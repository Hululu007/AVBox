package com.github.tvbox.osc.player.controller

import com.github.tvbox.osc.bean.ParseBean

import org.json.JSONObject

import java.util.HashMap

interface VodControlListener {
    fun playNext(rmProgress: Boolean)

    fun playPre()

    fun prepared()

    fun changeParse(pb: ParseBean)

    fun updatePlayerCfg(
        suppressAutoSwitch: Boolean = false,
        suppressDecodeFallback: Boolean = false,
        mutate: (JSONObject) -> Unit,
    )

    fun replay(replay: Boolean)

    fun onNearEndProgress(positionMs: Long, durationMs: Long)

    fun selectSubtitle()

    fun closeSubtitles()

    fun selectAudioTrack()

    fun selectVideoTrack()

    fun showDanmuSetting()

    fun showEpisodes()

    fun toggleDanmu(): Boolean

    fun searchDanmuUi(longClick: Boolean)

    fun startPlayUrl(url: String, headers: HashMap<String, String>?)

    fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String)

    fun clickCast()
}
