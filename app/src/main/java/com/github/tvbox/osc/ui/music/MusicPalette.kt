package com.github.tvbox.osc.ui.music

import android.graphics.Bitmap
import coil3.Image
import coil3.toBitmap

object MusicPalette {

    private const val SAMPLE_SIZE = 24
    private const val DARK_CUT = 40
    private const val LIGHT_CUT = 233

    fun seedOf(image: Image?): Int? = runCatching {
        val bitmap = image?.toBitmap() ?: return null
        dominantArgb(bitmap)
    }.getOrNull()

    private fun dominantArgb(source: Bitmap): Int? {
        if (source.width <= 0 || source.height <= 0) return null
        val scaled = Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, false)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (scaled !== source) scaled.recycle()

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var weight = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < DARK_CUT || min > LIGHT_CUT) continue
            val saturation = (max - min).toDouble() / max.toDouble()
            val w = saturation * saturation * (max / 255.0) + 0.05
            sumR += r * w
            sumG += g * w
            sumB += b * w
            weight += w
        }
        if (weight <= 0.0) return null
        val r = (sumR / weight).toInt().coerceIn(0, 255)
        val g = (sumG / weight).toInt().coerceIn(0, 255)
        val b = (sumB / weight).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
