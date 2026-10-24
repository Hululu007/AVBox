package com.github.tvbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAttemptStateIntentTest {

    @Test
    fun reuseIntent_consumedOnce() {
        val first = PlaybackAttemptState().setReuseIntent(true).consumeReuseIntent()
        assertTrue(first.value)
        assertFalse(first.state.consumeReuseIntent().value)
    }

    @Test
    fun releaseIntent_outranksReuseIntent() {
        val st = PlaybackAttemptState().setReuseIntent(true).setReleaseIntent(true)
        assertFalse(st.consumeReuseIntent().value)
    }

    @Test
    fun reuseIntent_doesNotOverwriteReleaseIntent() {
        val st = PlaybackAttemptState().setReleaseIntent(true).setReuseIntent(true)
        assertFalse(st.consumeReuseIntent().value)
    }

    @Test
    fun lineSwitched_keepsRebuildIntent() {
        val st = PlaybackAttemptState().setReleaseIntent(true).onLineSwitched()
        assertFalse(st.consumeReuseIntent().value)
    }

    @Test
    fun lineSwitched_setsReuseWhenNoRebuildIntent() {
        assertTrue(PlaybackAttemptState().onLineSwitched().consumeReuseIntent().value)
    }

    @Test
    fun sourceSwitchClearsIntent() {
        val st = PlaybackAttemptState().setReuseIntent(true).stoppedForSourceSwitch()
        assertFalse(st.consumeReuseIntent().value)
    }
}
