package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoBandwidthGuardTest {

    @Test
    fun slowSource_reportsVerdict() {
        val verdict = isoBandwidthVerdict(1_000_000L, TARGET, DURATION_MS)
        assertNotNull(verdict)
        assertEquals(1_000_000L, requireNotNull(verdict).measuredBytesPerSecond)
        assertEquals(expectedRequired(), requireNotNull(verdict).requiredBytesPerSecond)
        assertTrue(requireNotNull(verdict).requiredBytesPerSecond > 3_000_000L)
    }

    @Test
    fun fastSource_noVerdict() {
        assertNull(isoBandwidthVerdict(expectedRequired() * 2, TARGET, DURATION_MS))
    }

    @Test
    fun exactlyAtThreshold_noVerdict() {
        assertNull(isoBandwidthVerdict(thresholdRate(), TARGET, DURATION_MS))
    }

    @Test
    fun justBelowThreshold_reportsVerdict() {
        assertNotNull(isoBandwidthVerdict(thresholdRate() - 1, TARGET, DURATION_MS))
    }

    @Test
    fun zeroRate_neverReportsBandwidthVerdict() {
        assertNull(isoBandwidthVerdict(0L, TARGET, DURATION_MS))
        assertNull(isoBandwidthVerdict(-1L, TARGET, DURATION_MS))
    }

    @Test
    fun missingInputs_noVerdict() {
        assertNull(isoBandwidthVerdict(0L, TARGET, DURATION_MS))
        assertNull(isoBandwidthVerdict(1_000_000L, 0L, DURATION_MS))
        assertNull(isoBandwidthVerdict(1_000_000L, TARGET, 0L))
    }

    @Test
    fun requiredRate_matchesGuardFormula() {
        assertEquals(expectedRequired(), isoRequiredBytesPerSecond(TARGET, DURATION_MS))
        assertEquals(0L, isoRequiredBytesPerSecond(0L, DURATION_MS))
        assertEquals(0L, isoRequiredBytesPerSecond(TARGET, 0L))
    }

    @Test
    fun retryStopsOnlyAfterIsoBandwidthStop() {
        assertTrue(stopsIsoBandwidthRetry(isoSession = true, switchTried = true))
        assertFalse(stopsIsoBandwidthRetry(isoSession = false, switchTried = true))
        assertFalse(stopsIsoBandwidthRetry(isoSession = true, switchTried = false))
        assertFalse(stopsIsoBandwidthRetry(isoSession = false, switchTried = false))
    }

    private fun expectedRequired(): Long = TARGET * TS_SIZE / PACKET_SIZE * MILLIS_PER_SECOND / DURATION_MS

    private fun thresholdRate(): Long = (expectedRequired() * THRESHOLD + PERCENT - 1) / PERCENT

    private companion object {
        const val TARGET = 6_917_855_232L
        const val DURATION_MS = 1_872_989L
        const val TS_SIZE = 188L
        const val PACKET_SIZE = 192L
        const val MILLIS_PER_SECOND = 1_000L
        const val THRESHOLD = 115L
        const val PERCENT = 100L
    }
}
