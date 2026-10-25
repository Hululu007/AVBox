package com.github.avbox.osc.util

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast

import com.github.avbox.osc.R

import java.lang.ref.WeakReference

object SpiderStorageNotice {

    private const val SUPPRESS_MS = 3_000L

    private var paused: WeakReference<Activity>? = null
    private var pending = false
    private var shown = false

    @Volatile
    private var suppressedAt = 0L

    fun suppress() {
        suppressedAt = SystemClock.elapsedRealtime()
    }

    private fun suppressedRecently(): Boolean =
        suppressedAt != 0L && SystemClock.elapsedRealtime() - suppressedAt <= SUPPRESS_MS

    fun onHostPaused(activity: Activity) {
        paused = WeakReference(activity)
        pending = !suppressedRecently() &&
            !PermissionHelper.hostAskedStorageRecently() &&
            SpiderInitWatch.inWindow()
    }

    fun onHostResumed(activity: Activity) {
        val same = paused?.get() === activity
        paused = null
        if (!pending || !same) {
            pending = false
            return
        }
        pending = false
        if (shown) return
        if (activity.isFinishing) return
        if (PermissionHelper.isStorageGranted(activity)) return
        shown = true
        AppLog.i("echo-storage-asked-by-spider")
        Toast.makeText(activity, R.string.toast_storage_asked_by_spider, Toast.LENGTH_LONG).show()
    }
}
