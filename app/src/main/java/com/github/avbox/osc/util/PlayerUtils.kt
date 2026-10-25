package com.github.avbox.osc.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Point
import android.os.Build
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager

object PlayerUtils {

    @JvmStatic
    fun stringForTime(timeMs: Int): String {
        var ms = timeMs
        if (ms < 0) ms = 0
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            "$hours:${two(minutes)}:${two(seconds)}"
        } else {
            "${two(minutes)}:${two(seconds)}"
        }
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    @JvmStatic
    fun safeTimeMs(timeMs: Long): Int {
        if (timeMs <= 0) return 0
        if (timeMs > Int.MAX_VALUE) return Int.MAX_VALUE
        return timeMs.toInt()
    }

    @JvmStatic
    fun isEdge(context: Context, e: MotionEvent): Boolean {
        val edgeSize = dp2px(context, 40f)
        return e.rawX < edgeSize ||
            e.rawX > getScreenWidth(context, true) - edgeSize ||
            e.rawY < edgeSize ||
            e.rawY > getScreenHeight(context, true) - edgeSize
    }

    @JvmStatic
    fun scanForActivity(context: Context?): Activity? {
        if (context == null) return null
        if (context is Activity) return context
        if (context is ContextWrapper) return scanForActivity(context.baseContext)
        return null
    }

    @JvmStatic
    fun getScreenWidth(context: Context, isIncludeNav: Boolean): Int {
        return if (isIncludeNav) {
            context.resources.displayMetrics.widthPixels + navigationBarHeight(context)
        } else {
            context.resources.displayMetrics.widthPixels
        }
    }

    @JvmStatic
    fun getScreenHeight(context: Context, isIncludeNav: Boolean): Int {
        return if (isIncludeNav) {
            context.resources.displayMetrics.heightPixels + navigationBarHeight(context)
        } else {
            context.resources.displayMetrics.heightPixels
        }
    }

    private fun navigationBarHeight(context: Context): Int {
        if (!hasNavigationBar(context)) return 0
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return resources.getDimensionPixelSize(resourceId)
    }

    private fun hasNavigationBar(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val display = getWindowManager(context).defaultDisplay
            val size = Point()
            val realSize = Point()
            display.getSize(size)
            display.getRealSize(realSize)
            return realSize.x != size.x || realSize.y != size.y
        } else {
            val menu = ViewConfiguration.get(context).hasPermanentMenuKey()
            val back = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK)
            return !(menu || back)
        }
    }

    private fun getWindowManager(context: Context): WindowManager {
        return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @JvmStatic
    fun dp2px(context: Context, dpValue: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpValue,
            context.resources.displayMetrics,
        ).toInt()
    }
}
