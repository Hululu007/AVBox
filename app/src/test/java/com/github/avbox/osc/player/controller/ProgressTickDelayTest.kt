package com.github.avbox.osc.player.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTickDelayTest {

    @Test
    fun alignsToNextSecond() {
        assertEquals(1000L, ProgressTickDelay.next(0, 1f))
        assertEquals(1000L, ProgressTickDelay.next(1000, 1f))
        assertEquals(1000L, ProgressTickDelay.next(2000, 1f))
        assertEquals(500L, ProgressTickDelay.next(500, 1f))
        assertEquals(334L, ProgressTickDelay.next(666, 1f))
    }

    @Test
    fun neverSpinsWhenPositionStalls() {
        assertEquals(ProgressTickDelay.MIN_MS, ProgressTickDelay.next(998, 1f))
        assertEquals(ProgressTickDelay.MIN_MS, ProgressTickDelay.next(998, 2f))
        assertEquals(ProgressTickDelay.MIN_MS, ProgressTickDelay.next(998, 3f))
        assertEquals(ProgressTickDelay.MIN_MS, ProgressTickDelay.next(999, 1f))
    }

    @Test
    fun scalesWithPlaybackSpeed() {
        assertEquals(250L, ProgressTickDelay.next(500, 2f))
        assertEquals(333L, ProgressTickDelay.next(500, 1.5f))
        assertEquals(2000L, ProgressTickDelay.next(0, 0.5f))
    }

    @Test
    fun treatsUnsetSpeedAsNormal() {
        assertEquals(1000L, ProgressTickDelay.next(0, 0f))
        assertEquals(1000L, ProgressTickDelay.next(0, -1f))
    }
}
