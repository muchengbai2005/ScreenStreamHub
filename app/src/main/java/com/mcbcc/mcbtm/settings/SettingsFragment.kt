package com.mcbcc.mcbtm.settings

import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Size
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.TSMuxerInfo
import io.github.thibaultbee.streampack.core.streamers.infos.StreamerConfigurationInfo
import io.github.thibaultbee.streampack.ext.flv.elements.endpoints.composites.muxer.FlvMuxerInfo
import com.mcbcc.mcbtm.R
import com.mcbcc.mcbtm.models.EndpointFactory
import com.mcbcc.mcbtm.models.EndpointType
import com.mcbcc.mcbtm.utils.LocaleHelper

class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var streamerInfo: StreamerConfigurationInfo

    private val videoEncoderListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.video_encoder_key))!!
    }

    private val videoResolutionListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.video_resolution_key))!!
    }

    private val videoBitrateSeekBar: SeekBarPreference by lazy {
        this.findPreference(getString(R.string.video_bitrate_key))!!
    }

    private val audioSettingsCategory: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.audio_settings_key))!!
    }

    private val audioEncoderListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.audio_encoder_key))!!
    }

    private val audioNumberOfChannelListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.audio_number_of_channels_key))!!
    }

    private val audioBitrateListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.audio_bitrate_key))!!
    }

    private val audioSampleRateListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.audio_sample_rate_key))!!
    }

    private val audioByteFormatListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.audio_byte_format_key))!!
    }

    private val endpointTypePreference: ListPreference by lazy {
        this.findPreference(getString(R.string.endpoint_type_key))!!
    }

    private val tsMuxerPreference: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.ts_muxer_key))!!
    }

    private val rtmpEndpointPreference: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.rtmp_server_key))!!
    }

    private val srtEndpointPreference: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.srt_server_key))!!
    }

    private val websocketEndpointPreference: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.websocket_server_key))!!
    }

    private val serverIpPreference: EditTextPreference by lazy {
        this.findPreference(getString(R.string.server_ip_key))!!
    }

    private val serverPortPreference: EditTextPreference by lazy {
        this.findPreference(getString(R.string.server_port_key))!!
    }

    private val serverEnableBitrateRegulationPreference: SwitchPreference by lazy {
        this.findPreference(getString(R.string.server_enable_bitrate_regulation_key))!!
    }

    private val serverTargetVideoBitratePreference: SeekBarPreference by lazy {
        this.findPreference(getString(R.string.server_video_target_bitrate_key))!!
    }

    private val serverMinVideoBitratePreference: SeekBarPreference by lazy {
        this.findPreference(getString(R.string.server_video_min_bitrate_key))!!
    }

    private val languagePreference: ListPreference by lazy {
        this.findPreference(getString(R.string.language_key))!!
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
    }

    private fun setEndpointType(id: Int) {
        val endpointType = EndpointType.fromId(id)
        val endpoint = EndpointFactory(
            endpointType
        ).build()
        srtEndpointPreference.isVisible = endpoint.hasSrtCapabilities
        rtmpEndpointPreference.isVisible = endpoint.hasRtmpCapabilities
        websocketEndpointPreference.isVisible = endpoint.hasWebSocketCapabilities
        tsMuxerPreference.isVisible = endpoint.hasTSCapabilities

        streamerInfo = when (endpointType) {
            EndpointType.SRT -> StreamerConfigurationInfo(CompositeEndpoint.EndpointInfo(TSMuxerInfo))
            EndpointType.RTMP -> StreamerConfigurationInfo(
                CompositeEndpoint.EndpointInfo(
                    FlvMuxerInfo
                )
            )
            EndpointType.WEBSOCKET -> StreamerConfigurationInfo(
                CompositeEndpoint.EndpointInfo(
                    FlvMuxerInfo
                )
            )
        }
        loadVideoSettings()
        loadAudioSettings()
    }

    private fun loadVideoSettings() {
        val supportedVideoEncoderName =
            mapOf(
                MediaFormat.MIMETYPE_VIDEO_AVC to getString(R.string.video_encoder_h264),
                MediaFormat.MIMETYPE_VIDEO_HEVC to getString(R.string.video_encoder_h265),
                MediaFormat.MIMETYPE_VIDEO_H263 to getString(R.string.video_encoder_h263)
            )

        val supportedVideoEncoder = streamerInfo.video.supportedEncoders
        videoEncoderListPreference.setDefaultValue(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoderListPreference.entryValues = supportedVideoEncoder.toTypedArray()
        videoEncoderListPreference.entries =
            supportedVideoEncoder.map { supportedVideoEncoderName[it] }.toTypedArray()

        loadVideoSettings(videoEncoderListPreference.value ?: MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    private fun loadVideoSettings(encoder: String) {
        val encoderSupportedResolution = streamerInfo.video.getSupportedResolutions(encoder)
        listOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(640, 360),
            Size(640, 480)
        ).filter {
            encoderSupportedResolution.first.contains(it.width) && encoderSupportedResolution.second.contains(
                it.height
            )
        }.map { it.toString() }.toTypedArray().run {
            videoResolutionListPreference.entries = this
            videoResolutionListPreference.entryValues = this
        }

        streamerInfo.video.getSupportedBitrates(encoder).run {
            videoBitrateSeekBar.max = minOf(videoBitrateSeekBar.max, upper / 1000)
        }
    }

    private fun loadAudioSettings() {
        val supportedAudioEncoderName =
            mapOf(
                MediaFormat.MIMETYPE_AUDIO_AAC to getString(R.string.audio_encoder_aac),
                MediaFormat.MIMETYPE_AUDIO_OPUS to getString(R.string.audio_encoder_opus)
            )

        val supportedAudioEncoder = streamerInfo.audio.supportedEncoders
        audioEncoderListPreference.setDefaultValue(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoderListPreference.entryValues = supportedAudioEncoder.toTypedArray()
        audioEncoderListPreference.entries =
            supportedAudioEncoder.map { supportedAudioEncoderName[it] }.toTypedArray()
        if (audioEncoderListPreference.entry == null) {
            audioEncoderListPreference.value = MediaFormat.MIMETYPE_AUDIO_AAC
        }
        audioEncoderListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadAudioSettings(newValue as String)
            true
        }

        loadAudioSettings(audioEncoderListPreference.value ?: MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    private fun loadAudioSettings(encoder: String) {
        val inputChannelRange =
            streamerInfo.audio.getSupportedInputChannelRange(encoder)
        audioNumberOfChannelListPreference.entryValues.filter {
            inputChannelRange.contains(it.toString().toInt())
        }.toTypedArray().run {
            audioNumberOfChannelListPreference.entries = this
            audioNumberOfChannelListPreference.entryValues = this
        }

        val bitrateRange = streamerInfo.audio.getSupportedBitrates(encoder)
        audioBitrateListPreference.entryValues.filter {
            bitrateRange.contains(
                it.toString().toInt()
            )
        }.toTypedArray().run {
            audioBitrateListPreference.entries =
                this.map { "${it.toString().toInt() / 1000} Kbps" }.toTypedArray()
            audioBitrateListPreference.entryValues = this
        }

        val sampleRates = streamerInfo.audio.getSupportedSampleRates(encoder)
        audioSampleRateListPreference.entries =
            sampleRates.map { "${"%.1f".format(it.toString().toFloat() / 1000)} kHz" }
                .toTypedArray()
        audioSampleRateListPreference.entryValues = sampleRates.map { "$it" }.toTypedArray()
        if (audioSampleRateListPreference.entry == null) {
            audioSampleRateListPreference.value = when {
                sampleRates.contains(44100) -> "44100"
                sampleRates.contains(48000) -> "48000"
                else -> "${sampleRates.first()}"
            }
        }

        val supportedByteFormatName =
            mapOf(
                AudioFormat.ENCODING_PCM_8BIT to getString(R.string.audio_byte_format_8bit),
                AudioFormat.ENCODING_PCM_16BIT to getString(R.string.audio_byte_format_16bit),
                AudioFormat.ENCODING_PCM_FLOAT to getString(R.string.audio_byte_format_float)
            )
        val byteFormats = streamerInfo.audio.getSupportedByteFormats()
        audioByteFormatListPreference.entries =
            byteFormats.map { supportedByteFormatName[it] }.toTypedArray()
        audioByteFormatListPreference.entryValues = byteFormats.map { "$it" }.toTypedArray()
        if (audioByteFormatListPreference.entry == null) {
            audioByteFormatListPreference.value = "${AudioFormat.ENCODING_PCM_16BIT}"
        }
    }

    private fun loadEndpoint() {
        val supportedEndpointName =
            mapOf(
                EndpointType.SRT to getString(R.string.to_srt),
                EndpointType.RTMP to getString(R.string.to_rtmp),
                EndpointType.WEBSOCKET to getString(R.string.to_websocket),
            )
        val supportedEndpoint = EndpointType.entries.map { "${it.id}" }.toTypedArray()
        endpointTypePreference.setDefaultValue(EndpointType.SRT.id)
        endpointTypePreference.entryValues = supportedEndpoint
        endpointTypePreference.entries =
            supportedEndpoint.map { supportedEndpointName[EndpointType.fromId(it.toInt())] }
                .toTypedArray()
        setEndpointType(endpointTypePreference.value?.toInt() ?: EndpointType.SRT.id)
        endpointTypePreference.setOnPreferenceChangeListener { _, newValue ->
            setEndpointType((newValue as String).toInt())
            true
        }

        serverIpPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        serverPortPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.filters = arrayOf(InputFilter.LengthFilter(5))
        }

        serverTargetVideoBitratePreference.isVisible =
            serverEnableBitrateRegulationPreference.isChecked
        serverMinVideoBitratePreference.isVisible =
            serverEnableBitrateRegulationPreference.isChecked
        serverEnableBitrateRegulationPreference.setOnPreferenceChangeListener { _, newValue ->
            serverTargetVideoBitratePreference.isVisible = newValue as Boolean
            serverMinVideoBitratePreference.isVisible = newValue
            true
        }

        serverTargetVideoBitratePreference.setOnPreferenceChangeListener { _, newValue ->
            if ((newValue as Int) < serverMinVideoBitratePreference.value) {
                serverMinVideoBitratePreference.value = newValue
            }
            true
        }

        serverMinVideoBitratePreference.setOnPreferenceChangeListener { _, newValue ->
            if ((newValue as Int) > serverTargetVideoBitratePreference.value) {
                serverTargetVideoBitratePreference.value = newValue
            }
            true
        }

        loadWebSocketSettings()
    }

    private fun loadWebSocketSettings() {
        val enableFpsLimitPreference: SwitchPreference =
            findPreference(getString(R.string.websocket_enable_fps_limit_key))!!
        val targetFpsPreference: SeekBarPreference =
            findPreference(getString(R.string.websocket_target_fps_key))!!
        val autoReconnectPreference: SwitchPreference =
            findPreference(getString(R.string.websocket_auto_reconnect_key))!!
        val reconnectMaxAttemptsPreference: SeekBarPreference =
            findPreference(getString(R.string.websocket_reconnect_max_attempts_key))!!
        val batchSendPreference: SwitchPreference =
            findPreference(getString(R.string.websocket_batch_send_key))!!
        val batchIntervalPreference: SeekBarPreference =
            findPreference(getString(R.string.websocket_batch_interval_key))!!
        val keyframeOnlyPreference: SwitchPreference =
            findPreference(getString(R.string.websocket_keyframe_only_key))!!

        targetFpsPreference.isVisible = enableFpsLimitPreference.isChecked
        enableFpsLimitPreference.setOnPreferenceChangeListener { _, newValue ->
            targetFpsPreference.isVisible = newValue as Boolean
            true
        }

        reconnectMaxAttemptsPreference.isVisible = autoReconnectPreference.isChecked
        autoReconnectPreference.setOnPreferenceChangeListener { _, newValue ->
            reconnectMaxAttemptsPreference.isVisible = newValue as Boolean
            true
        }

        batchIntervalPreference.isVisible = batchSendPreference.isChecked
        batchSendPreference.setOnPreferenceChangeListener { _, newValue ->
            batchIntervalPreference.isVisible = newValue as Boolean
            true
        }

        val frameDropStrategyPreference: ListPreference =
            findPreference(getString(R.string.websocket_frame_drop_strategy_key))!!
        val queueOverflowStrategyPreference: ListPreference =
            findPreference(getString(R.string.websocket_queue_overflow_strategy_key))!!

        frameDropStrategyPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        queueOverflowStrategyPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun loadPreferences() {
        loadEndpoint()
        loadLanguage()
    }

    private fun loadLanguage() {
        languagePreference.setOnPreferenceChangeListener { _, newValue ->
            val language = newValue as String
            LocaleHelper.setLocale(requireContext(), language)
            requireActivity().recreate()
            true
        }
    }
}