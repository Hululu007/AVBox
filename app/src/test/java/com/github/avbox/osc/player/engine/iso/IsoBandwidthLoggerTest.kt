package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoBandwidthLoggerTest {

    private var now = 1_000_000L
    private val logger = IsoBandwidthLogger { now }

    @Test
    fun firstPositiveRate_logsImmediately() {
        val line = requireNotNull(logger.record(3_000_000L, 2_500_000L, 4_000_000L))
        assertEquals("echo-iso-bps:3000000:2500000:4000000", line)
    }

    @Test
    fun zeroRate_doesNotConsumeSlot() {
        assertNull(logger.record(0L, 0L, 4_000_000L))
        assertTrue(logger.due())
        assertEquals("echo-iso-bps:1:1:2", logger.record(1L, 1L, 2L))
    }

    @Test
    fun repeatedCalls_withinInterval_areSuppressed() {
        logger.record(1_000L, 900L, 2_000L)
        now += 9_999
        assertNull(logger.record(1_000L, 900L, 2_000L))
        assertFalse(logger.due())
        now += 1
        assertEquals("echo-iso-bps:1000:900:2000", logger.record(1_000L, 900L, 2_000L))
    }

    @Test
    fun stopsAfterMaxLogs() {
        repeat(60) {
            assertTrue(logger.record(1_000L, 900L, 2_000L) != null)
            now += 10_000
        }
        assertFalse(logger.due())
        assertNull(logger.record(1_000L, 900L, 2_000L))
    }

    @Test
    fun reset_allowsLoggingAgain() {
        repeat(60) {
            logger.record(1_000L, 900L, 2_000L)
            now += 10_000
        }
        logger.reset()
        assertTrue(logger.due())
        assertEquals("echo-iso-bps:1000:900:2000", logger.record(1_000L, 900L, 2_000L))
    }
}
