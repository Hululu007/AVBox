package com.github.avbox.osc.ui.components

import android.graphics.Bitmap
import coil3.Image
import coil3.toBitmap
import kotlin.math.abs

internal data class SeedSample(val r: Int, val g: Int, val b: Int, val count: Int)

private const val HUE_BUCKETS = 360
private const val HUE_WINDOW_BACK = 14
private const val HUE_WINDOW_FORWARD = 15
private const val SOFT_CHROMA = 30
private const val CUTOFF_CHROMA = 10
private const val CUTOFF_PROPORTION = 0.01f
private const val WEIGHT_PROPORTION = 0.7f
private const val WEIGHT_CHROMA = 0.2f

internal fun pickSeedColor(samples: List<SeedSample>): Int? {
    if (samples.isEmpty()) return null
    var total = 0
    for (sample in samples) total += sample.count
    if (total <= 0) return null

    val huePopulation = FloatArray(HUE_BUCKETS)
    val hues = IntArray(samples.size)
    for (index in samples.indices) {
        val sample = samples[index]
        val hue = hueOf(sample.r, sample.g, sample.b)
        hues[index] = hue
        if (perceivedChromaOf(sample.r, sample.g, sample.b) >= CUTOFF_CHROMA) {
            huePopulation[hue] += sample.count.toFloat()
        }
    }

    val excited = FloatArray(HUE_BUCKETS)
    for (hue in 0 until HUE_BUCKETS) {
        val proportion = huePopulation[hue] / total
        if (proportion == 0f) continue
        for (offset in -HUE_WINDOW_BACK..HUE_WINDOW_FORWARD) {
            excited[(hue + offset + HUE_BUCKETS) % HUE_BUCKETS] += proportion
        }
    }

    var best: SeedSample? = null
    var bestScore = Float.NEGATIVE_INFINITY
    for (index in samples.indices) {
        val sample = samples[index]
        val chroma = perceivedChromaOf(sample.r, sample.g, sample.b)
        val proportion = excited[hues[index]]
        if (chroma < CUTOFF_CHROMA || proportion <= CUTOFF_PROPORTION) continue
        val proportionScore = proportion * 100f * WEIGHT_PROPORTION
        val chromaScore = (SOFT_CHROMA - abs(chroma - SOFT_CHROMA)) * WEIGHT_CHROMA
        val score = proportionScore + chromaScore
        if (score > bestScore) {
            bestScore = score
            best = sample
        }
    }
    val picked = best ?: return null
    return (0xFF shl 24) or (picked.r shl 16) or (picked.g shl 8) or picked.b
}

private fun perceivedChromaOf(r: Int, g: Int, b: Int): Int {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    return (max - min) * (255 - min) / 255
}

private fun hueOf(r: Int, g: Int, b: Int): Int {
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    if (delta == 0) return 0
    val hue = when (max) {
        r -> 60f * ((g - b).toFloat() / delta)
        g -> 60f * (2f + (b - r).toFloat() / delta)
        else -> 60f * (4f + (r - g).toFloat() / delta)
    }
    return (((hue % 360f) + 360f) % 360f).toInt()
}

object ImagePalette {

    private const val SAMPLE_SIZE = 24
    private const val BUCKET_SHIFT = 4

    fun seedOf(image: Image?): Int? = runCatching {
        val raw = image?.toBitmap() ?: return null
        val bitmap = if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            raw
        }
        try {
            dominantArgb(bitmap)
        } finally {
            if (bitmap !== raw) bitmap.recycle()
        }
    }.getOrNull()

    private fun dominantArgb(source: Bitmap): Int? {
        if (source.width <= 0 || source.height <= 0) return null
        val scaled = Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, false)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (scaled !== source) scaled.recycle()

        val bucketCount = 1 shl (3 * (8 - BUCKET_SHIFT))
        val counts = IntArray(bucketCount)
        val sumR = IntArray(bucketCount)
        val sumG = IntArray(bucketCount)
        val sumB = IntArray(bucketCount)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val index = ((r shr BUCKET_SHIFT) shl (2 * (8 - BUCKET_SHIFT))) or
                ((g shr BUCKET_SHIFT) shl (8 - BUCKET_SHIFT)) or
                (b shr BUCKET_SHIFT)
            counts[index]++
            sumR[index] += r
            sumG[index] += g
            sumB[index] += b
        }

        val samples = ArrayList<SeedSample>()
        for (i in counts.indices) {
            val count = counts[i]
            if (count == 0) continue
            samples.add(SeedSample(sumR[i] / count, sumG[i] / count, sumB[i] / count, count))
        }
        return pickSeedColor(samples)
    }
}
