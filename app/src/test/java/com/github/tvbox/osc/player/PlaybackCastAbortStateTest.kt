package com.github.tvbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCastAbortStateTest {

    @Test
    fun freshStateAcceptsPlayUrls() {
        assertFalse(PlaybackAttemptState().castAborted)
    }

    @Test
    fun abortCastSession_setsAbortAndClearsPrepare() {
        val st = PlaybackAttemptState(castPrepareOnly = true).abortCastSession()
        assertTrue(st.castAborted)
        assertFalse(st.castPrepareOnly)
    }

    @Test
    fun clearCastAbort_reportsTransitionOnce() {
        val first = PlaybackAttemptState().abortCastSession().clearCastAbort()
        assertTrue(first.value)
        assertFalse(first.state.castAborted)
        assertFalse(first.state.clearCastAbort().value)
    }

    @Test
    fun beginSessionClearsAbort() {
        assertFalse(PlaybackAttemptState().abortCastSession().beginSession().castAborted)
    }

    @Test
    fun abortSurvivesPlayLevelResets() {
        val st = PlaybackAttemptState().abortCastSession()
            .beginNewPlay()
            .onLineSwitched()
            .resetAutoRetryLadder()
            .userSelfRescue()
            .stoppedForSourceSwitch()
            .clearSessionFlags()
        assertTrue(st.castAborted)
    }

    @Test
    fun abortDoesNotTouchStartFlags() {
        val aborted = PlaybackAttemptState(playbackStarted = true).abortCastSession()
        assertTrue(aborted.playbackStarted)
        val cleared = aborted.clearCastAbort()
        assertTrue(cleared.value)
        assertTrue(cleared.state.playbackStarted)
    }
}
