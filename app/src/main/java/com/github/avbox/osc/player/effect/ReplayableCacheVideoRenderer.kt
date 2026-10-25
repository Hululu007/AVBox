package com.github.avbox.osc.player.effect

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import com.github.avbox.osc.player.core.DecodeKindRule
import com.github.avbox.osc.player.core.VideoCodecObserver
import com.github.avbox.osc.player.core.CodecPreferences
import com.github.avbox.osc.util.AppLog

class ReplayableCacheVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val lateThresholdToDropDecoderInputUs: Long,
    private val videoCodecObserver: VideoCodecObserver?,
) : MediaCodecVideoRenderer(builder) {

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper =
        PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .experimentalSetLateThresholdToDropInputUs(
                if (lateThresholdToDropDecoderInputUs != C.TIME_UNSET) {
                    -lateThresholdToDropDecoderInputUs
                } else {
                    C.TIME_UNSET
                },
            )
            .setClock(clock)
            .setEnableReplayableCache(true)
            .build()

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        val info = configuration.codecInfo
        val kind = DecodeKindRule.videoKindOf(name, info.softwareOnly)
        videoCodecObserver?.onCodecInitialized(name, kind)
        AppLog.i(
            "echo-exo-codec-init: name=$name kind=$kind hw=${info.hardwareAccelerated}" +
                " swOnly=${info.softwareOnly} preferSoft=${CodecPreferences.preferSoftwareDecode}",
        )
    }

    override fun onEnabled(joining: Boolean, mayRenderFirstFrame: Boolean) {
        super.onEnabled(joining, mayRenderFirstFrame)
        AppLog.i("echo-exo-renderer: enabled joining=$joining firstFrame=$mayRenderFirstFrame")
    }

    override fun onDisabled() {
        AppLog.i("echo-exo-renderer: disabled")
        super.onDisabled()
    }

    override fun onReset() {
        AppLog.i("echo-exo-renderer: reset")
        super.onReset()
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION && getSurface() == null) {
            AppLog.i("echo-exo-renderer: drop output-size (surface null)")
            return
        }
        super.handleMessage(messageType, message)
    }
}
