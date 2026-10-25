package com.github.avbox.osc.util

import android.os.SystemClock

object SpiderInitWatch {

    private const val WINDOW_MS = 800L

    @Volatile
    private var startAt = 0L

    @Volatile
    private var endAt = 0L

    fun begin() {
        startAt = SystemClock.elapsedRealtime()
    }

    fun end() {
        endAt = SystemClock.elapsedRealtime()
    }

    fun inWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (startAt != 0L && now - startAt <= WINDOW_MS) || (endAt != 0L && now - endAt <= WINDOW_MS)
    }
}
