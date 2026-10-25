package com.github.avbox.osc.player

import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import org.json.JSONException
import org.json.JSONObject

class LivePlayerManager {

    private var sessionDecode: String? = null

    fun init(videoView: PlayerKernelView) {
        defaultLiveChannelPlayer(videoView)
    }

    fun defaultLiveChannelPlayer(videoView: PlayerKernelView) {
        KernelConfigApplier.applyConfig(videoView, buildConfig())
    }

    private fun effectiveDecode(): String = sessionDecode ?: KV.get(KVKeys.EXO_DECODE, "硬解码") // i18n: keep

    val livePlayerType: Int
        get() = if ("软解码" == effectiveDecode()) 1 else 0 // i18n: keep

    val livePlayerScale: Int
        get() = KV.get(KVKeys.LIVE_PLAY_SCALE, 0)

    fun changeLivePlayerType(videoView: PlayerKernelView, playerType: Int) {
        sessionDecode = if (playerType == 1) "软解码" else "硬解码" // i18n: keep
        KernelConfigApplier.applyConfig(videoView, buildConfig())
    }

    fun changeLivePlayerScale(videoView: PlayerKernelView, playerScale: Int) {
        videoView.setScreenScaleType(playerScale)
        KV.put(KVKeys.LIVE_PLAY_SCALE, playerScale)
    }

    private fun buildConfig(): JSONObject {
        val cfg = JSONObject()
        try {
            cfg.put("exo", effectiveDecode()) // i18n: keep
            cfg.put("pr", KV.get(KVKeys.PLAY_RENDER, 1))
            cfg.put("sc", KV.get(KVKeys.LIVE_PLAY_SCALE, 0))
        } catch (e: JSONException) {
            AppLog.e("LivePlayerManager", e)
        }
        return cfg
    }
}
