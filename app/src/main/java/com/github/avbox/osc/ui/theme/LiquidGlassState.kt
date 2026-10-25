package com.github.avbox.osc.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV

object LiquidGlassState {

    const val DEFAULT_BLUR_DP = 5f
    const val DEFAULT_DISTORTION_DP = 30f
    const val DEFAULT_TRANSLUCENCY = 0.5f
    const val DEFAULT_DISPERSION = true
    val BLUR_RANGE: ClosedFloatingPointRange<Float> = 0f..40f
    val DISTORTION_RANGE: ClosedFloatingPointRange<Float> = 0f..30f
    val TRANSLUCENCY_RANGE: ClosedFloatingPointRange<Float> = 0f..1f

    private var current by mutableStateOf(load())

    val config: LiquidGlassConfig get() = current

    private fun load(): LiquidGlassConfig = LiquidGlassConfig(
        navbarEnabled = KV.get(KVKeys.LIQUID_GLASS_NAVBAR, true),
        controlsEnabled = KV.get(KVKeys.LIQUID_GLASS_CONTROLS, true),
        blurDp = KV.get(KVKeys.LIQUID_GLASS_BLUR, DEFAULT_BLUR_DP),
        distortionDp = KV.get(KVKeys.LIQUID_GLASS_DISTORTION, DEFAULT_DISTORTION_DP),
        translucency = KV.get(KVKeys.LIQUID_GLASS_TRANSLUCENCY, DEFAULT_TRANSLUCENCY),
        dispersion = KV.get(KVKeys.LIQUID_GLASS_DISPERSION, DEFAULT_DISPERSION),
    )

    var navbarEnabled: Boolean
        get() = current.navbarEnabled
        set(value) {
            KV.put(KVKeys.LIQUID_GLASS_NAVBAR, value)
            current = current.copy(navbarEnabled = value)
        }

    var controlsEnabled: Boolean
        get() = current.controlsEnabled
        set(value) {
            KV.put(KVKeys.LIQUID_GLASS_CONTROLS, value)
            current = current.copy(controlsEnabled = value)
        }

    var blurDp: Float
        get() = current.blurDp
        set(value) {
            KV.put(KVKeys.LIQUID_GLASS_BLUR, value)
            current = current.copy(blurDp = value)
        }

    var distortionDp: Float
        get() = current.distortionDp
        set(value) {
            KV.put(KVKeys.LIQUID_GLASS_DISTORTION, value)
            current = current.copy(distortionDp = value)
        }

    var translucency: Float
        get() = current.translucency
        set(value) {
            KV.put(KVKeys.LIQUID_GLASS_TRANSLUCENCY, value)
            current = current.copy(translucency = value)
        }

    var dispersion: Boolean
        get() = current.dispersion
        set(value) {
            KV.put(KVKeys.LIQUID_GLASS_DISPERSION, value)
            current = current.copy(dispersion = value)
        }

    fun restoreDefaults() {
        blurDp = DEFAULT_BLUR_DP
        distortionDp = DEFAULT_DISTORTION_DP
        translucency = DEFAULT_TRANSLUCENCY
        dispersion = DEFAULT_DISPERSION
    }
}
