package com.github.avbox.osc.util

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(launch) }.isFailure) return
    Process.killProcess(Process.myPid())
    exitProcess(0)
}
