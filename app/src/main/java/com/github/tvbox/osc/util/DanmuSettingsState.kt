package com.github.tvbox.osc.util

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
        open = KV.get(HawkConfig.DANMU_OPEN, true),
        maxLine = clamp(KV.get(HawkConfig.DANMU_MAX_LINE, 3), 1, 15),
        speed = KV.get(HawkConfig.DANMU_SPEED, 1.5f),
        alpha = clamp(KV.get(HawkConfig.DANMU_ALPHA, 1.0f), 0.1f, 1.0f),
        sizeScale = clamp(KV.get(HawkConfig.DANMU_SIZE_SCALE, 0.8f), 0.6f, 2.0f),
        randomColor = KV.get(HawkConfig.DANMU_RANDOM_COLOR, false),
        api = KV.get(HawkConfig.DANMU_API, ""),
    )

    fun setOpen(open: Boolean) {
        KV.put(HawkConfig.DANMU_OPEN, open)
        current = current.copy(open = open)
    }

    fun setMaxLine(line: Int) {
        val clamped = clamp(line, 1, 15)
        KV.put(HawkConfig.DANMU_MAX_LINE, clamped)
        current = current.copy(maxLine = clamped)
    }

    fun setSpeed(speed: Float) {
        KV.put(HawkConfig.DANMU_SPEED, speed)
        current = current.copy(speed = speed)
    }

    fun setAlpha(alpha: Float) {
        val clamped = clamp(alpha, 0.1f, 1.0f)
        KV.put(HawkConfig.DANMU_ALPHA, clamped)
        current = current.copy(alpha = clamped)
    }

    fun setSizeScale(scale: Float) {
        val clamped = clamp(scale, 0.6f, 2.0f)
        KV.put(HawkConfig.DANMU_SIZE_SCALE, clamped)
        current = current.copy(sizeScale = clamped)
    }

    fun setRandomColor(enabled: Boolean) {
        KV.put(HawkConfig.DANMU_RANDOM_COLOR, enabled)
        current = current.copy(randomColor = enabled)
    }

    fun setApi(api: String) {
        KV.put(HawkConfig.DANMU_API, api)
        KV.delete(HawkConfig.DANMU_API_USE_DEFAULT)
        current = current.copy(api = api)
    }

    fun reset() {
        KV.delete(HawkConfig.DANMU_RANDOM_COLOR)
        KV.delete(HawkConfig.DANMU_SPEED)
        KV.delete(HawkConfig.DANMU_SIZE_SCALE)
        KV.delete(HawkConfig.DANMU_MAX_LINE)
        KV.delete(HawkConfig.DANMU_ALPHA)
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
