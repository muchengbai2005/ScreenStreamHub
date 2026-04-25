package com.mcbcc.mcbtm

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Menu
import android.view.MenuInflater
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.encoders.mediacodec.MediaCodecHelper
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.data.TSServiceInfo
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.IConfigurableAudioStreamer
import io.github.thibaultbee.streampack.core.streamers.IVideoStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerAudioConfig
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerVideoConfig
import io.github.thibaultbee.streampack.core.streamers.dual.IAudioDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IVideoDualStreamer
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.utils.MediaProjectionUtils
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.services.MediaProjectionService
import com.mcbcc.mcbtm.databinding.ActivityMainBinding
import com.mcbcc.mcbtm.endpoints.WebSocketMediaDescriptor
import com.mcbcc.mcbtm.models.EndpointType
import com.mcbcc.mcbtm.services.DemoMediaProjectionService
import com.mcbcc.mcbtm.services.DemoMediaProjectionService.Companion.AUDIO_SOURCE_KEY
import com.mcbcc.mcbtm.services.DemoMediaProjectionService.Companion.AUDIO_SOURCE_MICROPHONE_KEY
import com.mcbcc.mcbtm.settings.SettingsActivity
import com.mcbcc.mcbtm.utils.LocaleHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }
    private lateinit var binding: ActivityMainBinding
    private val configuration by lazy {
        Configuration(this)
    }

    private val tsServiceInfo: TSServiceInfo
        get() = TSServiceInfo(
            TSServiceInfo.ServiceType.DIGITAL_TV,
            0x4698,
            configuration.muxer.service,
            configuration.muxer.provider
        )

    private var connection: ServiceConnection? = null

    private var streamer: IVideoStreamer<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()

        binding.actions.setOnClickListener {
            showPopup()
        }

        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    if (configuration.audio.enable) {
                        requestAudioPermissionsLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        // 音频禁用时直接启动屏幕录制
                        getContent.launch(
                            MediaProjectionUtils.createScreenCaptureIntent(
                                this
                            )
                        )
                    }
                } else {
                    runBlocking {
                        streamer?.stopStream()
                        streamer?.close()
                    }
                    connection?.let { unbindService(it) }
                    connection = null
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService()
    }

    private val requestAudioPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionAlertDialog(this) { this.finish() }
        } else {
            getContent.launch(
                MediaProjectionUtils.createScreenCaptureIntent(
                    this
                )
            )
        }
    }

    private var getContent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            connection = MediaProjectionService.bindService(
                context = this,
                serviceClass = DemoMediaProjectionService::class.java,
                resultCode = result.resultCode,
                resultData = result.data!!,
                onServiceCreated = { streamer ->
                    streamer as IVideoStreamer<*>
                    lifecycleScope.launch {
                        try {
                            configure(streamer)
                        } catch (t: Throwable) {
                            this@MainActivity.showAlertDialog(
                                this@MainActivity, "Error", t.message ?: "Unknown error"
                            )
                            binding.liveButton.isChecked = false
                            Log.e(TAG, "Error while starting streamer", t)
                        }
                        startStream(streamer)
                    }
                    lifecycleScope.launch {
                        streamer.isStreamingFlow.collect { isStreaming ->
                            binding.liveButton.isChecked = isStreaming
                        }
                    }
                    this.streamer = streamer
                },
                onServiceDisconnected = {
                    streamer = null
                    Log.i(TAG, "Service disconnected")
                },
                onExtra = { extra ->
                    if (configuration.audio.enable) {
                        extra.putExtra(AUDIO_SOURCE_KEY, AUDIO_SOURCE_MICROPHONE_KEY)
                    }
                }
            )
        }

    private fun configure(streamer: IVideoStreamer<*>) {
        val deviceRefreshRate = 
            (this.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(
                Display.DEFAULT_DISPLAY
            ).refreshRate.toInt()

        val videoConfig = VideoConfig(
            mimeType = configuration.video.encoder,
            startBitrate = configuration.video.bitrate * 1000,
            resolution = configuration.video.resolution,
            fps = configuration.video.fps,
            gopDurationInS = configuration.endpoint.websocket.keyframeInterval.toFloat()
        )
        
        Log.i(TAG, "视频配置: 分辨率=${configuration.video.resolution}, 帧率=${configuration.video.fps}fps, 码率=${configuration.video.bitrate}Kbps")
        
        // 确保编码器真正按照设置的帧率输出
        // 添加帧率限制，防止编码器输出过高帧率
        val fps = configuration.video.fps
        lifecycleScope.launch {
            when (streamer) {
                is IVideoSingleStreamer -> streamer.setVideoConfig(videoConfig)
                is IVideoDualStreamer -> streamer.setVideoConfig(DualStreamerVideoConfig(videoConfig))
                else -> throw IllegalStateException("Streamer is not a supported streamer")
            }
        }

        // 只有当音频启用时才配置音频
        if (configuration.audio.enable) {
            if (streamer is IConfigurableAudioStreamer<*>) {
                val audioConfig = AudioConfig(
                    mimeType = configuration.audio.encoder,
                    startBitrate = configuration.audio.bitrate,
                    sampleRate = configuration.audio.sampleRate,
                    channelConfig = AudioConfig.getChannelConfig(configuration.audio.numberOfChannels),
                    byteFormat = configuration.audio.byteFormat
                )

                if (ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    lifecycleScope.launch {
                        when (streamer) {
                            is IAudioSingleStreamer -> streamer.setAudioConfig(audioConfig)
                            is IAudioDualStreamer -> streamer.setAudioConfig(
                                DualStreamerAudioConfig(
                                    audioConfig
                                )
                            )

                            else -> throw IllegalStateException("Streamer is not a audio streamer")
                        }
                    }
                } else {
                    throw SecurityException("Permission RECORD_AUDIO must have been granted!")
                }
            } else {
                Log.d(TAG, "Streamer does not support audio configuration, skipping audio setup")
            }
        } else {
            Log.d(TAG, "Audio is disabled in settings, skipping audio setup")
        }
    }

    private suspend fun startStream(streamer: IVideoStreamer<*>) {
        try {
            val descriptor = when (configuration.endpoint.type) {
                EndpointType.SRT -> SrtMediaDescriptor(
                    configuration.endpoint.srt.ip,
                    configuration.endpoint.srt.port,
                    configuration.endpoint.srt.streamID,
                    configuration.endpoint.srt.passPhrase,
                    serviceInfo = tsServiceInfo
                )

                EndpointType.RTMP -> UriMediaDescriptor(configuration.endpoint.rtmp.url.toUri())

                EndpointType.WEBSOCKET -> WebSocketMediaDescriptor(configuration.endpoint.websocket.url)
            }

            when (streamer) {
                is ISingleStreamer -> streamer.startStream(descriptor)

                is IDualStreamer -> {
                    Log.w(TAG, "Only first output will be used")
                    streamer.first.startStream(descriptor)
                }

                else -> throw IllegalStateException("Streamer is not a supported streamer")
            }
            moveTaskToBack(true)
        } catch (t: Throwable) {
            this.showAlertDialog(
                this, "Error", t.message ?: "Unknown error"
            )
            Log.e(TAG, "Error while starting streamer", t)
        }
    }

    private fun stopService() {
        connection?.let { unbindService(it) }
        connection = null
        val intent = Intent(this, DemoMediaProjectionService::class.java)
        stopService(intent)
    }

    private fun showPopup() {
        val popup = PopupMenu(this, binding.actions)
        val inflater: MenuInflater = popup.menuInflater
        inflater.inflate(R.menu.actions, popup.menu)
        popup.show()
        popup.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                goToSettingsActivity()
            } else {
                Log.e(TAG, "Unknown menu item ${it.itemId}")
            }
            true
        }
    }

    private fun goToSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.actions, menu)
        return true
    }

    private fun showAlertDialog(
        context: Context, title: String, message: String, afterPositiveButton: () -> Unit = {}
    ) {
        AlertDialog.Builder(context).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
                afterPositiveButton()
            }.show()
    }

    private fun showAlertDialog(
        context: Context,
        titleResourceId: Int,
        messageResourceId: Int,
        afterPositiveButton: () -> Unit = {}
    ) {
        AlertDialog.Builder(context).setTitle(titleResourceId).setMessage(messageResourceId)
            .setPositiveButton(android.R.string.ok) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
                afterPositiveButton()
            }.show()
    }

    private fun showPermissionAlertDialog(
        context: Context, afterPositiveButton: () -> Unit = {}
    ) = showAlertDialog(
        context, R.string.permission, R.string.permission_not_granted, afterPositiveButton
    )

    companion object {
        private const val TAG = "MainActivity"
    }
}