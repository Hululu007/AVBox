package com.github.avbox.osc.util

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class DanmuSettingsConfig(
    val open: Boolean,
    val maxLine: Int,
    val speed: Float,
    val alpha: Float,
    val sizeScale: Float,
    val randomColor: Boolean,
    val api: String,
)

object DanmuSettingsState {

    private val PALETTE = arrayOf(
            "#ffffff", "#70f3ff", "#44cef6", "#3eede7", "#00e079", "#2edfa3",
            "#bce672", "#fff143", "#ffa631", "#ff7500", "#ff4e20", "#ff2d51",
            "#ef7a82", "#ff0097", "#b0a4e3", "#4b5cc4"
    )

    private var current by mutableStateOf(load())

    val config: DanmuSettingsConfig get() = current

    private fun load(): DanmuSettingsConfig = DanmuSettingsConfig(
        open = KV.get(KVKeys.DANMU_OPEN, true),
        maxLine = clamp(KV.get(KVKeys.DANMU_MAX_LINE, 3), 1, 15),
        speed = KV.get(KVKeys.DANMU_SPEED, 1.5f),
        alpha = clamp(KV.get(KVKeys.DANMU_ALPHA, 1.0f), 0.1f, 1.0f),
        sizeScale = clamp(KV.get(KVKeys.DANMU_SIZE_SCALE, 0.8f), 0.6f, 2.0f),
        randomColor = KV.get(KVKeys.DANMU_RANDOM_COLOR, false),
        api = KV.get(KVKeys.DANMU_API, ""),
    )

    var open: Boolean
        get() = current.open
        set(value) {
            KV.put(KVKeys.DANMU_OPEN, value)
            current = current.copy(open = value)
        }

    var maxLine: Int
        get() = current.maxLine
        set(value) {
            val clamped = clamp(value, 1, 15)
            KV.put(KVKeys.DANMU_MAX_LINE, clamped)
            current = current.copy(maxLine = clamped)
        }

    var speed: Float
        get() = current.speed
        set(value) {
            KV.put(KVKeys.DANMU_SPEED, value)
            current = current.copy(speed = value)
        }

    var alpha: Float
        get() = current.alpha
        set(value) {
            val clamped = clamp(value, 0.1f, 1.0f)
            KV.put(KVKeys.DANMU_ALPHA, clamped)
            current = current.copy(alpha = clamped)
        }

    var sizeScale: Float
        get() = current.sizeScale
        set(value) {
            val clamped = clamp(value, 0.6f, 2.0f)
            KV.put(KVKeys.DANMU_SIZE_SCALE, clamped)
            current = current.copy(sizeScale = clamped)
        }

    var randomColor: Boolean
        get() = current.randomColor
        set(value) {
            KV.put(KVKeys.DANMU_RANDOM_COLOR, value)
            current = current.copy(randomColor = value)
        }

    var api: String
        get() = current.api
        set(value) {
            KV.put(KVKeys.DANMU_API, value)
            KV.delete(KVKeys.DANMU_API_USE_DEFAULT)
            current = current.copy(api = value)
        }

    fun reset() {
        KV.delete(KVKeys.DANMU_RANDOM_COLOR)
        KV.delete(KVKeys.DANMU_SPEED)
        KV.delete(KVKeys.DANMU_SIZE_SCALE)
        KV.delete(KVKeys.DANMU_MAX_LINE)
        KV.delete(KVKeys.DANMU_ALPHA)
        current = load()
    }

    fun randomColor(): Int {
        val index = (Math.random() * PALETTE.size).toInt()
        return Color.parseColor(PALETTE[index])
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(value, max))
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(value, max))
    }
}
