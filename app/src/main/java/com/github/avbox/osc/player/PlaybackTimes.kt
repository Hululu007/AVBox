package com.github.avbox.osc.player

object PlaybackTimes {

    fun safeTimeMs(timeMs: Long): Int {
        if (timeMs <= 0) return 0
        if (timeMs > Int.MAX_VALUE) return Int.MAX_VALUE
        return timeMs.toInt()
    }
}
