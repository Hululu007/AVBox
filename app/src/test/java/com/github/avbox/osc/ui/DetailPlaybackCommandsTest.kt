package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPlaybackCommandsTest {

    @Test
    fun exitFromLandscapeRotatesBack() {
        val (full, rotating) = DetailPlaybackCommands.exitFullScreenState(DetailPlaybackFacts(landscape = true))
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromPortraitDoesNotRotate() {
        val (full, rotating) = DetailPlaybackCommands.exitFullScreenState(DetailPlaybackFacts(landscape = false))
        assertFalse(full)
        assertFalse(rotating)
    }
}
