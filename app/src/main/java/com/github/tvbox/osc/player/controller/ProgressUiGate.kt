package com.github.tvbox.osc.player.controller

enum class ProgressPhase {
    IDLE,
    LOADING,
    ACTIVE,
}

object ProgressUiGate {

    fun accept(phase: ProgressPhase, armedGen: Int, kernelGen: Int): Boolean {
        if (phase != ProgressPhase.ACTIVE) return false
        if (armedGen < 0) return false
        return armedGen == kernelGen
    }
}

data class ProgressSnapshot(
    val durationMs: Int,
    val positionMs: Int,
    val bufferedPercent: Int,
)
