package com.github.tvbox.osc.player.state

object NearEndRules {

    fun shouldAdvance(positionMs: Long, durationMs: Long, skipEndSeconds: Int): Boolean {
        if (positionMs == 0L || durationMs == 0L) return false
        if (skipEndSeconds <= 0) return false
        return positionMs + skipEndSeconds * 1000L >= durationMs
    }
}
