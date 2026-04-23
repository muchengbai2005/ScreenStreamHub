package com.mcbcc.mcbtm.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MediaProjectionAudioSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.ICloseableStreamer
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.services.MediaProjectionService
import io.github.thibaultbee.streampack.services.utils.StreamerFactory
import com.mcbcc.mcbtm.R
import com.mcbcc.mcbtm.endpoints.CombinedEndpointFactory
import com.mcbcc.mcbtm.models.Actions
import kotlinx.coroutines.launch

class DemoMediaProjectionService : MediaProjectionService<ISingleStreamer>(
    streamerFactory = CustomStreamerFactory(),
    notificationId = 0x4569,
    channelId = "com.mcbcc.mcbtm.screenrecorder.demo",
    channelNameResourceId = R.string.app_name
) {
    override fun createDefaultAudioSource(
        mediaProjection: MediaProjection,
        extras: Bundle
    ): IAudioSourceInternal.Factory {
        val audioSource = extras.getString(AUDIO_SOURCE_KEY)
        return if (audioSource == AUDIO_SOURCE_MEDIA_PROJECTION_KEY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaProjectionAudioSourceFactory(mediaProjection)
            } else {
                throw UnsupportedOperationException(
                    "Media projection audio source is not supported on this version of Android"
                )
            }
        } else if (audioSource == AUDIO_SOURCE_MICROPHONE_KEY) {
            MicrophoneSourceFactory()
        } else {
            throw IllegalArgumentException(
                "Audio source $audioSource is not supported. Use $AUDIO_SOURCE_MEDIA_PROJECTION_KEY or $AUDIO_SOURCE_MICROPHONE_KEY"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            streamer.let {
                if (intent.action == Actions.STOP.value) {
                    lifecycleScope.launch {
                        streamer.stopStream()
                        (streamer as? ICloseableStreamer)?.close()
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onOpenNotification(): Notification {
        val intent =
            Intent(this, DemoMediaProjectionService::class.java).setAction(Actions.STOP.value)
        val stopIntent =
            PendingIntent.getService(this, 5678, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(notificationIconResourceId)
            .setContentTitle(getString(R.string.live_in_progress))
            .addAction(
                R.drawable.ic_baseline_stop_24,
                getString(R.string.stop),
                stopIntent
            )
            .build()
    }

    companion object {
        internal const val AUDIO_SOURCE_KEY = "audioSource"
        internal const val AUDIO_SOURCE_MICROPHONE_KEY = "microphone"
        internal const val AUDIO_SOURCE_MEDIA_PROJECTION_KEY = "mediaProjection"
    }
}

class CustomStreamerFactory : StreamerFactory<ISingleStreamer> {
    override fun create(context: Context): ISingleStreamer {
        return SingleStreamer(
            context,
            withAudio = true,
            withVideo = true,
            endpointFactory = CombinedEndpointFactory()
        )
    }
}
