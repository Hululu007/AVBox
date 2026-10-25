package com.github.avbox.osc.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.HistoryWriter
import com.github.avbox.osc.data.WatchProgressStore
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.ui.music.LyricLine
import com.github.avbox.osc.ui.music.MusicPlayMode
import com.github.avbox.osc.ui.music.MusicPlayerState
import com.github.avbox.osc.ui.player.PlayerTipState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MusicSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface SongAction {
    object None : SongAction
    object Replay : SongAction
    class Play(val index: Int, val removeProgress: Boolean) : SongAction
}

internal class MusicPlayerViewModel : ViewModel() {

    val ui = MusicPlayerState()

    fun initPlayMode() {
        ui.updatePlayMode(MusicPlayMode.of(MusicSettings.playMode))
    }

    fun cyclePlayMode() {
        val mode = ui.playMode.toggled()
        ui.updatePlayMode(mode)
        MusicSettings.playMode = mode.name
    }

    fun songCompletedAction(playIndex: Int, queueSize: Int): SongAction = when (ui.playMode) {
        MusicPlayMode.SINGLE -> SongAction.Replay
        MusicPlayMode.LIST -> {
            if (queueSize <= 0) {
                SongAction.None
            } else {
                val next = (playIndex + 1) % queueSize
                if (next == playIndex) SongAction.Replay else SongAction.Play(next, true)
            }
        }
        MusicPlayMode.ORDER -> SongAction.Play(playIndex + 1, true)
    }

    fun applyTick(positionMs: Long, durationMs: Long, buffering: Boolean, playing: Boolean) {
        ui.applyTick(positionMs, durationMs, buffering, playing)
    }

    fun applyPlayState(playState: PlayState, durationMs: Long) {
        ui.applyPlayState(playState, durationMs)
    }

    fun setPlaying(playing: Boolean) {
        ui.updatePlaying(playing)
    }

    fun setPosition(positionMs: Long) {
        ui.updatePosition(positionMs)
    }

    fun setQueueIndex(index: Int) {
        ui.updateQueueIndex(index)
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
        ui.applyMeta(title, subtitle, sourceName, artwork, queue, queueIndex, seedKey)
    }

    fun beginLyric(source: String): Boolean = ui.beginLyric(source)

    fun applyLyric(source: String, lines: List<LyricLine>) {
        ui.applyLyric(source, lines)
    }

    fun clearLyric() {
        ui.clearLyric()
    }

    fun setTip(tip: PlayerTipState) {
        ui.applyTip(tip)
    }

    fun setTip(msg: String, loading: Boolean, error: Boolean) {
        ui.applyTip(msg, loading, error)
    }

    fun clearTip() {
        ui.clearTip()
    }

    fun setArtwork(url: String?) {
        ui.updateArtwork(url)
    }

    fun refreshCollected(sourceKey: String?, vodId: String?) {
        viewModelScope.launch {
            ui.updateCollected(
                try {
                    isCollected(sourceKey, vodId)
                } catch (th: Throwable) {
                    AppLog.e("MusicPlayerViewModel", "collect state read failed: " + th, th)
                    false
                },
            )
        }
    }

    suspend fun isCollected(sourceKey: String?, vodId: String?): Boolean = withContext(Dispatchers.IO) {
        AppGraph.collectRepository.isVodCollect(sourceKey, vodId)
    }

    fun toggleCollect(sourceKey: String?, info: VodInfo) {
        val collected = ui.collected
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (collected) {
                    AppGraph.collectRepository.deleteVodCollect(sourceKey, info)
                } else {
                    AppGraph.collectRepository.insertVodCollect(sourceKey, info)
                }
            } catch (th: Throwable) {
                AppLog.e("MusicPlayerViewModel", "collect toggle failed: " + th, th)
            }
        }
        ui.updateCollected(!collected)
    }

    fun clearWatchProgress(owner: String?, key: String?) {
        WatchProgressStore.clear(owner, key, discard = true)
    }

    fun syncHistory(sourceKey: String, info: VodInfo) {
        EpisodeTotals.putFromVod(info)
        HistoryWriter.write(sourceKey, info)
    }
}
