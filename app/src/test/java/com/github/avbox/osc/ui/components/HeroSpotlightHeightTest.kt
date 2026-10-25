package com.github.avbox.osc.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroSpotlightHeightTest {

    @Test
    fun shortScreenClampsToMinHeight() {
        assertEquals(360f, heroSpotlightHeight(600).value, 0.01f)
    }

    @Test
    fun normalScreenFollowsRatio() {
        assertEquals(464f, heroSpotlightHeight(800).value, 0.01f)
    }

    @Test
    fun tallScreenClampsToMaxHeight() {
        assertEquals(560f, heroSpotlightHeight(1400).value, 0.01f)
    }
}
