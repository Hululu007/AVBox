package com.github.tvbox.osc.ui.music

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.player.PlayerTipState

enum class MusicPlayMode(@StringRes val labelRes: Int) {
    SINGLE(R.string.music_mode_single),
    LIST(R.string.music_mode_list),
    ORDER(R.string.music_mode_order);

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

    var tip by mutableStateOf(PlayerTipState())
        private set
    var title by mutableStateOf("")
        private set
    var subtitle by mutableStateOf("")
        private set
    var sourceName by mutableStateOf("")
        private set
    var artwork by mutableStateOf("")
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    var lyricSource by mutableStateOf<String?>(null)
        private set
    var queue by mutableStateOf<List<String>>(emptyList())
        private set
    var queueIndex by mutableStateOf(0)
        private set
    var waveSeed by mutableStateOf(0)
        private set
    var playMode by mutableStateOf(MusicPlayMode.ORDER)
        private set
    var collected by mutableStateOf(false)
        private set
    var castSheet by mutableStateOf<CastSheetState?>(null)
        private set

    fun applyTick(positionMs: Long, durationMs: Long, buffering: Boolean, playing: Boolean) {
        this.positionMs = positionMs
        this.durationMs = durationMs
        this.buffering = buffering
        this.playing = playing
    }

    fun applyPlayState(playState: PlayState, durationMs: Long) {
        when (playState) {
            PlayState.PREPARING, PlayState.BUFFERING -> buffering = true
            PlayState.PREPARED, PlayState.BUFFERED -> buffering = false
            PlayState.PLAYING -> {
                buffering = false
                playing = true
            }
            PlayState.PAUSED, PlayState.COMPLETED, PlayState.ERROR -> {
                buffering = false
                playing = false
            }
            PlayState.IDLE, PlayState.START_ABORT -> {}
        }
        this.durationMs = durationMs
    }

    fun updatePlaying(playing: Boolean) {
        this.playing = playing
    }

    fun updatePosition(positionMs: Long) {
        this.positionMs = positionMs
    }

    fun updateQueueIndex(index: Int) {
        queueIndex = index
    }

    fun applyMeta(
        title: String,
        subtitle: String,
        sourceName: String,
        artwork: String,
        queue: List<String>,
        queueIndex: Int,
        seedKey: String,
    ) {
        this.title = title
        this.subtitle = subtitle
        this.sourceName = sourceName
        this.artwork = artwork
        this.queue = queue
        this.queueIndex = queueIndex
        this.waveSeed = seedKey.hashCode()
    }

    fun beginLyric(source: String): Boolean {
        if (source == lyricSource) return false
        lyricSource = source
        return true
    }

    fun applyLyric(source: String, lines: List<LyricLine>) {
        if (source == lyricSource) lyrics = lines
    }

    fun clearLyric() {
        lyricSource = null
        lyrics = emptyList()
    }

    fun applyTip(tip: PlayerTipState) {
        this.tip = tip
    }

    fun applyTip(msg: String, loading: Boolean, error: Boolean) {
        tip = PlayerTipState(msg, loading, error)
    }

    fun clearTip() {
        tip = PlayerTipState()
    }

    fun updateArtwork(url: String?) {
        artwork = url.orEmpty()
    }

    fun updatePlayMode(mode: MusicPlayMode) {
        playMode = mode
    }

    fun updateCollected(collected: Boolean) {
        this.collected = collected
    }

    fun openCastSheet(sheet: CastSheetState) {
        castSheet = sheet
    }

    fun dismissCastSheet() {
        castSheet = null
    }
}
