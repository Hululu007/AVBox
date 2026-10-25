package com.github.avbox.osc.player.controller

import com.github.avbox.osc.player.BasePlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTextFormatTest {

    @Test
    fun displaySpeed_usesByteUnitsWithBinaryBase() {
        assertEquals("512B/s", PlayerTextFormat.displaySpeed(512L, true))
        assertEquals("900KB/s", PlayerTextFormat.displaySpeed(900L * 1024, true))
        assertTrue(PlayerTextFormat.displaySpeed(3L * 1024 * 1024, true).endsWith("MB/s"))
    }

    @Test
    fun displaySpeed_zeroTextFollowsShowFlag() {
        assertEquals("0B/s", PlayerTextFormat.displaySpeed(0L, true))
        assertEquals("", PlayerTextFormat.displaySpeed(0L, false))
    }

    @Test
    fun renderName_mapsSurfaceAndTexture() {
        assertEquals("SurfaceView", PlayerTextFormat.renderName(1))
        assertEquals("TextureView", PlayerTextFormat.renderName(0))
    }

    @Test
    fun scaleName_ratioScalesDoNotNeedResources() {
        assertEquals("16:9", PlayerTextFormat.scaleName(BasePlayerView.SCREEN_SCALE_16_9))
        assertEquals("4:3", PlayerTextFormat.scaleName(BasePlayerView.SCREEN_SCALE_4_3))
    }
}
