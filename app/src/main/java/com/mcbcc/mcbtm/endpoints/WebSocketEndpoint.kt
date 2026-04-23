package com.mcbcc.mcbtm.endpoints

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.FrameWithCloseable
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val sendQueue = ArrayDeque<ByteArray>()
    private val isSending = AtomicBoolean(false)
    private val streamPidMap = ConcurrentHashMap<CodecConfig, Int>()
    private var nextStreamId = 1
    
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var frameCounter = 0
    private var firstKeyFrameReceived = false

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
        websocketUrl = getWebSocketUrlFromDescriptor(descriptor)
        Log.d(TAG, "Opening WebSocket connection to: $websocketUrl")
        
        client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(websocketUrl)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "WebSocket connected: $websocketUrl")
                isConnected.set(true)
                _isOpenFlow.value = true
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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket error", t)
                isConnected.set(false)
                _throwableFlow.value = t
            }
        })
    }

    private fun getWebSocketUrlFromDescriptor(descriptor: MediaDescriptor): String {
        val customDataStr = descriptor.uri.getQueryParameter("url")
        if (!customDataStr.isNullOrEmpty()) {
            return customDataStr
        }
        return "ws://${descriptor.uri.host}:${descriptor.uri.port ?: 8765}${descriptor.uri.path ?: "/stream"}"
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
            
            frameCounter++
            
            if (buffer.hasRemaining()) {
                val byteArray = ByteArray(buffer.remaining())
                buffer.get(byteArray)
                
                Log.d(TAG, "Frame $frameCounter: isKeyFrame=${frame.isKeyFrame}, size=${byteArray.size} bytes")
                Log.d(TAG, "  frame.extra: ${frame.extra?.size ?: 0} buffers")
                
                // 检查 MediaFormat 中的 csd 数据
                if (frame.isKeyFrame) {
                    val csd0 = format.getByteBuffer("csd-0")
                    val csd1 = format.getByteBuffer("csd-1")
                    Log.d(TAG, "  csd-0 present: ${csd0 != null}, csd-1 present: ${csd1 != null}")
                    
                    if (csd0 != null) {
                        val csd0Data = ByteArray(csd0.remaining())
                        csd0.get(csd0Data)
                        csd0.position(0) // 恢复position
                        Log.d(TAG, "  csd-0 size: ${csd0Data.size}")
                        Log.d(TAG, "  csd-0 first 10 bytes: ${csd0Data.take(10).joinToString(" ") { "%02x".format(it) }}")
                    }
                }
                
                if (frame.isKeyFrame) {
                    firstKeyFrameReceived = true
                    
                    // 尝试从 frame.extra 提取 SPS/PPS
                    if (frame.extra != null && frame.extra!!.isNotEmpty()) {
                        Log.d(TAG, "Attempting to extract SPS/PPS from frame.extra")
                        extractSpsPpsFromExtra(frame.extra!!)
                    } else {
                        Log.w(TAG, "frame.extra is null or empty!")
                        
                        // 尝试从 MediaFormat 直接获取
                        extractSpsPpsFromMediaFormat(format)
                    }
                    
                    // 每次关键帧前都发送 SPS/PPS（确保解码器有最新配置）
                    if (spsData != null && ppsData != null) {
                        Log.d(TAG, "Key frame detected, sending SPS/PPS before frame")
                        synchronized(sendQueue) {
                            // 正确顺序：SPS -> PPS -> I帧
                            // 先添加PPS，再添加I帧，最后把SPS放到最前面
                            sendQueue.add(ppsData!!)
                            sendQueue.add(byteArray)
                            sendQueue.addFirst(spsData!!)
                        }
                        Log.d(TAG, "SPS/PPS queued before key frame (order: SPS -> PPS -> I-frame)")
                        processQueue()
                        return
                    }
                }
                
                synchronized(sendQueue) {
                    sendQueue.add(byteArray)
                }

                processQueue()
            }
        } finally {
            closeableFrame.close()
        }
    }

    private fun extractSpsPpsFromExtra(extra: List<ByteBuffer>) {
        Log.d(TAG, "extractSpsPpsFromExtra: ${extra.size} buffers")
        
        extra.forEachIndexed { index, buffer ->
            val originalPosition = buffer.position()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            buffer.position(originalPosition)
            
            Log.d(TAG, "  Extra buffer $index: ${data.size} bytes")
            Log.d(TAG, "  First 10 bytes: ${data.take(10).joinToString(" ") { "%02x".format(it) }}")
            
            if (data.isNotEmpty()) {
                val hasStartCode = data.size >= 4 && 
                    data[0] == 0.toByte() && data[1] == 0.toByte() && 
                    data[2] == 0.toByte() && data[3] == 1.toByte()
                
                val nalStart = if (hasStartCode) 4 else 0
                
                if (data.size > nalStart) {
                    val nalType = data[nalStart].toInt() and 0x1F
                    
                    Log.d(TAG, "  NAL type: $nalType (${getNalTypeName(nalType)}), hasStartCode=$hasStartCode")
                    
                    when (nalType) {
                        7 -> {
                            spsData = if (hasStartCode) data else createAnnexBFrame(data)
                            Log.d(TAG, "  SPS stored from extra, size=${spsData?.size}")
                        }
                        8 -> {
                            ppsData = if (hasStartCode) data else createAnnexBFrame(data)
                            Log.d(TAG, "  PPS stored from extra, size=${ppsData?.size}")
                        }
                    }
                }
            }
        }
    }

    private fun extractSpsPpsFromMediaFormat(format: MediaFormat) {
        Log.d(TAG, "extractSpsPpsFromMediaFormat")
        
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        
        if (csd0 != null) {
            val data = ByteArray(csd0.remaining())
            csd0.get(data)
            csd0.position(0) // 恢复position
            
            Log.d(TAG, "  csd-0 (SPS): ${data.size} bytes")
            Log.d(TAG, "  First 10 bytes: ${data.take(10).joinToString(" ") { "%02x".format(it) }}")
            
            val hasStartCode = data.size >= 4 && 
                data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte()
            
            spsData = if (hasStartCode) data else createAnnexBFrame(data)
            Log.d(TAG, "  SPS stored from MediaFormat, size=${spsData?.size}")
        }
        
        if (csd1 != null) {
            val data = ByteArray(csd1.remaining())
            csd1.get(data)
            csd1.position(0) // 恢复position
            
            Log.d(TAG, "  csd-1 (PPS): ${data.size} bytes")
            Log.d(TAG, "  First 10 bytes: ${data.take(10).joinToString(" ") { "%02x".format(it) }}")
            
            val hasStartCode = data.size >= 4 && 
                data[0] == 0.toByte() && data[1] == 0.toByte() && 
                data[2] == 0.toByte() && data[3] == 1.toByte()
            
            ppsData = if (hasStartCode) data else createAnnexBFrame(data)
            Log.d(TAG, "  PPS stored from MediaFormat, size=${ppsData?.size}")
        }
    }

    private fun getNalTypeName(type: Int): String {
        return when (type) {
            1 -> "P-frame"
            5 -> "I-frame"
            7 -> "SPS"
            8 -> "PPS"
            6 -> "SEI"
            else -> "Unknown($type)"
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
                    while (isConnected.get()) {
                        val data = synchronized(sendQueue) {
                            if (sendQueue.isEmpty()) null else sendQueue.removeFirst()
                        }

                        if (data == null) {
                            break
                        }

                        webSocket?.send(ByteString.of(*data))
                        
                        logNalType(data)
                    }
                } finally {
                    isSending.set(false)
                }
            }
        }
    }

    private fun logNalType(data: ByteArray) {
        if (data.size < 4) return
        
        val nalStart = if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            4
        } else if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            3
        } else {
            return
        }
        
        if (data.size <= nalStart) return
        
        val nalType = data[nalStart].toInt() and 0x1F
        
        Log.d(TAG, "Sent NAL type ${getNalTypeName(nalType)} (${data.size} bytes)")
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
    }

    override suspend fun stopStream() {
        Log.d(TAG, "stopStream called")
        sendQueue.clear()
    }

    override suspend fun close() {
        Log.d(TAG, "close called")
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
