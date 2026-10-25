package com.github.avbox.osc.player.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGestureBottomBandTest {

    @Test
    fun bandCoversTheLastStripOfTheLayer() {
        assertFalse(isInBottomGestureBand(downY = 899f, height = 1000, bandPx = 100f))
        assertTrue(isInBottomGestureBand(downY = 900f, height = 1000, bandPx = 100f))
        assertTrue(isInBottomGestureBand(downY = 1000f, height = 1000, bandPx = 100f))
    }

    @Test
    fun nonPositiveBandMatchesNothing() {
        assertFalse(isInBottomGestureBand(downY = 1000f, height = 1000, bandPx = 0f))
        assertFalse(isInBottomGestureBand(downY = 1000f, height = 1000, bandPx = -10f))
    }

    @Test
    fun emptyLayerNeverMatches() {
        assertFalse(isInBottomGestureBand(downY = 0f, height = 0, bandPx = 100f))
    }
}
