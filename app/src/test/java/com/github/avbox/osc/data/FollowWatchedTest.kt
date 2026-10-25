package com.github.avbox.osc.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowWatchedTest {

    @Test
    fun watchedDays_groupSameWeekMarksByOwner() {
        val map = mapOf(
            "a|1|0" to "100",
            "a|1|2" to "100",
            "b|2|3" to "101",
            "c|3|5" to "100",
        )
        assertEquals(
            mapOf("a|1" to setOf(0, 2), "c|3" to setOf(5)),
            FollowWatched.watchedDays(map, 100L),
        )
        assertEquals(mapOf("b|2" to setOf(3)), FollowWatched.watchedDays(map, 101L))
        assertEquals(emptyMap<String, Set<Int>>(), FollowWatched.watchedDays(map, 102L))
    }

    @Test
    fun watchedDays_keepMarksForTheWholeWeek() {
        val map = mapOf("a|1|2" to "100")
        assertEquals(
            "周内每一天读出的都是已看",
            mapOf("a|1" to setOf(2)),
            FollowWatched.watchedDays(map, 100L),
        )
        assertEquals(
            "跨周后同一个格子不再算已看",
            emptyMap<String, Set<Int>>(),
            FollowWatched.watchedDays(map, 107L),
        )
    }

    @Test
    fun watchedDays_ignoreKeysWithoutDay() {
        val map = mapOf("broken" to "100", "a|1|x" to "100")
        assertEquals(emptyMap<String, Set<Int>>(), FollowWatched.watchedDays(map, 100L))
    }

    @Test
    fun prune_dropsOtherWeeksAndReportsChange() {
        val map = mutableMapOf("a|1|0" to "100", "b|2|3" to "99", "c|3|5" to "100")
        assertTrue(FollowWatched.prune(map, 100L))
        assertEquals(mapOf("a|1|0" to "100", "c|3|5" to "100"), map)
        assertFalse(FollowWatched.prune(map, 100L))
    }

    @Test
    fun weekStart_isTheMondayOfTheCurrentWeek() {
        val today = LocalDate.now()
        val start = LocalDate.ofEpochDay(FollowWatched.weekStart())
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals((today.dayOfWeek.value - 1).toLong(), ChronoUnit.DAYS.between(start, today))
        assertTrue(start <= today)
    }

    @Test
    fun ownerOf_combinesSourceAndVodId() {
        assertEquals("k|v", FollowWatched.ownerOf("k", "v"))
        assertEquals("|", FollowWatched.ownerOf(null, null))
    }
}
