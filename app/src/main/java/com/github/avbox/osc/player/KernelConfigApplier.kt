package com.github.avbox.osc.player

import com.github.avbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.avbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.avbox.osc.player.host.PlayerRenderViewFactory
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

import org.json.JSONObject

object KernelConfigApplier {

    @JvmStatic
    fun applyConfig(videoView: PlayerKernelView?, playerCfg: JSONObject) {
        var renderType = KV.get(KVKeys.PLAY_RENDER, 1)
        var exoDecode = KV.get(KVKeys.EXO_DECODE, "硬解码") // i18n: keep
        var scale = KV.get(KVKeys.PLAY_SCALE, 0)
        renderType = playerCfg.optInt("pr", renderType)
        scale = playerCfg.optInt("sc", scale)
        exoDecode = playerCfg.optString("exo", exoDecode)
        val exoDecodeChanged = applyExoDecode(exoDecode)
        val renderViewFactory: PlayerRenderViewFactory = when (renderType) {
            1 -> EngineSurfaceRenderViewFactory.create()
            else -> EngineTextureRenderViewFactory.create()
        }
        if (videoView != null) {
            if (exoDecodeChanged && videoView.mediaPlayer is ExoPlayer) {
                videoView.requireKernelRebuild()
                AppLog.i("echo-exo-decode-changed: rebuild kernel on next start")
            }
            videoView.setRenderViewFactory(renderViewFactory)
            videoView.setScreenScaleType(scale)
        }
    }

    private fun applyExoDecode(exoDecode: String): Boolean {
        val prefer = "软解码" == exoDecode // i18n: keep
        if (ExoPlayer.isPreferSoftwareDecode() == prefer) return false
        ExoPlayer.setPreferSoftwareDecode(prefer)
        return true
    }
}
