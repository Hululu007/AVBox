package com.github.tvbox.osc.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOnlyRulesTest {

    private fun state(forced: Boolean, confirmed: Boolean, track: Boolean?): AudioOnlyState =
        AudioOnlyRules.state(forced, confirmed) { track }

    @Test
    fun idleIsOff() {
        assertEquals(AudioOnlyState.OFF, state(forced = false, confirmed = false, track = false))
    }

    @Test
    fun unknownTrackKeepsOff() {
        assertEquals(AudioOnlyState.OFF, state(forced = false, confirmed = false, track = null))
    }

    @Test
    fun pageIntentForcesBeforeConfirmation() {
        assertEquals(AudioOnlyState.FORCED, state(forced = true, confirmed = false, track = false))
    }

    @Test
    fun audioOnlyTrackConfirmsWithoutIntent() {
        assertEquals(AudioOnlyState.CONFIRMED, state(forced = false, confirmed = false, track = true))
    }

    @Test
    fun musicPageWithAudioTrackConfirms() {
        assertEquals(AudioOnlyState.CONFIRMED, state(forced = true, confirmed = false, track = true))
    }

    @Test
    fun confirmationSurvivesPageIntentRelease() {
        assertEquals(AudioOnlyState.CONFIRMED, state(forced = false, confirmed = true, track = false))
    }

    @Test
    fun newPlayKeepsIntentWhileDroppingConfirmation() {
        assertEquals(AudioOnlyState.FORCED, state(forced = true, confirmed = false, track = null))
    }

    @Test
    fun confirmedShortCircuitsTrackProbe() {
        var probed = false
        val value = AudioOnlyRules.state(forced = false, confirmed = true) {
            probed = true
            null
        }
        assertEquals(AudioOnlyState.CONFIRMED, value)
        assertEquals(false, probed)
    }
}
