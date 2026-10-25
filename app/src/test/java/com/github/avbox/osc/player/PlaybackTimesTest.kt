package com.github.avbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimesTest {

    @Test
    fun negativeAndZeroClampToZero() {
        assertEquals(0, PlaybackTimes.safeTimeMs(-1L))
        assertEquals(0, PlaybackTimes.safeTimeMs(0L))
    }

    @Test
    fun overflowClampsToIntMax() {
        assertEquals(Int.MAX_VALUE, PlaybackTimes.safeTimeMs(Int.MAX_VALUE.toLong() + 1))
        assertEquals(Int.MAX_VALUE, PlaybackTimes.safeTimeMs(Long.MAX_VALUE))
    }

    @Test
    fun normalValuePassesThrough() {
        assertEquals(1000, PlaybackTimes.safeTimeMs(1000L))
        assertEquals(Int.MAX_VALUE, PlaybackTimes.safeTimeMs(Int.MAX_VALUE.toLong()))
    }
}
