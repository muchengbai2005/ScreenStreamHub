package com.mcbcc.mcbtm.endpoints

import android.content.Context
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.FrameWithCloseable
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpointFactory
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CombinedEndpointFactory : IEndpointInternal.Factory {
    override fun create(
        context: Context,
        dispatcherProvider: IDispatcherProvider
    ): IEndpointInternal {
        return CombinedEndpoint(context, dispatcherProvider)
    }
}

class CombinedEndpoint(
    private val context: Context,
    private val dispatcherProvider: IDispatcherProvider
) : IEndpointInternal {

    private var currentEndpoint: IEndpointInternal? = null
    private val dynamicEndpointFactory = DynamicEndpointFactory()
    private val webSocketEndpointFactory = WebSocketEndpoint

    private val _isOpenFlow = MutableStateFlow(false)
    private val _throwableFlow = MutableStateFlow<Throwable?>(null)

    override val isOpenFlow: StateFlow<Boolean> = _isOpenFlow.asStateFlow()
    override val throwableFlow: StateFlow<Throwable?> = _throwableFlow.asStateFlow()

    override val info: IEndpoint.IEndpointInfo
        get() = currentEndpoint?.info ?: throw IllegalStateException("Endpoint not opened")

    override val metrics: Any
        get() = currentEndpoint?.metrics ?: throw IllegalStateException("Endpoint not opened")

    override fun getInfo(type: MediaDescriptor.Type): IEndpoint.IEndpointInfo {
        return dynamicEndpointFactory.create(context, dispatcherProvider).getInfo(type)
    }

    private fun isWebSocketDescriptor(descriptor: MediaDescriptor): Boolean {
        return descriptor.uri.scheme == "ws" || descriptor.uri.scheme == "wss"
    }

    private fun getEndpointFactory(descriptor: MediaDescriptor): IEndpointInternal.Factory {
        return if (isWebSocketDescriptor(descriptor)) {
            webSocketEndpointFactory
        } else {
            dynamicEndpointFactory
        }
    }

    override suspend fun open(descriptor: MediaDescriptor) {
        val factory = getEndpointFactory(descriptor)
        currentEndpoint = factory.create(context, dispatcherProvider)
        
        currentEndpoint?.open(descriptor)
        
        CoroutineScope(Dispatchers.Main).launch {
            currentEndpoint?.isOpenFlow?.collect { isOpen ->
                _isOpenFlow.value = isOpen
            }
        }
        
        while (!currentEndpoint?.isOpenFlow?.value!!) {
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun write(closeableFrame: FrameWithCloseable, streamPid: Int) {
        currentEndpoint?.write(closeableFrame, streamPid)
    }

    override suspend fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> {
        return currentEndpoint?.addStreams(streamConfigs) ?: emptyMap()
    }

    override suspend fun addStream(streamConfig: CodecConfig): Int {
        return currentEndpoint?.addStream(streamConfig) ?: -1
    }

    override suspend fun startStream() {
        currentEndpoint?.startStream()
    }

    override suspend fun stopStream() {
        currentEndpoint?.stopStream()
    }

    override suspend fun close() {
        currentEndpoint?.close()
        currentEndpoint = null
        _isOpenFlow.value = false
    }

    override suspend fun release() {
        currentEndpoint?.release()
        currentEndpoint = null
        _isOpenFlow.value = false
    }
}
