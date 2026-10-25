package com.github.avbox.osc.util

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenUtils {

    @JvmStatic
    fun getSqrt(activity: Activity): Double {
        val wm: WindowManager = activity.windowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getMetrics(dm)
        val x = Math.pow((dm.widthPixels / dm.xdpi).toDouble(), 2.0)
        val y = Math.pow((dm.heightPixels / dm.ydpi).toDouble(), 2.0)
        val screenInches = Math.sqrt(x + y)
        return screenInches
    }

    @JvmStatic
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager?
        return uiModeManager != null
                && uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
