package com.github.avbox.osc.player.effect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureEffectsRestartPolicyTest {

    @Test
    fun enableNeedsRestartOnlyWhenEpisodeHasNoPipe() {
        assertTrue(PictureEffects.restartNeeded(wantEffects = true, opened = false, presetOriginal = false))
        assertTrue(PictureEffects.restartNeeded(wantEffects = true, opened = false, presetOriginal = true))
        assertFalse(PictureEffects.restartNeeded(wantEffects = true, opened = true, presetOriginal = false))
    }

    @Test
    fun disableNeedsRestartOnlyFromOriginalPresetWithOpenPipe() {
        assertTrue(PictureEffects.restartNeeded(wantEffects = false, opened = true, presetOriginal = true))
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = true, presetOriginal = false))
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = false, presetOriginal = true))
    }

    @Test
    fun idleStateNeverRestarts() {
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = false, presetOriginal = false))
    }
}
