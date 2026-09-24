package com.github.tvbox.osc.player.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.doikki.videoplayer.player.VideoView

class PlayerUiStateVisibilityTest {

    private fun state(
        playState: Int = VideoView.STATE_PLAYING,
        controlsVisible: Boolean = true,
        locked: Boolean = false,
    ) = PlayerUiState().apply {
        this.playState = playState
        this.controlsVisible = controlsVisible
        this.locked = locked
    }

    @Test
    fun centerControls_hiddenWhileParseTipOnScreen() {
        val s = state(playState = VideoView.STATE_IDLE)
        s.applyTip("解析中", loading = true, err = false)
        assertFalse(s.centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhileErrorTipOnScreen() {
        val s = state(playState = VideoView.STATE_IDLE)
        s.applyTip("播放失败", loading = false, err = true)
        assertFalse(s.centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhilePreparing() {
        assertFalse(state(playState = VideoView.STATE_PREPARING).centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhileBuffering() {
        assertFalse(state(playState = VideoView.STATE_BUFFERING).centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhenLockedOrControlsNotSummoned() {
        assertFalse(state(locked = true).centerControlsVisible)
        assertFalse(state(controlsVisible = false).centerControlsVisible)
    }

    @Test
    fun centerControls_visibleDuringPlaybackAndInPreviewMode() {
        val playing = state(playState = VideoView.STATE_PLAYING)
        assertTrue(playing.centerControlsVisible)
        playing.previewMode = true
        assertTrue(playing.centerControlsVisible)

        assertTrue(state(playState = VideoView.STATE_PAUSED).centerControlsVisible)
    }

    @Test
    fun centerControls_visibleAgainAfterTipCleared() {
        val s = state(playState = VideoView.STATE_PLAYING)
        s.applyTip("解析中", loading = true, err = false)
        assertFalse(s.centerControlsVisible)
        s.applyTip("", loading = false, err = false)
        assertTrue(s.centerControlsVisible)
    }
}
