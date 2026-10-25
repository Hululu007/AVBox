package com.github.avbox.osc.player.effect

import com.github.avbox.osc.player.core.RedrawPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedrawPolicyTest {

    @Test
    fun geometry_redrawsOnlyWhenSizeChangedWhilePaused() {
        assertTrue(RedrawPolicy.shouldRedrawOnGeometry(true, false, true))
        assertFalse(RedrawPolicy.shouldRedrawOnGeometry(true, true, true))
        assertFalse(RedrawPolicy.shouldRedrawOnGeometry(false, false, true))
        assertFalse(RedrawPolicy.shouldRedrawOnGeometry(true, false, false))
    }

    @Test
    fun params_redrawsOnlyWhenPaused() {
        assertTrue(RedrawPolicy.shouldRedrawOnParams(false, true))
        assertFalse(RedrawPolicy.shouldRedrawOnParams(true, true))
        assertFalse(RedrawPolicy.shouldRedrawOnParams(false, false))
    }
}
