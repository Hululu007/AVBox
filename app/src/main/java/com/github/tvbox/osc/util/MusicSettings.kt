package com.github.tvbox.osc.util

object MusicSettings {
    private const val PLAY_MODE = "music_play_mode"

    fun playMode(): String = KV.get(PLAY_MODE, "")

    fun setPlayMode(mode: String) {
        KV.put(PLAY_MODE, mode)
    }
}
