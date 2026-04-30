package com.mcbcc.mcbtm.endpoints

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import androidx.preference.PreferenceManager
import com.mcbcc.mcbtm.R
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.FrameWithCloseable
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class QueuedFrame(
    val data: ByteArray,
    val isKeyFrame: Boolean,
    val naluType: Int
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class WebSocketEndpoint(
    private val context: Context,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) : IEndpointInternal {
    private val TAG = "WebSocketEndpoint"

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var websocketUrl: String = ""
    private val _isOpenFlow = MutableStateFlow(false)
    private val _throwableFlow = MutableStateFlow<Throwable?>(null)
    private val isConnected = AtomicBoolean(false)
    private val sendQueue = ArrayDeque<QueuedFrame>()
    private val isSending = AtomicBoolean(false)
    private val streamPidMap = ConcurrentHashMap<CodecConfig, Int>()
    private var nextStreamId = 1

    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var frameCounter = 0
    private var firstKeyFrameReceived = false

    private var savedDescriptor: MediaDescriptor? = null
    private var isIntentionalClose = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val isReconnecting = AtomicBoolean(false)

    private var droppedFrameCount = 0
    private var sentFrameCount = 0

    private var lastSentFrameTimeMs: Long = 0
    private var sendBudgetRemaining = 0
    private var isInSendSequence = false

    private var pingJob: Job? = null

    private val sharedPref by lazy { PreferenceManager.getDefaultSharedPreferences(context) }

    private val autoReconnectEnabled: Boolean
        get() = sharedPref.getBoolean(context.getString(R.string.websocket_auto_reconnect_key), false)

    private val reconnectMaxAttempts: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_reconnect_max_attempts_key), 0)

    private val fpsLimitEnabled: Boolean
        get() = sharedPref.getBoolean(context.getString(R.string.websocket_enable_fps_limit_key), false)

    private val targetFps: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_target_fps_key), 10)

    private val frameDropStrategy: String
        get() = sharedPref.getString(context.getString(R.string.websocket_frame_drop_strategy_key), "evenly_timed") ?: "evenly_timed"

    private val keyframeOnlyEnabled: Boolean
        get() = sharedPref.getBoolean(context.getString(R.string.websocket_keyframe_only_key), false)

    private val connectTimeoutSec: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_connect_timeout_key), 10)

    private val sendQueueMaxSize: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_send_queue_size_key), 30)

    private val queueOverflowStrategy: String
        get() = sharedPref.getString(context.getString(R.string.websocket_queue_overflow_strategy_key), "drop_oldest") ?: "drop_oldest"

    private val batchSendEnabled: Boolean
        get() = sharedPref.getBoolean(context.getString(R.string.websocket_batch_send_key), false)

    private val batchIntervalMs: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_batch_interval_key), 20)

    private val nagleEnabled: Boolean
        get() = sharedPref.getBoolean(context.getString(R.string.websocket_nagle_enabled_key), false)

    private val pingIntervalSec: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_ping_interval_key), 0)

    private val maxMessageSizeKb: Int
        get() = sharedPref.getInt(context.getString(R.string.websocket_max_message_size_key), 64)

    private val iFramePriorityEnabled: Boolean
        get() = sharedPref.getBoolean(context.getString(R.string.websocket_i_frame_priority_key), true)

    override val isOpenFlow: StateFlow<Boolean> = _isOpenFlow.asStateFlow()
    override val throwableFlow: StateFlow<Throwable?> = _throwableFlow.asStateFlow()

    override val info: IEndpoint.IEndpointInfo = object : IEndpoint.IEndpointInfo {
        override val audio: IEndpoint.IEndpointInfo.IAudioEndpointInfo =
            object : IEndpoint.IEndpointInfo.IAudioEndpointInfo {
                override val supportedEncoders: List<String> = emptyList()
                override val supportedSampleRates: List<Int>? = null
                override val supportedByteFormats: List<Int>? = null
            }

        override val video: IEndpoint.IEndpointInfo.IVideoEndpointInfo =
            object : IEndpoint.IEndpointInfo.IVideoEndpointInfo {
                override val supportedEncoders: List<String> = listOf("video/avc")
            }
    }

    override val metrics: Any = emptyMap<String, Any>()

    override fun getInfo(type: MediaDescriptor.Type): IEndpoint.IEndpointInfo {
        return info
    }

    override suspend fun open(descriptor: MediaDescriptor) {
        savedDescriptor = descriptor
        isIntentionalClose = false
        reconnectAttempt = 0
        connectInternal(descriptor)
    }

    private suspend fun connectInternal(descriptor: MediaDescriptor) {
        websocketUrl = getWebSocketUrlFromDescriptor(descriptor)
        Log.d(TAG, "Opening WebSocket connection to: $websocketUrl")
        Log.d(TAG, "Settings: timeout=${connectTimeoutSec}s, queue=${sendQueueMaxSize}, " +
                "overflow=$queueOverflowStrategy, batch=$batchSendEnabled(${batchIntervalMs}ms), " +
                "nagle=$nagleEnabled, ping=${pingIntervalSec}s, maxMsg=${maxMessageSizeKb}KB, " +
                "iFramePriority=$iFramePriorityEnabled, fpsLimit=$fpsLimitEnabled(${targetFps}fps), " +
                "dropStrategy=$frameDropStrategy, keyframeOnly=$keyframeOnlyEnabled, " +
                "autoReconnect=$autoReconnectEnabled(maxAttempts=$reconnectMaxAttempts)")

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)

        if (pingIntervalSec > 0) {
            builder.pingInterval(pingIntervalSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        }

        client = builder.build()

        val request = Request.Builder()
            .url(websocketUrl)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "WebSocket connected: $websocketUrl")
                isConnected.set(true)
                _isOpenFlow.value = true
                reconnectAttempt = 0
                isReconnecting.set(false)
                startPingIfNeeded()
                processQueue()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                isConnected.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                isConnected.set(false)
                _isOpenFlow.value = false
                stopPing()
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected.set(false)
                _isOpenFlow.value = false
                _throwableFlow.value = t
                stopPing()
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun startPingIfNeeded() {
        stopPing()
        if (pingIntervalSec > 0) {
            pingJob = CoroutineScope(ioDispatcher).launch {
                while (isConnected.get()) {
                    delay(pingIntervalSec * 1000L)
                    if (isConnected.get()) {
                        val sent = webSocket?.send(ByteString.EMPTY)
                        Log.d(TAG, "Ping sent: $sent")
                    }
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled) {
            Log.d(TAG, "Auto reconnect is disabled, not reconnecting")
            return
        }

        if (savedDescriptor == null) {
            Log.w(TAG, "No saved descriptor, cannot reconnect")
            return
        }

        if (isIntentionalClose) {
            Log.d(TAG, "Intentional close, not reconnecting")
            return
        }

        if (reconnectMaxAttempts > 0 && reconnectAttempt >= reconnectMaxAttempts) {
            Log.d(TAG, "Max reconnect attempts ($reconnectMaxAttempts) reached, stopping")
            return
        }

        if (!isReconnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Reconnection already in progress")
            return
        }

        reconnectAttempt++
        val delaySeconds = minOf(1 shl (reconnectAttempt - 1), 30).toLong()
        Log.d(TAG, "Scheduling reconnect attempt #$reconnectAttempt in ${delaySeconds}s")

        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(ioDispatcher).launch {
            try {
                delay(delaySeconds * 1000)
                if (isIntentionalClose || !autoReconnectEnabled) {
                    Log.d(TAG, "Reconnect cancelled (intentional close or disabled)")
                    isReconnecting.set(false)
                    return@launch
                }
                Log.d(TAG, "Attempting reconnect #$reconnectAttempt to $websocketUrl")
                try {
                    client?.dispatcher?.executorService?.shutdown()
                    client = null
                    webSocket = null
                    connectInternal(savedDescriptor!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt #$reconnectAttempt failed: ${e.message}")
                    isReconnecting.set(false)
                    scheduleReconnect()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Reconnect job cancelled")
                isReconnecting.set(false)
            }
        }
    }

    private fun getWebSocketUrlFromDescriptor(descriptor: MediaDescriptor): String {
        val customDataStr = descriptor.uri.getQueryParameter("url")
        if (!customDataStr.isNullOrEmpty()) {
            return customDataStr
        }
        return "ws://${descriptor.uri.host}:${descriptor.uri.port ?: 8765}${descriptor.uri.path ?: "/stream"}"
    }

    private fun handleQueueOverflow() {
        while (sendQueue.size > sendQueueMaxSize) {
            when (queueOverflowStrategy) {
                "drop_oldest" -> {
                    val removed = sendQueue.removeFirst()
                    droppedFrameCount++
                    Log.d(TAG, "Queue overflow (drop_oldest): removed frame, type=${removed.naluType}, queue=${sendQueue.size}")
                }
                "drop_newest" -> {
                    sendQueue.removeLast()
                    droppedFrameCount++
                    Log.d(TAG, "Queue overflow (drop_newest): removed frame, queue=${sendQueue.size}")
                }
                "drop_p_only" -> {
                    val pFrameIdx = sendQueue.indexOfFirst { !it.isKeyFrame && it.naluType != 7 && it.naluType != 8 }
                    if (pFrameIdx >= 0) {
                        sendQueue.removeAt(pFrameIdx)
                        droppedFrameCount++
                        Log.d(TAG, "Queue overflow (drop_p_only): removed P-frame, queue=${sendQueue.size}")
                    } else {
                        sendQueue.removeFirst()
                        droppedFrameCount++
                        Log.d(TAG, "Queue overflow (drop_p_only): no P-frame, removed oldest, queue=${sendQueue.size}")
                    }
                }
                else -> {
                    sendQueue.removeFirst()
                    droppedFrameCount++
                }
            }
        }
    }

    override suspend fun write(closeableFrame: FrameWithCloseable, streamPid: Int) {
        try {
            if (!isConnected.get()) {
                Log.w(TAG, "Not connected, dropping frame")
                return
            }

            val frame = closeableFrame.frame
            val buffer = frame.rawBuffer
            val format = frame.format
            val isKeyFrame = frame.isKeyFrame

            frameCounter++

            if (shouldDropFrame(isKeyFrame)) {
                droppedFrameCount++
                if (droppedFrameCount % 100 == 0) {
                    Log.d(TAG, "Traffic control: dropped $droppedFrameCount, sent $sentFrameCount, strategy=$frameDropStrategy")
                }
                return
            }

            sentFrameCount++

            if (buffer.hasRemaining()) {
                val byteArray = ByteArray(buffer.remaining())
                buffer.get(byteArray)

                val maxBytes = maxMessageSizeKb * 1024
                if (byteArray.size > maxBytes) {
                    Log.w(TAG, "Frame too large (${byteArray.size} > ${maxBytes}), splitting or dropping")
                    val chunks = byteArray.asList().chunked(maxBytes)
                    for ((idx, chunk) in chunks.withIndex()) {
                        val chunkArray = chunk.toByteArray()
                        val naluType = if (idx == 0) (if (chunkArray.size > 4) chunkArray[4].toInt() and 0x1F else 0) else 0
                        synchronized(sendQueue) {
                            sendQueue.add(QueuedFrame(chunkArray, isKeyFrame && idx == 0, naluType))
                            handleQueueOverflow()
                        }
                    }
                    processQueue()
                    return
                }

                if (isKeyFrame) {
                    firstKeyFrameReceived = true

                    if (frame.extra != null && frame.extra!!.isNotEmpty()) {
                        extractSpsPpsFromExtra(frame.extra!!)
                    } else {
                        extractSpsPpsFromMediaFormat(format)
                    }

                    if (spsData != null && ppsData != null) {
                        synchronized(sendQueue) {
                            if (iFramePriorityEnabled) {
                                sendQueue.removeAll { !it.isKeyFrame && it.naluType != 7 && it.naluType != 8 }
                            }
                            sendQueue.add(QueuedFrame(spsData!!, true, 7))
                            sendQueue.add(QueuedFrame(ppsData!!, true, 8))
                            sendQueue.add(QueuedFrame(byteArray, true, 5))
                            handleQueueOverflow()
                        }
                        processQueue()
                        return
                    }
                }

                val naluType = if (byteArray.size > 4) byteArray[4].toInt() and 0x1F else 0
                synchronized(sendQueue) {
                    sendQueue.add(QueuedFrame(byteArray, isKeyFrame, naluType))
                    handleQueueOverflow()
                }

                processQueue()
            }
        } finally {
            closeableFrame.close()
        }
    }

    private fun shouldDropFrame(isKeyFrame: Boolean): Boolean {
        if (keyframeOnlyEnabled && !isKeyFrame) {
            return true
        }

        if (!fpsLimitEnabled) {
            return false
        }

        val fps = targetFps
        if (fps <= 0) {
            return false
        }

        return when (frameDropStrategy) {
            "evenly_timed" -> shouldDropFrameEvenlyTimed(isKeyFrame, fps)
            "burst_from_keyframe" -> shouldDropFrameBurstFromKeyframe(isKeyFrame, fps)
            else -> shouldDropFrameEvenlyTimed(isKeyFrame, fps)
        }
    }

    private fun shouldDropFrameEvenlyTimed(isKeyFrame: Boolean, fps: Int): Boolean {
        val now = System.currentTimeMillis()
        val minIntervalMs = 1000L / fps

        if (isKeyFrame) {
            lastSentFrameTimeMs = now
            return false
        }

        val elapsed = now - lastSentFrameTimeMs
        if (elapsed < minIntervalMs) {
            return true
        }

        lastSentFrameTimeMs = now
        return false
    }

    private fun shouldDropFrameBurstFromKeyframe(isKeyFrame: Boolean, fps: Int): Boolean {
        if (isKeyFrame) {
            isInSendSequence = true
            val gopSeconds = sharedPref.getInt(context.getString(R.string.websocket_keyframe_interval_key), 2).coerceAtLeast(1)
            sendBudgetRemaining = fps * gopSeconds
            return false
        }

        if (!isInSendSequence) {
            return true
        }

        if (sendBudgetRemaining <= 1) {
            isInSendSequence = false
            return true
        }

        sendBudgetRemaining--
        return false
    }

    private fun extractSpsPpsFromExtra(extra: List<ByteBuffer>) {
        extra.forEachIndexed { index, buffer ->
            val originalPosition = buffer.position()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            buffer.position(originalPosition)

            if (data.isNotEmpty()) {
                val hasStartCode = data.size >= 4 &&
                    data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 0.toByte() && data[3] == 1.toByte()

                val nalStart = if (hasStartCode) 4 else 0

                if (data.size > nalStart) {
                    val nalType = data[nalStart].toInt() and 0x1F

                    when (nalType) {
                        7 -> {
                            spsData = if (hasStartCode) data else createAnnexBFrame(data)
                        }
                        8 -> {
                            ppsData = if (hasStartCode) data else createAnnexBFrame(data)
                        }
                    }
                }
            }
        }
    }

    private fun extractSpsPpsFromMediaFormat(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")

        if (csd0 != null) {
            val data = ByteArray(csd0.remaining())
            csd0.get(data)
            csd0.position(0)

            val hasStartCode = data.size >= 4 &&
                data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte()

            spsData = if (hasStartCode) data else createAnnexBFrame(data)
        }

        if (csd1 != null) {
            val data = ByteArray(csd1.remaining())
            csd1.get(data)
            csd1.position(0)

            val hasStartCode = data.size >= 4 &&
                data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte()

            ppsData = if (hasStartCode) data else createAnnexBFrame(data)
        }
    }

    private fun createAnnexBFrame(data: ByteArray): ByteArray {
        val annexbData = ByteArray(data.size + 4)
        annexbData[0] = 0
        annexbData[1] = 0
        annexbData[2] = 0
        annexbData[3] = 1
        System.arraycopy(data, 0, annexbData, 4, data.size)
        return annexbData
    }

    private fun processQueue() {
        if (isSending.get()) return

        CoroutineScope(ioDispatcher).launch {
            if (isSending.compareAndSet(false, true)) {
                try {
                    if (batchSendEnabled) {
                        processQueueBatched()
                    } else {
                        processQueueDirect()
                    }
                } finally {
                    isSending.set(false)
                }
            }
        }
    }

    private suspend fun processQueueDirect() {
        while (isConnected.get()) {
            val data = synchronized(sendQueue) {
                if (sendQueue.isEmpty()) null else sendQueue.removeFirst()
            }

            if (data == null) {
                break
            }

            if (nagleEnabled) {
                val merged = mutableListOf<ByteArray>()
                merged.add(data.data)
                var totalSize = data.data.size

                synchronized(sendQueue) {
                    while (sendQueue.isNotEmpty()) {
                        val next = sendQueue.first()
                        if (totalSize + next.data.size > maxMessageSizeKb * 1024) break
                        merged.add(sendQueue.removeFirst().data)
                        totalSize += next.data.size
                    }
                }

                if (merged.size > 1) {
                    val mergedArray = ByteArray(totalSize)
                    var offset = 0
                    for (chunk in merged) {
                        System.arraycopy(chunk, 0, mergedArray, offset, chunk.size)
                        offset += chunk.size
                    }
                    webSocket?.send(ByteString.of(*mergedArray))
                } else {
                    webSocket?.send(ByteString.of(*data.data))
                }
            } else {
                webSocket?.send(ByteString.of(*data.data))
            }
        }
    }

    private suspend fun processQueueBatched() {
        while (isConnected.get()) {
            val batch = synchronized(sendQueue) {
                if (sendQueue.isEmpty()) return@synchronized null
                val items = mutableListOf<QueuedFrame>()
                var totalSize = 0
                while (sendQueue.isNotEmpty() && items.size < 10) {
                    val item = sendQueue.first()
                    if (totalSize + item.data.size > maxMessageSizeKb * 1024) break
                    items.add(sendQueue.removeFirst())
                    totalSize += item.data.size
                }
                items
            }

            if (batch == null || batch.isEmpty()) {
                break
            }

            if (batch.size == 1) {
                webSocket?.send(ByteString.of(*batch[0].data))
            } else {
                val totalSize = batch.sumOf { it.data.size }
                val mergedArray = ByteArray(totalSize)
                var offset = 0
                for (item in batch) {
                    System.arraycopy(item.data, 0, mergedArray, offset, item.data.size)
                    offset += item.data.size
                }
                webSocket?.send(ByteString.of(*mergedArray))
            }

            delay(batchIntervalMs.toLong())
        }
    }

    override suspend fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> {
        val result = mutableMapOf<CodecConfig, Int>()
        streamConfigs.forEach { config ->
            result[config] = addStream(config)
        }
        return result
    }

    override suspend fun addStream(streamConfig: CodecConfig): Int {
        val streamId = nextStreamId++
        streamPidMap[streamConfig] = streamId
        return streamId
    }

    override suspend fun startStream() {
        Log.d(TAG, "startStream called")
        droppedFrameCount = 0
        sentFrameCount = 0
        lastSentFrameTimeMs = 0
        sendBudgetRemaining = 0
        isInSendSequence = false
    }

    override suspend fun stopStream() {
        Log.d(TAG, "stopStream called - sent: $sentFrameCount, dropped: $droppedFrameCount")
        sendQueue.clear()
    }

    override suspend fun close() {
        Log.d(TAG, "close called")
        isIntentionalClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting.set(false)
        stopPing()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        isConnected.set(false)
        _isOpenFlow.value = false
    }

    override suspend fun release() {
        Log.d(TAG, "release called")
        stopStream()
        close()
        spsData = null
        ppsData = null
        frameCounter = 0
        firstKeyFrameReceived = false
        savedDescriptor = null
        reconnectAttempt = 0
        droppedFrameCount = 0
        sentFrameCount = 0
        lastSentFrameTimeMs = 0
        sendBudgetRemaining = 0
        isInSendSequence = false
    }

    companion object : IEndpointInternal.Factory {
        override fun create(
            context: Context,
            dispatcherProvider: IDispatcherProvider
        ): IEndpointInternal {
            return WebSocketEndpoint(context, dispatcherProvider.io)
        }
    }
}
