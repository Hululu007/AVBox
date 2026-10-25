package com.github.avbox.osc.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager

object CutoutUtil {

    @JvmStatic
    fun adaptCutoutAboveAndroidP(context: Context, isAdapt: Boolean) {
        val activity: Activity = PlayerUtils.scanForActivity(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp: WindowManager.LayoutParams = activity.window.attributes
            lp.layoutInDisplayCutoutMode = if (isAdapt) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            activity.window.attributes = lp
        }
    }
}
