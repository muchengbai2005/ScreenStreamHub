package com.mcbcc.mcbtm.endpoints

import android.net.Uri
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaContainerType
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType

class WebSocketMediaDescriptor(val websocketUrl: String) : MediaDescriptor(
    type = Type(MediaContainerType.FLV, MediaSinkType.CONTENT),
    customData = listOf(WebSocketCustomData(websocketUrl))
) {
    override val uri: Uri = Uri.parse("ws://websocket/stream?url=${Uri.encode(websocketUrl)}")

    data class WebSocketCustomData(val url: String)
}
