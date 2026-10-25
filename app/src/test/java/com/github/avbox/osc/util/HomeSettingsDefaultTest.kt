package com.github.avbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSettingsDefaultTest {

    @Test
    fun phoneKeepsImmersiveDefault() {
        assertFalse(resolveHomeLayoutVertical(null, 0))
        assertFalse(resolveHomeLayoutVertical("", 360))
        assertFalse(resolveHomeLayoutVertical(null, 599))
    }

    @Test
    fun tabletDefaultsToPlain() {
        assertTrue(resolveHomeLayoutVertical(null, 600))
        assertTrue(resolveHomeLayoutVertical("", 840))
    }

    @Test
    fun storedChoiceWins() {
        assertFalse(resolveHomeLayoutVertical(VALUE_LAYOUT_HORIZONTAL, 840))
        assertTrue(resolveHomeLayoutVertical(VALUE_LAYOUT_VERTICAL, 360))
    }
}
