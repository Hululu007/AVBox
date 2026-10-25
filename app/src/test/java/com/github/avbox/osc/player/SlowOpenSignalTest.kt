package com.github.avbox.osc.player

import com.github.avbox.osc.player.core.SlowOpenSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlowOpenSignalTest {

    @Before
    fun reset() {
        SlowOpenSignal.reset()
    }

    @Test
    fun dataFlowing_extendsOnce() {
        SlowOpenSignal.arm()
        SlowOpenSignal.note(124_067)
        assertTrue(SlowOpenSignal.shouldExtend(started = false, alreadyExtended = false))
    }

    @Test
    fun noBytesYet_doesNotExtend() {
        SlowOpenSignal.arm()
        assertFalse(SlowOpenSignal.shouldExtend(started = false, alreadyExtended = false))
    }

    @Test
    fun alreadyStarted_doesNotExtend() {
        SlowOpenSignal.arm()
        SlowOpenSignal.note(124_067)
        assertFalse(SlowOpenSignal.shouldExtend(started = true, alreadyExtended = false))
    }

    @Test
    fun alreadyExtended_doesNotExtendAgain() {
        SlowOpenSignal.arm()
        SlowOpenSignal.note(124_067)
        assertFalse(SlowOpenSignal.shouldExtend(started = false, alreadyExtended = true))
    }

    @Test
    fun disarmedSession_ignoresReads() {
        SlowOpenSignal.arm()
        SlowOpenSignal.disarm()
        SlowOpenSignal.note(124_067)
        assertFalse(SlowOpenSignal.shouldExtend(started = false, alreadyExtended = false))
    }
}
