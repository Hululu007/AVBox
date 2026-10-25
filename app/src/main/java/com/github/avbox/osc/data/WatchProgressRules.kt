package com.github.avbox.osc.data

enum class WatchDecision { SAVE, SKIP }

object WatchProgressRules {

    const val MIN_RESUME_MS = 30_000L

    const val MIN_RESUME_PERCENT = 30L

    const val FINISHED_PERCENT = 95L

    fun decide(positionMs: Long, durationMs: Long): WatchDecision {
        if (positionMs <= 0) return WatchDecision.SKIP
        if (durationMs > 0 && positionMs * 100 >= durationMs * FINISHED_PERCENT) return WatchDecision.SAVE
        val minResume = if (durationMs > 0) {
            minOf(MIN_RESUME_MS, durationMs * MIN_RESUME_PERCENT / 100)
        } else {
            MIN_RESUME_MS
        }
        return if (positionMs >= minResume) WatchDecision.SAVE else WatchDecision.SKIP
    }

    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean =
        decide(positionMs, durationMs) != WatchDecision.SKIP
}
