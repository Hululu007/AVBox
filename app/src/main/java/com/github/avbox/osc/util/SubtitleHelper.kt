package com.github.avbox.osc.util

import android.app.Activity

object SubtitleHelper {

    @JvmStatic
    fun subtitleTextAutoSize(activity: Activity?): Int {
        val screenSqrt = ScreenUtils.getSqrt(activity ?: return 16)
        var subtitleTextSize = 16
        if (screenSqrt > 7.0 && screenSqrt <= 13.0) {
            subtitleTextSize = 24
        } else if (screenSqrt > 13.0 && screenSqrt <= 50.0) {
            subtitleTextSize = 36
        } else if (screenSqrt > 50.0) {
            subtitleTextSize = 46
        }
        return subtitleTextSize
    }

    @JvmStatic
    fun getTextSize(activity: Activity?): Int {
        val autoSize = subtitleTextAutoSize(activity)
        val subtitleConfigSize = KV.get(KVKeys.SUBTITLE_TEXT_SIZE, autoSize)
        return subtitleConfigSize
    }

    @JvmStatic
    fun setTextSize(size: Int) {
        KV.put(KVKeys.SUBTITLE_TEXT_SIZE, size)
    }

    @JvmStatic
    var timeDelay: Int
        get() = KV.get(KVKeys.SUBTITLE_TIME_DELAY, 0)
        set(value) {
            KV.put(KVKeys.SUBTITLE_TIME_DELAY, value)
        }

    @JvmStatic
    var exoSubtitleScale: Int
        get() = KV.get(KVKeys.SUBTITLE_EXO_SCALE, 100)
        set(value) {
            KV.put(KVKeys.SUBTITLE_EXO_SCALE, value)
        }

    @JvmStatic
    var exoSubtitlePosition: Float
        get() = KV.get(KVKeys.SUBTITLE_EXO_POSITION, 0.0f)
        set(value) {
            KV.put(KVKeys.SUBTITLE_EXO_POSITION, value)
        }

    @JvmStatic
    fun reset() {
        KV.delete(KVKeys.SUBTITLE_TEXT_SIZE)
        KV.delete(KVKeys.SUBTITLE_TIME_DELAY)
        KV.delete(KVKeys.SUBTITLE_TEXT_STYLE)
        KV.delete(KVKeys.SUBTITLE_EXO_SCALE)
        KV.delete(KVKeys.SUBTITLE_EXO_POSITION)
    }

}
