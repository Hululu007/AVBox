package com.github.tvbox.osc.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通透度派生量:默认值必须是恒等点 —— 这是"加了通透度但默认观感与改动前一致"的唯一保证 */
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
    fun defaultTranslucency_isIdentity() {
        val config = at(LiquidGlassState.DEFAULT_TRANSLUCENCY)
        assertEquals(1f, config.containerAlphaScale, 0f)
        assertEquals(0f, config.contentBrightness, 0f)
        assertEquals(1f, config.contentContrast, 0f)
    }

    @Test
    fun translucency_scalesContainerAlphaMonotonically() {
        assertEquals(1.25f, at(0f).containerAlphaScale, 0f)
        assertEquals(0.75f, at(1f).containerAlphaScale, 0f)
        assertTrue(at(0.75f).containerAlphaScale < at(0.25f).containerAlphaScale)
    }

    @Test
    fun translucency_compensatesContentReadability() {
        // 底色越淡,采样内容压得越暗、对比抬得越高(补偿文字可读性)
        val opaque = at(0f)
        val clear = at(1f)
        assertTrue(clear.contentBrightness < opaque.contentBrightness)
        assertTrue(clear.contentContrast > opaque.contentContrast)
    }
}
