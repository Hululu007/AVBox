package com.github.avbox.osc.ui.page

import com.github.avbox.osc.data.FollowDays

internal object FollowListRules {

    fun dayCounts(items: List<FollowEntry>): Map<Int, Int> =
        items.flatMap { entry ->
            FollowDays.decode(entry.follow.updateDays) - entry.watchedDays
        }.groupingBy { it }.eachCount()

    fun visible(items: List<FollowEntry>, day: Int?): List<FollowEntry> =
        if (day == null) {
            items.sortedByDescending { it.follow.addedTime }
        } else {
            items.filter { day in FollowDays.decode(it.follow.updateDays) }
                .sortedWith(compareBy({ it.follow.updateHour }, { -it.follow.addedTime }))
        }

    fun isWatched(entry: FollowEntry, day: Int?): Boolean {
        if (day != null) return day in entry.watchedDays
        val days = FollowDays.decode(entry.follow.updateDays)
        return days.isNotEmpty() && entry.watchedDays.containsAll(days)
    }
}
