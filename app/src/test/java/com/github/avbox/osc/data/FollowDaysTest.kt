package com.github.avbox.osc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowDaysTest {

    @Test
    fun encode_sortsAndDropsInvalidDays() {
        assertEquals("1,3,5", FollowDays.encode(setOf(5, 1, 3)))
        assertEquals("0,6", FollowDays.encode(setOf(6, 0, 7, -1)))
        assertEquals("", FollowDays.encode(emptySet()))
    }

    @Test
    fun decode_toleratesBlankAndInvalidValues() {
        assertEquals(setOf(1, 3, 5), FollowDays.decode("1,3,5"))
        assertEquals(setOf(0), FollowDays.decode(" 0 "))
        assertEquals(setOf(6), FollowDays.decode("6,"))
        assertEquals(emptySet<Int>(), FollowDays.decode(""))
        assertEquals(emptySet<Int>(), FollowDays.decode(null))
        assertEquals(emptySet<Int>(), FollowDays.decode("a,8,-1"))
    }

    @Test
    fun roundTrip_keepsDays() {
        val days = setOf(0, 2, 6)
        assertEquals(days, FollowDays.decode(FollowDays.encode(days)))
    }

    @Test
    fun todayIndex_isMondayBased() {
        val index = FollowDays.todayIndex()
        assertTrue(index in 0..6)
        assertEquals(java.time.LocalDate.now().dayOfWeek.value - 1, index)
    }
}
