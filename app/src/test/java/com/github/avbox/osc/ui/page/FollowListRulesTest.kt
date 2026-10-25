package com.github.avbox.osc.ui.page

import com.github.avbox.osc.data.VodFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowListRulesTest {

    @Test
    fun dayCounts_skipUnscheduledShows() {
        val items = listOf(
            entry(recordId = 1, days = "0,2"),
            entry(recordId = 2, days = "2"),
            entry(recordId = 3, days = ""),
        )
        assertEquals(mapOf(0 to 1, 2 to 2), FollowListRules.dayCounts(items))
    }

    @Test
    fun dayCounts_dropOnlyWatchedDays() {
        val singleDay = listOf(
            entry(recordId = 1, days = "0,2", watchedDays = setOf(2)),
            entry(recordId = 2, days = "2"),
        )
        assertEquals(mapOf(0 to 1, 2 to 1), FollowListRules.dayCounts(singleDay))

        val wholeShow = listOf(
            entry(recordId = 1, days = "0,2", watchedDays = setOf(0, 2)),
            entry(recordId = 2, days = "2"),
        )
        assertEquals(mapOf(2 to 1), FollowListRules.dayCounts(wholeShow))
    }

    @Test
    fun isWatchedFollowsSelectedView() {
        val partiallyWatched = entry(recordId = 1, days = "0,2", watchedDays = setOf(2))
        assertTrue(FollowListRules.isWatched(partiallyWatched, 2))
        assertFalse(FollowListRules.isWatched(partiallyWatched, 0))
        assertFalse(FollowListRules.isWatched(partiallyWatched, 5))
        assertFalse(FollowListRules.isWatched(partiallyWatched, null))

        val fullyWatched = partiallyWatched.copy(watchedDays = setOf(0, 2))
        assertTrue(FollowListRules.isWatched(fullyWatched, null))
        assertTrue(FollowListRules.isWatched(fullyWatched, 0))
        assertTrue(FollowListRules.isWatched(fullyWatched, 2))

        val unscheduled = entry(recordId = 2, days = "", watchedDays = emptySet())
        assertFalse(FollowListRules.isWatched(unscheduled, null))
        assertFalse(FollowListRules.isWatched(unscheduled, 3))
    }

    @Test
    fun weekView_keepsUnscheduledAndSortsByAddedTime() {
        val items = listOf(
            entry(recordId = 1, days = "0", addedTime = 100L),
            entry(recordId = 2, days = "", addedTime = 300L),
            entry(recordId = 3, days = "3", hour = 8, addedTime = 200L),
        )
        assertEquals(listOf(2, 3, 1), FollowListRules.visible(items, null).map { it.follow.id })
    }

    @Test
    fun dayView_filtersByDayAndSortsByHour() {
        val items = listOf(
            entry(recordId = 1, days = "2", addedTime = 100L),
            entry(recordId = 2, days = "2", hour = 9, addedTime = 300L),
            entry(recordId = 3, days = "2", hour = 9, addedTime = 500L),
            entry(recordId = 4, days = "5", hour = 8, addedTime = 400L),
        )
        assertEquals(listOf(3, 2, 1), FollowListRules.visible(items, 2).map { it.follow.id })
        assertEquals(listOf(4), FollowListRules.visible(items, 5).map { it.follow.id })
    }

    @Test
    fun unscheduledShows_neverMatchASingleDay() {
        val items = listOf(entry(recordId = 1, days = "", addedTime = 100L))
        assertEquals(1, FollowListRules.visible(items, null).size)
        assertEquals(0, FollowListRules.visible(items, 3).size)
    }

    private fun follow(recordId: Int, days: String, hour: Int): VodFollow = VodFollow().apply {
        id = recordId
        updateDays = days
        updateHour = hour
    }

    private fun entry(
        recordId: Int,
        days: String,
        hour: Int = 21,
        addedTime: Long = 0L,
        watchedDays: Set<Int> = emptySet(),
    ): FollowEntry =
        FollowEntry(
            follow = follow(recordId, days, hour).apply { this.addedTime = addedTime },
            history = null,
            watchedDays = watchedDays,
        )
}
