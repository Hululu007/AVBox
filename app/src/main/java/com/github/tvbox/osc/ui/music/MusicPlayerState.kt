package com.github.tvbox.osc.ui.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.player.state.CastSheetState

enum class MusicPlayMode(val label: String) {
    SINGLE("单曲循环"),
    LIST("列表循环"),
    ORDER("顺序播放");

    fun toggled(): MusicPlayMode = when (this) {
        SINGLE -> LIST
        LIST -> ORDER
        ORDER -> SINGLE
    }

    companion object {
        fun of(name: String?): MusicPlayMode =
            entries.firstOrNull { it.name == name } ?: ORDER
    }
}

class MusicPlayerState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var sourceName by mutableStateOf("")
    var artwork by mutableStateOf("")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    var queue by mutableStateOf<List<String>>(emptyList())
    var queueIndex by mutableStateOf(0)
    var waveSeed by mutableStateOf(0)
    var playMode by mutableStateOf(MusicPlayMode.ORDER)
    var collected by mutableStateOf(false)
    var castSheet by mutableStateOf<CastSheetState?>(null)
}
