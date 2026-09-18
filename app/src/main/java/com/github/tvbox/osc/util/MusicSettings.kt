package com.github.tvbox.osc.util

/** 音乐播放页偏好(新键未登记 KVKeySpec:读必带默认值,与 search_result_layout 同法) */
object MusicSettings {
    private const val AUTO_OPEN_PAGE = "music_auto_open_page"
    private const val PLAY_MODE = "music_play_mode"

    fun autoOpenPage(): Boolean = KV.get(AUTO_OPEN_PAGE, true)

    fun setAutoOpenPage(enabled: Boolean) {
        KV.put(AUTO_OPEN_PAGE, enabled)
    }

    fun playMode(): String = KV.get(PLAY_MODE, "")

    fun setPlayMode(mode: String) {
        KV.put(PLAY_MODE, mode)
    }
}
