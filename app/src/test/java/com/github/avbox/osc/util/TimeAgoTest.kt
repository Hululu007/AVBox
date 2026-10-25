package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAgoTest {

    private val now = 1_700_000_000_000L

    private fun ago(deltaMs: Long) = timeAgoValueOf(now - deltaMs, now)

    @Test
    fun underOneMinute_isJustNow() {
        assertEquals(TimeAgoValue(TimeAgoUnit.JustNow, 0), ago(0L))
        assertEquals(TimeAgoValue(TimeAgoUnit.JustNow, 0), ago(59_999L))
    }

    @Test
    fun underOneHour_countsMinutes() {
        assertEquals(TimeAgoValue(TimeAgoUnit.Minutes, 1), ago(60_000L))
        assertEquals(TimeAgoValue(TimeAgoUnit.Minutes, 59), ago(59 * 60_000L + 59_000L))
    }

    @Test
    fun underOneDay_countsHours() {
        assertEquals(TimeAgoValue(TimeAgoUnit.Hours, 1), ago(3_600_000L))
        assertEquals(TimeAgoValue(TimeAgoUnit.Hours, 23), ago(23 * 3_600_000L + 3_599_000L))
    }

    @Test
    fun underOneMonth_countsDays() {
        assertEquals(TimeAgoValue(TimeAgoUnit.Days, 1), ago(86_400_000L))
        assertEquals(TimeAgoValue(TimeAgoUnit.Days, 29), ago(29 * 86_400_000L))
    }

    @Test
    fun fromOneMonth_countsMonths() {
        assertEquals(TimeAgoValue(TimeAgoUnit.Months, 1), ago(30 * 86_400_000L))
        assertEquals(TimeAgoValue(TimeAgoUnit.Months, 2), ago(75 * 86_400_000L))
    }

    @Test
    fun futureTimestamp_isJustNow() {
        assertEquals(TimeAgoValue(TimeAgoUnit.JustNow, 0), timeAgoValueOf(now + 5 * 3_600_000L, now))
    }
}
