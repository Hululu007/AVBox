package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.music.LyricLine
import com.github.tvbox.osc.ui.music.MusicPlayMode
import com.github.tvbox.osc.ui.music.MusicPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerStateTest {

    @Test
    fun songCompleted_orderAdvances() {
        val vm = MusicPlayerViewModel()
        vm.ui.updatePlayMode(MusicPlayMode.ORDER)
        val action = vm.songCompletedAction(2, 5)
        assertTrue(action is SongAction.Play)
        action as SongAction.Play
        assertEquals(3, action.index)
        assertTrue(action.removeProgress)
    }

    @Test
    fun songCompleted_orderAtEndStillRequestsNext() {
        val vm = MusicPlayerViewModel()
        vm.ui.updatePlayMode(MusicPlayMode.ORDER)
        val action = vm.songCompletedAction(4, 5)
        assertTrue(action is SongAction.Play)
        assertEquals(5, (action as SongAction.Play).index)
    }

    @Test
    fun songCompleted_listWrapsAround() {
        val vm = MusicPlayerViewModel()
        vm.ui.updatePlayMode(MusicPlayMode.LIST)
        val action = vm.songCompletedAction(4, 5)
        assertEquals(0, (action as SongAction.Play).index)
    }

    @Test
    fun songCompleted_listWithSingleSongReplays() {
        val vm = MusicPlayerViewModel()
        vm.ui.updatePlayMode(MusicPlayMode.LIST)
        assertEquals(SongAction.Replay, vm.songCompletedAction(0, 1))
    }

    @Test
    fun songCompleted_listWithEmptyQueueDoesNothing() {
        val vm = MusicPlayerViewModel()
        vm.ui.updatePlayMode(MusicPlayMode.LIST)
        assertEquals(SongAction.None, vm.songCompletedAction(0, 0))
    }

    @Test
    fun songCompleted_singleReplays() {
        val vm = MusicPlayerViewModel()
        vm.ui.updatePlayMode(MusicPlayMode.SINGLE)
        assertEquals(SongAction.Replay, vm.songCompletedAction(3, 9))
    }

    @Test
    fun playState_transitionsUpdatePlayingAndBuffering() {
        val state = MusicPlayerState()
        state.applyPlayState(PlayState.PREPARING, 0L)
        assertTrue(state.buffering)
        assertFalse(state.playing)

        state.applyPlayState(PlayState.PLAYING, 1000L)
        assertFalse(state.buffering)
        assertTrue(state.playing)
        assertEquals(1000L, state.durationMs)

        state.applyPlayState(PlayState.ERROR, 1000L)
        assertFalse(state.buffering)
        assertFalse(state.playing)
    }

    @Test
    fun applyMeta_seedsQueueAndWaveSeed() {
        val state = MusicPlayerState()
        state.applyMeta(
            title = "t",
            subtitle = "s",
            sourceName = "src",
            artwork = "art",
            queue = listOf("a", "b"),
            queueIndex = 1,
            seedKey = "vod#1",
        )
        assertEquals("t", state.title)
        assertEquals("s", state.subtitle)
        assertEquals("src", state.sourceName)
        assertEquals("art", state.artwork)
        assertEquals(listOf("a", "b"), state.queue)
        assertEquals(1, state.queueIndex)
        assertEquals("vod#1".hashCode(), state.waveSeed)
    }

    @Test
    fun lyric_loadsOncePerSourceAndAppliesOnlyForCurrentSource() {
        val state = MusicPlayerState()
        assertTrue(state.beginLyric("u1"))
        assertFalse(state.beginLyric("u1"))

        val lines = listOf(LyricLine(0L, "x"))
        state.applyLyric("u1", lines)
        assertEquals(lines, state.lyrics)

        state.applyLyric("stale", emptyList())
        assertEquals(lines, state.lyrics)

        assertTrue(state.beginLyric("u2"))
        assertEquals("u2", state.lyricSource)
        state.applyLyric("u1", emptyList())
        assertEquals(lines, state.lyrics)

        state.clearLyric()
        assertNull(state.lyricSource)
        assertTrue(state.lyrics.isEmpty())
    }

    @Test
    fun applyTick_updatesProgressSnapshot() {
        val state = MusicPlayerState()
        state.applyTick(positionMs = 500L, durationMs = 2000L, buffering = true, playing = false)
        assertEquals(500L, state.positionMs)
        assertEquals(2000L, state.durationMs)
        assertTrue(state.buffering)
        assertFalse(state.playing)
    }
}
