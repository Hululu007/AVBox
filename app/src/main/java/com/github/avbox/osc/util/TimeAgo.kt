package com.github.avbox.osc.util

internal enum class TimeAgoUnit {
    JustNow,
    Minutes,
    Hours,
    Days,
    Months,
}

internal data class TimeAgoValue(val unit: TimeAgoUnit, val value: Int)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val DAYS_PER_MONTH = 30L

internal fun timeAgoValueOf(atMs: Long, nowMs: Long): TimeAgoValue {
    val diff = (nowMs - atMs).coerceAtLeast(0L)
    val minutes = diff / MINUTE_MS
    val hours = diff / HOUR_MS
    val days = diff / DAY_MS
    return when {
        minutes < 1L -> TimeAgoValue(TimeAgoUnit.JustNow, 0)
        hours < 1L -> TimeAgoValue(TimeAgoUnit.Minutes, minutes.toInt())
        days < 1L -> TimeAgoValue(TimeAgoUnit.Hours, hours.toInt())
        days < DAYS_PER_MONTH -> TimeAgoValue(TimeAgoUnit.Days, days.toInt())
        else -> TimeAgoValue(TimeAgoUnit.Months, (days / DAYS_PER_MONTH).toInt())
    }
}
