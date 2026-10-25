package com.github.avbox.osc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressRulesTest {

    @Test
    fun decide_longVideo_needsThirtySeconds() {
        val twoHours = 2 * 60 * 60 * 1000L
        assertEquals(WatchDecision.SKIP, WatchProgressRules.decide(29_999, twoHours))
        assertEquals(WatchDecision.SAVE, WatchProgressRules.decide(30_000, twoHours))
    }

    @Test
    fun decide_shortVideo_fallsBackToPercent() {
        val short = 25_000L
        assertEquals(WatchDecision.SKIP, WatchProgressRules.decide(7_499, short))
        assertEquals(WatchDecision.SAVE, WatchProgressRules.decide(7_500, short))
    }

    @Test
    fun decide_finishedPercentStillSaves() {
        val hundredSeconds = 100_000L
        assertEquals(WatchDecision.SAVE, WatchProgressRules.decide(94_999, hundredSeconds))
        assertEquals(WatchDecision.SAVE, WatchProgressRules.decide(95_000, hundredSeconds))
        assertEquals(WatchDecision.SAVE, WatchProgressRules.decide(hundredSeconds, hundredSeconds))
    }

    @Test
    fun decide_unknownDuration_usesAbsoluteThreshold() {
        assertEquals(WatchDecision.SKIP, WatchProgressRules.decide(29_999, 0))
        assertEquals(WatchDecision.SAVE, WatchProgressRules.decide(30_000, 0))
    }

    @Test
    fun decide_noProgressOrNegative_skips() {
        assertEquals(WatchDecision.SKIP, WatchProgressRules.decide(0, 100_000))
        assertEquals(WatchDecision.SKIP, WatchProgressRules.decide(-1, 100_000))
    }

    @Test
    fun shouldRemember_treatsFinishedAsWatched() {
        val hundredSeconds = 100_000L
        assertFalse(WatchProgressRules.shouldRemember(1_000, hundredSeconds))
        assertTrue(WatchProgressRules.shouldRemember(50_000, hundredSeconds))
        assertTrue(WatchProgressRules.shouldRemember(99_000, hundredSeconds))
    }
}
