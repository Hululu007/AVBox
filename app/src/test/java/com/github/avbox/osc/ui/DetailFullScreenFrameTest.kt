package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailFullScreenFrameTest {

    @Test
    fun fullBoxFollowsFullWhenNotRotating() {
        assertTrue(DetailFullScreenFrame.fullBox(rotating = false, fullScreen = true, landscapeNow = false))
        assertFalse(DetailFullScreenFrame.fullBox(rotating = false, fullScreen = false, landscapeNow = true))
    }

    @Test
    fun fullBoxFollowsLandscapeWhileRotating() {
        assertTrue(DetailFullScreenFrame.fullBox(rotating = true, fullScreen = false, landscapeNow = true))
        assertFalse(DetailFullScreenFrame.fullBox(rotating = true, fullScreen = false, landscapeNow = false))
    }

    @Test
    fun playerFullScreenFollowsFullWhenNotRotating() {
        assertTrue(DetailFullScreenFrame.playerFullScreen(rotating = false, fullScreen = true, landscapeNow = false))
        assertFalse(DetailFullScreenFrame.playerFullScreen(rotating = false, fullScreen = false, landscapeNow = true))
    }

    @Test
    fun playerFullScreenInvertsLandscapeWhileExitingRotation() {
        assertFalse(DetailFullScreenFrame.playerFullScreen(rotating = true, fullScreen = false, landscapeNow = true))
        assertTrue(DetailFullScreenFrame.playerFullScreen(rotating = true, fullScreen = false, landscapeNow = false))
    }

    @Test
    fun theTwoFramesStayOppositeWhileRotating() {
        listOf(false, true).forEach { fullScreen ->
            listOf(false, true).forEach { landscapeNow ->
                val box = DetailFullScreenFrame.fullBox(rotating = true, fullScreen, landscapeNow)
                val player = DetailFullScreenFrame.playerFullScreen(rotating = true, fullScreen, landscapeNow)
                assertTrue(box != player)
            }
        }
    }
}
