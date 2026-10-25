package com.github.avbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOrientationTest {

    @Test
    fun portraitWhenTallerThanWide() {
        assertTrue(VideoOrientation.isPortrait(1080, 1920))
    }

    @Test
    fun landscapeWhenWiderThanTall() {
        assertFalse(VideoOrientation.isPortrait(1920, 1080))
    }

    @Test
    fun notPortraitWhenSizeUnavailable() {
        assertFalse(VideoOrientation.isPortrait(0, 0))
        assertFalse(VideoOrientation.isPortrait(0, 1920))
        assertFalse(VideoOrientation.isPortrait(1080, 0))
    }

    @Test
    fun notPortraitWhenSquare() {
        assertFalse(VideoOrientation.isPortrait(1080, 1080))
    }

    @Test
    fun usableSizeRequiresBothDimensions() {
        assertTrue(VideoOrientation.isUsableSize(1080, 1920))
        assertTrue(VideoOrientation.isUsableSize(1920, 1080))
        assertFalse(VideoOrientation.isUsableSize(0, 0))
        assertFalse(VideoOrientation.isUsableSize(0, 1920))
        assertFalse(VideoOrientation.isUsableSize(1080, 0))
    }
}
