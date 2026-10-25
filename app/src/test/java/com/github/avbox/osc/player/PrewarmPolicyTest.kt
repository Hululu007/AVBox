package com.github.avbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrewarmPolicyTest {

    @Test
    fun idleRelease_suppressedWhenPrewarmEnabled() {
        assertEquals(PrewarmPolicy.NO_IDLE_RELEASE, PrewarmPolicy.idleReleaseDelayMs(true, 60_000L))
    }

    @Test
    fun idleRelease_keepsBaseDelayWhenPrewarmDisabled() {
        assertEquals(60_000L, PrewarmPolicy.idleReleaseDelayMs(false, 60_000L))
    }

    @Test
    fun disableSchedule_onlyWhenEngineNotInUse() {
        assertTrue(PrewarmPolicy.shouldScheduleOnDisable(false))
        assertFalse(PrewarmPolicy.shouldScheduleOnDisable(true))
    }
}
