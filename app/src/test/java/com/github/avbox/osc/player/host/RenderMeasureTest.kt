package com.github.avbox.osc.player.host

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RenderMeasureTest {

    private fun measure(
        width: Int = 1000,
        height: Int = 1000,
        scaleType: Int = RenderMeasure.SCALE_DEFAULT,
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        rotation: Int = 0,
    ): IntArray = RenderMeasure.measure(
        widthSpec(width),
        widthSpec(height),
        width,
        height,
        scaleType,
        videoWidth,
        videoHeight,
        rotation,
    )

    private fun widthSpec(size: Int): Int = (1 shl 30) or size

    @Test
    fun default_letterboxesInsideContainer() {
        assertArrayEquals(intArrayOf(1000, 562), measure())
    }

    @Test
    fun default_matchesWhenRatioAlignmentIsExact() {
        assertArrayEquals(intArrayOf(1000, 1000), measure(width = 1000, height = 1000, videoWidth = 1000, videoHeight = 1000))
    }

    @Test
    fun original_usesVideoSize() {
        assertArrayEquals(intArrayOf(1920, 1080), measure(scaleType = RenderMeasure.SCALE_ORIGINAL))
    }

    @Test
    fun sixteenNine_fitsHeight() {
        assertArrayEquals(intArrayOf(1000, 558), measure(scaleType = RenderMeasure.SCALE_16_9))
    }

    @Test
    fun sixteenNine_fitsWidthWhenHeightIsTight() {
        assertArrayEquals(intArrayOf(500, 279), measure(width = 500, height = 2000, scaleType = RenderMeasure.SCALE_16_9))
    }

    @Test
    fun fourThree() {
        assertArrayEquals(intArrayOf(1000, 750), measure(scaleType = RenderMeasure.SCALE_4_3))
    }

    @Test
    fun matchParent_returnsRawSpecs() {
        val measured = measure(scaleType = RenderMeasure.SCALE_MATCH_PARENT)
        assertArrayEquals(intArrayOf(widthSpec(1000), widthSpec(1000)), measured)
    }

    @Test
    fun matchParent_withRotation_returnsSwappedRawSpecs() {
        val measured = measure(width = 1000, height = 500, scaleType = RenderMeasure.SCALE_MATCH_PARENT, rotation = 90)
        assertArrayEquals(intArrayOf(widthSpec(500), widthSpec(1000)), measured)
    }

    @Test
    fun centerCrop_fillsContainer() {
        assertArrayEquals(intArrayOf(1777, 1000), measure(scaleType = RenderMeasure.SCALE_CENTER_CROP))
    }

    @Test
    fun rotation0_usesContainerSizeAsIs() {
        assertArrayEquals(intArrayOf(281, 500), measure(width = 1000, height = 500, videoWidth = 1080, videoHeight = 1920))
    }

    @Test
    fun rotation90_swapsContainerSizeBeforeRatioMath() {
        assertArrayEquals(
            intArrayOf(500, 888),
            measure(width = 1000, height = 500, rotation = 90, videoWidth = 1080, videoHeight = 1920),
        )
    }

    @Test
    fun unknownVideoSize_returnsContainerSize() {
        assertArrayEquals(intArrayOf(1000, 1000), measure(videoWidth = 0, videoHeight = 0))
    }

    @Test
    fun unknownScaleType_fallsBackToDefault() {
        assertArrayEquals(intArrayOf(1000, 562), measure(scaleType = 99))
    }
}
