package com.github.tvbox.osc.util

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

/**
 * 应用自重启:先 `startActivity`(同步 binder,返回即已在 AMS 登记)再杀本进程,AMS 会在新进程里恢复它。
 * ⚠️ 别用 `AlarmManager` 等触发(实测黑屏 + 重启两次)/`CLEAR_TASK`(AMS 会失去 ActivityRecord);
 * 调用方须**先把要持久化的值写完再调本方法**(KV 是 mmap 同步写,但要给弹窗交互留出时间,见 LanguageRow)。
 */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(launch) }.isFailure) return
    Process.killProcess(Process.myPid())
    exitProcess(0)
}
