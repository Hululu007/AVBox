package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Test

class IsoThroughputTest {

    private var now = 1_000_000L
    private val window = IsoThroughputWindow { now }

    @Test
    fun noReads_returnsZero() {
        assertEquals(0L, window.bytesPerSecond())
    }

    @Test
    fun spanBelowMinimum_returnsZero() {
        window.onRead(100_000)
        now += 1_000
        assertEquals(0L, window.bytesPerSecond())
    }

    @Test
    fun steadyReads_reportWindowRate() {
        repeat(8) {
            window.onRead(250_000)
            now += 500
        }
        assertEquals(500_000L, window.bytesPerSecond())
    }

    @Test
    fun staleReads_expire() {
        window.onRead(1_000_000)
        now += 3_000
        assertEquals(1_000_000L * 1_000 / 3_000, window.bytesPerSecond())
        now += 8_000
        assertEquals(0L, window.bytesPerSecond())
    }

    @Test
    fun reset_clearsWindow() {
        window.onRead(1_000_000)
        now += 3_000
        window.reset()
        now += 3_000
        assertEquals(0L, window.bytesPerSecond())
    }

    @Test
    fun continuousReads_surviveBucketWrap() {
        repeat(20) {
            window.onRead(100_000)
            now += 250
        }
        assertEquals(400_000L, window.bytesPerSecond())
    }

    @Test
    fun lastReadAge_tracksStall() {
        assertEquals(-1L, window.lastReadAgeMs())
        window.onRead(1_000)
        now += 1_000
        assertEquals(1_000L, window.lastReadAgeMs())
        window.onRead(1_000)
        assertEquals(0L, window.lastReadAgeMs())
    }

    @Test
    fun zeroLengthRead_isIgnored() {
        window.onRead(0)
        now += 3_000
        assertEquals(0L, window.bytesPerSecond())
    }
}
