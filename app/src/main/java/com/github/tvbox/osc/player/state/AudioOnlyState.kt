package com.github.tvbox.osc.player.state

enum class AudioOnlyState {
    OFF,
    FORCED,
    CONFIRMED,
}

object AudioOnlyRules {

    fun state(
        forced: Boolean,
        confirmed: Boolean,
        trackAudioOnly: () -> Boolean?,
    ): AudioOnlyState {
        if (confirmed || trackAudioOnly() == true) return AudioOnlyState.CONFIRMED
        return if (forced) AudioOnlyState.FORCED else AudioOnlyState.OFF
    }
}
