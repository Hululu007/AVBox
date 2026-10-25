package com.github.avbox.osc.player.controller

import android.content.Context

import com.github.avbox.osc.R
import com.github.avbox.osc.player.BasePlayerView
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.LanguageManager

import java.text.DecimalFormat

object PlayerTextFormat {

    @JvmStatic
    fun renderName(renderType: Int): String {
        return if (renderType == 1) {
            "SurfaceView"
        } else {
            "TextureView"
        }
    }

    @JvmStatic
    fun scaleName(screenScaleType: Int): String {
        return when (screenScaleType) {
            BasePlayerView.SCREEN_SCALE_16_9 -> "16:9"
            BasePlayerView.SCREEN_SCALE_4_3 -> "4:3"
            BasePlayerView.SCREEN_SCALE_MATCH_PARENT -> str(R.string.player_scale_fill)
            BasePlayerView.SCREEN_SCALE_ORIGINAL -> str(R.string.player_scale_origin)
            BasePlayerView.SCREEN_SCALE_CENTER_CROP -> str(R.string.player_scale_crop)
            else -> str(R.string.common_default)
        }
    }

    @JvmStatic
    fun displaySpeed(speed: Long, show: Boolean): String {
        return if (speed > 1048576)
            DecimalFormat("#.00").format(speed / 1048576.0) + "MB/s"
        else if (speed > 1024)
            (speed / 1024).toString() + "KB/s"
        else
            if (speed > 0) speed.toString() + "B/s" else (if (show) "0B/s" else "")
    }

    private fun str(resId: Int): String {
        val app: Context? = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId)
    }
}
