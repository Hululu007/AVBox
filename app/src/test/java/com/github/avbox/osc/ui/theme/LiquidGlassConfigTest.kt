package com.github.avbox.osc.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassConfigTest {

    private fun at(translucency: Float) = LiquidGlassConfig(
        navbarEnabled = true,
        controlsEnabled = true,
        blurDp = 20f,
        distortionDp = 30f,
        translucency = translucency,
        dispersion = false,
    )

    @Test
    fun halfTranslucency_isNeutralAnchor() {
        val config = at(0.5f)
        assertEquals(1f, config.containerAlphaScale, 0f)
        assertEquals(0f, config.contentBrightness, 0f)
        assertEquals(1f, config.contentContrast, 0f)
        assertEquals(1.5f, config.contentSaturation, 0f)
    }

    @Test
    fun defaults_matchDesignValues() {
        assertEquals(5f, LiquidGlassState.DEFAULT_BLUR_DP, 0f)
        assertEquals(30f, LiquidGlassState.DEFAULT_DISTORTION_DP, 0f)
        assertEquals(0.5f, LiquidGlassState.DEFAULT_TRANSLUCENCY, 0f)
        val config = at(LiquidGlassState.DEFAULT_TRANSLUCENCY)
        assertEquals(1f, config.containerAlphaScale, 0f)
        assertEquals(0f, config.contentBrightness, 0f)
        assertEquals(1f, config.contentContrast, 0f)
        assertEquals(1.5f, config.contentSaturation, 0f)
    }

    @Test
    fun translucency_scalesContainerAlphaMonotonically() {
        assertEquals(2f, at(0f).containerAlphaScale, 0f)
        assertEquals(0f, at(1f).containerAlphaScale, 0f)
        assertTrue(at(0.75f).containerAlphaScale < at(0.25f).containerAlphaScale)
    }

    @Test
    fun translucency_dimsContentOnlyAboveDefault() {
        val opaque = at(0f)
        val clear = at(1f)
        assertTrue(opaque.contentBrightness > 0f)
        assertTrue(opaque.contentContrast < 1f)
        assertEquals(0f, at(0.75f).contentBrightness, 0f)
        assertEquals(1f, at(0.75f).contentContrast, 0f)
        assertEquals(0f, clear.contentBrightness, 0f)
        assertEquals(1f, clear.contentContrast, 0f)
        assertEquals(1.5f, at(0.5f).contentSaturation, 0f)
        assertTrue(clear.contentSaturation < at(0.75f).contentSaturation)
        assertTrue(at(0.75f).contentSaturation < 1.5f)
        assertEquals(1f, clear.contentSaturation, 0f)
    }
}
