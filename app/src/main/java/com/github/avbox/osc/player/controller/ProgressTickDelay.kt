package com.github.avbox.osc.player.controller

internal object ProgressTickDelay {

    const val MIN_MS = 200L

    private const val MAX_MS = 2000L
    private const val SECOND_MS = 1000

    fun next(positionMs: Int, speed: Float): Long {
        val rate = if (speed > 0f) speed else 1f
        val remainingMs = (SECOND_MS - positionMs % SECOND_MS) / rate
        return remainingMs.toLong().coerceIn(MIN_MS, MAX_MS)
    }
}
