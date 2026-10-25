package com.github.avbox.osc.player

object PrewarmPolicy {
    const val NO_IDLE_RELEASE = -1L

    @JvmStatic
    fun idleReleaseDelayMs(prewarmEnabled: Boolean, baseDelayMs: Long): Long =
        if (prewarmEnabled) NO_IDLE_RELEASE else baseDelayMs

    @JvmStatic
    fun shouldScheduleOnDisable(engineInUse: Boolean): Boolean = !engineInUse
}
