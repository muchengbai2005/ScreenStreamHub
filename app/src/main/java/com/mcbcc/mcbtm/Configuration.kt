package com.mcbcc.mcbtm

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.media.MediaFormat
import android.util.Size
import androidx.preference.PreferenceManager
import com.mcbcc.mcbtm.models.EndpointType

class Configuration(context: Context) {
    private val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
    private val resources = context.resources
    val video = Video(sharedPref, resources)
    val audio = Audio(sharedPref, resources)
    val muxer = Muxer(sharedPref, resources)
    val endpoint = Endpoint(sharedPref, resources)

    class Video(private val sharedPref: SharedPreferences, private val resources: Resources) {
        var encoder: String = MediaFormat.MIMETYPE_VIDEO_AVC
            get() = sharedPref.getString(resources.getString(R.string.video_encoder_key), field)!!

        var resolution: Size = Size(1280, 720)
            get() {
                val res = sharedPref.getString(
                    resources.getString(R.string.video_resolution_key),
                    field.toString()
                )!!
                val resArray = res.split("x")
                return Size(
                    resArray[0].toInt(),
                    resArray[1].toInt()
                )
            }

        var bitrate: Int = 800
            get() = sharedPref.getInt(resources.getString(R.string.video_bitrate_key), field)

        var fps: Int = 5
            get() = sharedPref.getString(resources.getString(R.string.video_fps_key), field.toString())!!.toInt()

        var orientation: String = "landscape"
            get() = sharedPref.getString(resources.getString(R.string.video_orientation_key), field)!!
    }

    class Audio(private val sharedPref: SharedPreferences, private val resources: Resources) {
        var enable: Boolean = false
            get() = sharedPref.getBoolean(resources.getString(R.string.audio_enable_key), field)

        var encoder: String = MediaFormat.MIMETYPE_AUDIO_AAC
            get() = sharedPref.getString(resources.getString(R.string.audio_encoder_key), field)!!

        var numberOfChannels: Int = 2
            get() = sharedPref.getString(
                resources.getString(R.string.audio_number_of_channels_key),
                field.toString()
            )!!.toInt()

        var bitrate: Int = 128000
            get() = sharedPref.getString(
                resources.getString(R.string.audio_bitrate_key),
                field.toString()
            )!!.toInt()

        var sampleRate: Int = 48000
            get() = sharedPref.getString(
                resources.getString(R.string.audio_sample_rate_key),
                field.toString()
            )!!.toInt()


        val byteFormat: Int = 2
            get() = sharedPref.getString(
                resources.getString(R.string.audio_byte_format_key),
                field.toString()
            )!!.toInt()
    }

    class Muxer(private val sharedPref: SharedPreferences, private val resources: Resources) {
        var service: String = resources.getString(R.string.default_muxer_service)
            get() = sharedPref.getString(
                resources.getString(R.string.ts_muxer_service_key),
                field
            )!!

        var provider: String = resources.getString(R.string.default_ts_muxer_provider)
            get() = sharedPref.getString(
                resources.getString(R.string.ts_muxer_provider_key),
                field
            )!!
    }

    class Endpoint(private val sharedPref: SharedPreferences, private val resources: Resources) {
        val srt = SrtConnection(sharedPref, resources)
        val rtmp = RtmpConnection(sharedPref, resources)
        val websocket = WebSocketConnection(sharedPref, resources)

        val type: EndpointType
            get() {
                val endpointId = sharedPref.getString(
                    resources.getString(R.string.endpoint_type_key),
                    "${EndpointType.SRT.id}"
                )!!.toInt()

                return EndpointType.fromId(endpointId)
            }

        class SrtConnection(
            private val sharedPref: SharedPreferences,
            private val resources: Resources
        ) {
            var ip: String = ""
                get() = sharedPref.getString(resources.getString(R.string.server_ip_key), field)!!

            var port: Int = 9998
                get() = sharedPref.getString(
                    resources.getString(R.string.server_port_key),
                    field.toString()
                )!!.toInt()

            var streamID: String = ""
                get() = sharedPref.getString(
                    resources.getString(R.string.server_stream_id_key),
                    field
                )!!

            var passPhrase: String = ""
                get() = sharedPref.getString(
                    resources.getString(R.string.server_passphrase_key),
                    field
                )!!
        }

        class RtmpConnection(
            private val sharedPref: SharedPreferences,
            private val resources: Resources
        ) {
            var url: String = resources.getString(R.string.default_rtmp_url)
                get() = sharedPref.getString(
                    resources.getString(R.string.rtmp_server_url_key),
                    field
                )!!
        }

        class WebSocketConnection(
            private val sharedPref: SharedPreferences,
            private val resources: Resources
        ) {
            var url: String = resources.getString(R.string.default_websocket_url)
                get() = sharedPref.getString(
                    resources.getString(R.string.websocket_server_url_key),
                    field
                )!!

            var enableBFrames: Boolean = false
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_enable_b_frames_key), field)

            var keyframeInterval: Int = 2
                get() = sharedPref.getInt(resources.getString(R.string.websocket_keyframe_interval_key), field)

            var autoReconnect: Boolean = false
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_auto_reconnect_key), field)

            var reconnectMaxAttempts: Int = 0
                get() = sharedPref.getInt(resources.getString(R.string.websocket_reconnect_max_attempts_key), field)

            var enableFpsLimit: Boolean = false
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_enable_fps_limit_key), field)

            var targetFps: Int = 10
                get() = sharedPref.getInt(resources.getString(R.string.websocket_target_fps_key), field)

            var frameDropStrategy: String = "evenly_timed"
                get() = sharedPref.getString(resources.getString(R.string.websocket_frame_drop_strategy_key), field)!!

            var keyframeOnly: Boolean = false
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_keyframe_only_key), field)

            var connectTimeout: Int = 10
                get() = sharedPref.getInt(resources.getString(R.string.websocket_connect_timeout_key), field)

            var sendQueueSize: Int = 30
                get() = sharedPref.getInt(resources.getString(R.string.websocket_send_queue_size_key), field)

            var queueOverflowStrategy: String = "drop_oldest"
                get() = sharedPref.getString(resources.getString(R.string.websocket_queue_overflow_strategy_key), field)!!

            var batchSend: Boolean = false
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_batch_send_key), field)

            var batchInterval: Int = 20
                get() = sharedPref.getInt(resources.getString(R.string.websocket_batch_interval_key), field)

            var nagleEnabled: Boolean = false
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_nagle_enabled_key), field)

            var pingInterval: Int = 0
                get() = sharedPref.getInt(resources.getString(R.string.websocket_ping_interval_key), field)

            var maxMessageSize: Int = 64
                get() = sharedPref.getInt(resources.getString(R.string.websocket_max_message_size_key), field)

            var iFramePriority: Boolean = true
                get() = sharedPref.getBoolean(resources.getString(R.string.websocket_i_frame_priority_key), field)
        }
    }

}