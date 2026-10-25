package com.github.avbox.osc.ui.components

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePaletteTest {

    @Test
    fun picksLargeWarmAreaOverBlackBannerAndRedAccent() {
        val samples = listOf(
            SeedSample(18, 18, 20, 90),
            SeedSample(238, 228, 204, 220),
            SeedSample(214, 200, 172, 140),
            SeedSample(198, 182, 150, 60),
            SeedSample(180, 32, 36, 60),
        )
        val seed = pickSeedColor(samples)
        assertNotNull(seed)
        val r = (seed!! shr 16) and 0xFF
        val g = (seed shr 8) and 0xFF
        val b = seed and 0xFF
        assertTrue("expect warm tone, got r=$r g=$g b=$b", r > g && g > b)
    }

    @Test
    fun returnsNullForGrayscaleOnly() {
        val samples = listOf(
            SeedSample(20, 20, 20, 200),
            SeedSample(240, 240, 240, 200),
            SeedSample(128, 128, 128, 176),
        )
        assertNull(pickSeedColor(samples))
    }

    @Test
    fun picksColoredAccentWhenDarkAreaDominates() {
        val samples = listOf(
            SeedSample(24, 24, 26, 300),
            SeedSample(60, 130, 200, 90),
        )
        val seed = pickSeedColor(samples)
        assertNotNull(seed)
        val r = (seed!! shr 16) and 0xFF
        val b = seed and 0xFF
        assertTrue("expect blue accent, got r=$r b=$b", b > r)
    }

    @Test
    fun ignoresLargeNearWhiteArea() {
        val samples = listOf(
            SeedSample(242, 237, 233, 300),
            SeedSample(214, 200, 172, 120),
            SeedSample(180, 32, 36, 60),
        )
        val seed = pickSeedColor(samples)
        assertNotNull(seed)
        val r = (seed!! shr 16) and 0xFF
        val g = (seed shr 8) and 0xFF
        val b = seed and 0xFF
        val brightness = (r + g + b) / 3
        assertTrue("near-white area should not win, got r=$r g=$g b=$b", brightness < 220)
    }

    @Test
    fun returnsNullForEmptyInput() {
        assertNull(pickSeedColor(emptyList()))
    }
}
