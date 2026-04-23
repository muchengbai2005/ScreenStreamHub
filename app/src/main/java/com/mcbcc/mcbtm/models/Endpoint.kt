package com.mcbcc.mcbtm.models

sealed class Endpoint(
    val hasTSCapabilities: Boolean,
    val hasFLVCapabilities: Boolean,
    val hasSrtCapabilities: Boolean,
    val hasRtmpCapabilities: Boolean,
    val hasWebSocketCapabilities: Boolean = false
) {
    class SrtEndpoint : Endpoint(true, false, true, false)
    class RtmpEndpoint : Endpoint(false, true, false, true)
    class WebSocketEndpoint : Endpoint(false, false, false, false, true)
}

class EndpointFactory(private val type: EndpointType) {
    fun build(): Endpoint {
        return when (type) {
            EndpointType.SRT -> Endpoint.SrtEndpoint()
            EndpointType.RTMP -> Endpoint.RtmpEndpoint()
            EndpointType.WEBSOCKET -> Endpoint.WebSocketEndpoint()
        }
    }
}