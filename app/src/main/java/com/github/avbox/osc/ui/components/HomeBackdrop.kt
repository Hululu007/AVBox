package com.github.avbox.osc.ui.components

import android.graphics.Color

private const val MAX_SATURATION = 0.14f
private const val LIGHT_VALUE = 0.96f
private const val DARK_VALUE = 0.24f

internal object HomeBackdrop {

    private val seeds = PosterSeedCache()

    fun has(pic: String?): Boolean = !pic.isNullOrEmpty() && seeds.has(pic)

    fun seedOf(pic: String?): Int? = pic?.takeIf { it.isNotEmpty() }?.let { seeds.get(it) }

    fun put(pic: String?, seed: Int?) {
        val key = pic?.takeIf { it.isNotEmpty() } ?: return
        seeds.put(key, seed)
    }

    fun surfaceOf(seedArgb: Int, isDark: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(seedArgb, hsv)
        hsv[1] = hsv[1].coerceAtMost(MAX_SATURATION)
        hsv[2] = if (isDark) DARK_VALUE else LIGHT_VALUE
        return Color.HSVToColor(hsv)
    }
}
