package com.github.tvbox.osc.player.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateVisibilityTest {

    private fun state(
        playState: PlayState = PlayState.PLAYING,
        controlsVisible: Boolean = true,
        locked: Boolean = false,
    ) = PlayerUiState().apply {
        this.updatePlayState(playState)
        this.updateControlsVisible(controlsVisible)
        this.updateLocked(locked)
    }

    @Test
    fun centerControls_hiddenWhileParseTipOnScreen() {
        val s = state(playState = PlayState.IDLE)
        s.applyTip("解析中", loading = true, err = false)
        assertFalse(s.centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhileErrorTipOnScreen() {
        val s = state(playState = PlayState.IDLE)
        s.applyTip("播放失败", loading = false, err = true)
        assertFalse(s.centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhilePreparing() {
        assertFalse(state(playState = PlayState.PREPARING).centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhileBuffering() {
        assertFalse(state(playState = PlayState.BUFFERING).centerControlsVisible)
    }

    @Test
    fun centerControls_hiddenWhenLockedOrControlsNotSummoned() {
        assertFalse(state(locked = true).centerControlsVisible)
        assertFalse(state(controlsVisible = false).centerControlsVisible)
    }

    @Test
    fun centerControls_visibleDuringPlaybackAndInPreviewMode() {
        val playing = state(playState = PlayState.PLAYING)
        assertTrue(playing.centerControlsVisible)
        playing.updatePreviewMode(true)
        assertTrue(playing.centerControlsVisible)

        assertTrue(state(playState = PlayState.PAUSED).centerControlsVisible)
    }

    @Test
    fun centerControls_visibleAgainAfterTipCleared() {
        val s = state(playState = PlayState.PLAYING)
        s.applyTip("解析中", loading = true, err = false)
        assertFalse(s.centerControlsVisible)
        s.applyTip("", loading = false, err = false)
        assertTrue(s.centerControlsVisible)
    }

    @Test
    fun pauseOverlay_visibleOnUserPause() {
        assertTrue(state(playState = PlayState.PAUSED, controlsVisible = false).pauseOverlayVisible)
    }

    @Test
    fun pauseOverlay_hiddenWhileLifecyclePaused() {
        val s = state(playState = PlayState.PAUSED, controlsVisible = false)
        s.updateLifecyclePaused(true)
        assertFalse(s.pauseOverlayVisible)
    }

    @Test
    fun pauseOverlay_hiddenWhenPausedByLeavingFullscreen() {
        val s = state(playState = PlayState.PAUSED, controlsVisible = false)
        s.updateExitPaused(true)
        assertFalse(s.pauseOverlayVisible)
    }
}
