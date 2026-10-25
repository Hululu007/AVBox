package com.github.avbox.osc.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMockupPreviewTest {

    @Test
    fun landscapeFrameNeedsWideLandscapeWindow() {
        assertFalse(phoneMockupLandscape(411, 866))
        assertTrue(phoneMockupLandscape(866, 411))
        assertTrue(phoneMockupLandscape(1097, 617))
        assertFalse(phoneMockupLandscape(617, 1097))
        assertFalse(phoneMockupLandscape(599, 360))
    }

    @Test
    fun frameSwapsOrientation() {
        assertEquals(168f, phoneMockupFrame(false).width.value, 0.01f)
        assertEquals(308f, phoneMockupFrame(false).height.value, 0.01f)
        assertEquals(308f, phoneMockupFrame(true).width.value, 0.01f)
        assertEquals(190f, phoneMockupFrame(true).height.value, 0.01f)
    }

    @Test
    fun wideLandscapeMockupStaysInsideHalfScreen() {
        val frame = phoneMockupFrame(true)
        val scale = phoneMockupScale(1065.dp, 617, frame)
        assertTrue(frame.height * scale <= 617.dp * 0.5f + 0.5.dp)
        assertTrue(scale <= 2.2f)
    }

    @Test
    fun phonePortraitScaleKeepsFloor() {
        assertEquals(1f, phoneMockupScale(379.dp, 866, phoneMockupFrame(false)), 0.001f)
    }

    @Test
    fun tabletPortraitScaleFollowsWidth() {
        assertEquals(1.22f, phoneMockupScale(585.dp, 1097, phoneMockupFrame(false)), 0.01f)
    }
}
