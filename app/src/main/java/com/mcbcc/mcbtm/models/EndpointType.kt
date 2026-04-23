package com.mcbcc.mcbtm.models

enum class EndpointType(val id: Int) {
    SRT(0),
    RTMP(1),
    WEBSOCKET(2);

    companion object {
        fun fromId(id: Int): EndpointType = entries.first { it.id == id }
    }
}