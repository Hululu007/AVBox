package com.github.tvbox.osc.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

object LiquidGlassState {

    const val DEFAULT_BLUR_DP = 20f
    const val DEFAULT_DISTORTION_DP = 30f
    const val DEFAULT_TRANSLUCENCY = 0.5f
    const val DEFAULT_DISPERSION = true
    val BLUR_RANGE: ClosedFloatingPointRange<Float> = 0f..40f
    val DISTORTION_RANGE: ClosedFloatingPointRange<Float> = 0f..30f
    val TRANSLUCENCY_RANGE: ClosedFloatingPointRange<Float> = 0f..1f

    private var current by mutableStateOf(load())

    val config: LiquidGlassConfig get() = current

    private fun load(): LiquidGlassConfig = LiquidGlassConfig(
        navbarEnabled = KV.get(HawkConfig.LIQUID_GLASS_NAVBAR, true),
        controlsEnabled = KV.get(HawkConfig.LIQUID_GLASS_CONTROLS, true),
        blurDp = KV.get(HawkConfig.LIQUID_GLASS_BLUR, DEFAULT_BLUR_DP),
        distortionDp = KV.get(HawkConfig.LIQUID_GLASS_DISTORTION, DEFAULT_DISTORTION_DP),
        translucency = KV.get(HawkConfig.LIQUID_GLASS_TRANSLUCENCY, DEFAULT_TRANSLUCENCY),
        dispersion = KV.get(HawkConfig.LIQUID_GLASS_DISPERSION, DEFAULT_DISPERSION),
    )

    fun setNavbarEnabled(enabled: Boolean) {
        KV.put(HawkConfig.LIQUID_GLASS_NAVBAR, enabled)
        current = current.copy(navbarEnabled = enabled)
    }

    fun setControlsEnabled(enabled: Boolean) {
        KV.put(HawkConfig.LIQUID_GLASS_CONTROLS, enabled)
        current = current.copy(controlsEnabled = enabled)
    }

    fun setBlurDp(dp: Float) {
        KV.put(HawkConfig.LIQUID_GLASS_BLUR, dp)
        current = current.copy(blurDp = dp)
    }

    fun setDistortionDp(dp: Float) {
        KV.put(HawkConfig.LIQUID_GLASS_DISTORTION, dp)
        current = current.copy(distortionDp = dp)
    }

    fun setTranslucency(value: Float) {
        KV.put(HawkConfig.LIQUID_GLASS_TRANSLUCENCY, value)
        current = current.copy(translucency = value)
    }

    fun setDispersion(enabled: Boolean) {
        KV.put(HawkConfig.LIQUID_GLASS_DISPERSION, enabled)
        current = current.copy(dispersion = enabled)
    }

    /** 只重置效果参数,不动两个启用开关 */
    fun restoreDefaults() {
        setBlurDp(DEFAULT_BLUR_DP)
        setDistortionDp(DEFAULT_DISTORTION_DP)
        setTranslucency(DEFAULT_TRANSLUCENCY)
        setDispersion(DEFAULT_DISPERSION)
    }
}
