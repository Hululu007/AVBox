package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONException
import org.json.JSONObject

class LivePlayerManager {

    private var sessionDecode: String? = null

    fun init(videoView: MyVideoView) {
        getDefaultLiveChannelPlayer(videoView)
    }

    fun getDefaultLiveChannelPlayer(videoView: MyVideoView) {
        PlayerHelper.updateCfg(videoView, buildConfig())
    }

    private fun effectiveDecode(): String = sessionDecode ?: KV.get(HawkConfig.EXO_DECODE, "硬解码") // i18n: keep

    val livePlayerType: Int
        get() = if ("软解码" == effectiveDecode()) 1 else 0 // i18n: keep

    val livePlayerScale: Int
        get() = KV.get(HawkConfig.LIVE_PLAY_SCALE, 0)

    fun changeLivePlayerType(videoView: MyVideoView, playerType: Int) {
        sessionDecode = if (playerType == 1) "软解码" else "硬解码" // i18n: keep
        PlayerHelper.updateCfg(videoView, buildConfig())
    }

    fun changeLivePlayerScale(videoView: MyVideoView, playerScale: Int) {
        videoView.setScreenScaleType(playerScale)
        KV.put(HawkConfig.LIVE_PLAY_SCALE, playerScale)
    }

    private fun buildConfig(): JSONObject {
        val cfg = JSONObject()
        try {
            cfg.put("exo", effectiveDecode()) // i18n: keep
            cfg.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1))
            cfg.put("sc", KV.get(HawkConfig.LIVE_PLAY_SCALE, 0))
        } catch (e: JSONException) {
            LOG.e("LivePlayerManager", e)
        }
        return cfg
    }
}
