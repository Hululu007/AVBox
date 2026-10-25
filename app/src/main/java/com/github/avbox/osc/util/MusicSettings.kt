package com.github.avbox.osc.util

object MusicSettings {
    private const val PLAY_MODE = "music_play_mode"

    var playMode: String
        get() = KV.get(PLAY_MODE, "")
        set(value) {
            KV.put(PLAY_MODE, value)
        }
}
