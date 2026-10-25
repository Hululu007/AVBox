package com.github.avbox.osc.boot

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList

object BootGuard {

    private const val QUICK_CRASH_MS = 10_000L

    private const val ATTEMPT_WINDOW_MS = 10 * 60_000L

    private const val STABLE_RUN_MS = 10 * 60_000L

    private const val MAX_LOAD_ATTEMPTS = 3

    private const val EXIT_CRASH_WINDOW_MS = 8_000L

    private const val ENTRY_SEPARATOR = '\t'

    private const val CRASH_MARKER_NAME = "boot_crash.marker"

    const val SCOPE_VOD = "vod"

    const val SCOPE_LIVE = "live"

    private val IGNORABLE_FRAME_PREFIXES = arrayOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.", "libcore.",
            "com.google.android.", "com.android.internal.", "com.github.avbox.osc."
    )

    private const val MAX_THROWABLE_CHAIN = 32

    @JvmStatic
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (looksSourceRelatedSafely(throwable)) {
                writeCrashMarker()
            } else {
                AppLog.i("echo-boot-guard: crash unrelated to source, marker skipped")
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashMarker() {
        try {
            val file = crashMarkerFile()
            if (file == null) return
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(file).use { out ->
                out.write(java.lang.String.valueOf(SystemClock.elapsedRealtime()).toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun looksSourceRelated(throwable: Throwable?): Boolean {
        if (throwable == null) return true
        val chain = ArrayList<Throwable>()
        collectThrowables(throwable, chain)
        var sawFrame = false
        for (item in chain) {
            val frames = item.stackTrace
            if (frames == null) continue
            for (frame in frames) {
                if (frame == null) continue
                sawFrame = true
                val className = frame.className
                if (className == null || !isIgnorableFrame(className)) return true
            }
        }
        return !sawFrame
    }

    private fun looksSourceRelatedSafely(throwable: Throwable?): Boolean {
        try {
            return looksSourceRelated(throwable)
        } catch (ignored: Throwable) {
            return true
        }
    }

    private fun collectThrowables(throwable: Throwable?, out: ArrayList<Throwable>) {
        if (throwable == null || out.size >= MAX_THROWABLE_CHAIN || out.contains(throwable)) return
        out.add(throwable)
        try {
            for (suppressed in throwable.suppressed) collectThrowables(suppressed, out)
        } catch (ignored: Throwable) {
        }
        collectThrowables(throwable.cause, out)
    }

    private fun isIgnorableFrame(className: String): Boolean {
        for (prefix in IGNORABLE_FRAME_PREFIXES) {
            if (className.startsWith(prefix)) return true
        }
        return false
    }

    private fun crashMarkerFile(): File? {
        try {
            return File(requireNotNull(AppContextHolder.context()) { "app context not initialized" }.filesDir, CRASH_MARKER_NAME)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun takeCrashMarkerElapsed(): Long {
        try {
            val file = crashMarkerFile()
            if (file == null || !file.exists()) return -1L
            val buf = ByteArray(32)
            var len = 0
            FileInputStream(file).use { input ->
                len = input.read(buf)
            }
            var value = -1L
            if (len > 0) {
                try {
                    value = java.lang.Long.parseLong(String(buf, 0, len, StandardCharsets.UTF_8).trim { it <= ' ' })
                } catch (ignored: NumberFormatException) {
                    value = -1L
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete()
            return value
        } catch (e: Throwable) {
            return -1L
        }
    }

    @JvmStatic
    fun onJarLoadStart(jarUrl: String, scope: String?) {
        try {
            if (isEmpty(jarUrl)) return
            if (!isEmpty(scope)) KV.put(KVKeys.BOOT_LOADING_SCOPE, scope)
            recordCurrentSource()
            val now = System.currentTimeMillis()
            KV.put(KVKeys.BOOT_LOAD_START_ELAPSED, SystemClock.elapsedRealtime())
            val previous = KV.get(KVKeys.BOOT_LOADING_JAR, "")
            val lastAttemptAt = KV.get(KVKeys.BOOT_LAST_ATTEMPT_AT, 0L)
            val stale = lastAttemptAt > 0 && now - lastAttemptAt > ATTEMPT_WINDOW_MS
            val count = if (previous == jarUrl && !stale) KV.get(KVKeys.BOOT_LOADING_COUNT, 0L) + 1L else 1L
            KV.put(KVKeys.BOOT_LOADING_COUNT, count)
            KV.put(KVKeys.BOOT_LOADING_JAR, jarUrl)
            KV.put(KVKeys.BOOT_LAST_ATTEMPT_AT, now)
        } catch (ignored: Throwable) {
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val stableReset = Runnable {
        try {
            KV.put(KVKeys.BOOT_LOADING_JAR, "")
            KV.put(KVKeys.BOOT_LOADING_COUNT, 0L)
            KV.put(KVKeys.BOOT_LOADING_SCOPE, "")
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun scheduleStableRunReset() {
        try {
            mainHandler.removeCallbacks(stableReset)
            mainHandler.postDelayed(stableReset, STABLE_RUN_MS)
        } catch (ignored: Throwable) {
        }
    }

    private fun recordCurrentSource() {
        val vod = KV.get(KVKeys.API_URL, "")
        val live = KV.get(KVKeys.LIVE_API_URL, "")
        KV.put(KVKeys.BOOT_VOD_SOURCE, resolveSourceUrl(
                vod, KV.get(KVKeys.API_LINE_SOURCE, ""), HistoryHelper.isApiLineUrl(vod)))
        KV.put(KVKeys.BOOT_LIVE_SOURCE, resolveSourceUrl(
                live, KV.get(KVKeys.LIVE_API_LINE_SOURCE, ""), HistoryHelper.isLiveApiLineUrl(live)))
    }

    @JvmStatic
    fun resolveSourceUrl(active: String?, lineSource: String?, activeIsLine: Boolean): String {
        val value = active ?: ""
        val source = lineSource ?: ""
        if (!activeIsLine || source.isEmpty()) return value
        return source
    }

    @JvmStatic
    fun disableBootLoopingSource(): String {
        try {
            val loading = KV.get(KVKeys.BOOT_LOADING_JAR, "")
            if (isEmpty(loading)) {
                KV.put(KVKeys.BOOT_LOADING_COUNT, 0L)
                return ""
            }
            val jar = loading ?: return ""
            val crashElapsed = takeCrashMarkerElapsed()
            val count = KV.get(KVKeys.BOOT_LOADING_COUNT, 0L)
            val loadStartElapsed = KV.get(KVKeys.BOOT_LOAD_START_ELAPSED, 0L)
            val startupCrash = crashedDuringStartup(crashElapsed, loadStartElapsed)
            val nativeCrash = crashElapsed <= 0 && exitedWithCrashSinceJarLoad()
            val looping = startupCrash || nativeCrash || (crashElapsed > 0 && count >= MAX_LOAD_ATTEMPTS)
            if (!looping) return ""
            AppLog.i("echo-boot-guard: disable looping jar attempt=" + count
                    + " startupCrash=" + startupCrash + " nativeCrash=" + nativeCrash + " jar=" + jar)
            val disabled = disableRecordedSource(jar)
            KV.put(KVKeys.BOOT_LOADING_JAR, "")
            KV.put(KVKeys.BOOT_LOADING_COUNT, 0L)
            KV.put(KVKeys.BOOT_LOAD_START_ELAPSED, 0L)
            KV.put(KVKeys.BOOT_LOADING_SCOPE, "")
            return if (disabled) jar else ""
        } catch (e: Throwable) {
            AppLog.i("echo-boot-guard failed: " + e.message)
            return ""
        }
    }

    private fun exitedWithCrashSinceJarLoad(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val lastAttempt = KV.get(KVKeys.BOOT_LAST_ATTEMPT_AT, 0L)
        if (lastAttempt <= 0) return false
        val context = AppContextHolder.context() ?: return false
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val exits = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        } catch (e: Throwable) {
            return false
        }
        if (exits == null) return false
        for (exit in exits) {
            if (exit == null) continue
            if (!withinLoadWindow(exit.timestamp, lastAttempt)) continue
            if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) return true
        }
        return false
    }

    @JvmStatic
    fun shouldDisable(jar: String?, count: Long, crashElapsed: Long, startupCrash: Boolean): Boolean {
        if (isEmpty(jar) || crashElapsed <= 0) return false
        return startupCrash || count >= MAX_LOAD_ATTEMPTS
    }

    @JvmStatic
    fun crashedDuringStartup(crashElapsed: Long, loadStartElapsed: Long): Boolean {
        return loadStartElapsed > 0 && crashElapsed >= loadStartElapsed
                && crashElapsed - loadStartElapsed <= QUICK_CRASH_MS
    }

    @JvmStatic
    fun withinLoadWindow(exitedAt: Long, lastAttempt: Long): Boolean {
        if (lastAttempt <= 0 || exitedAt < lastAttempt) return false
        return exitedAt - lastAttempt <= EXIT_CRASH_WINDOW_MS
    }

    @JvmStatic
    fun pickSource(scope: String?, liveSource: String?, vodSource: String?): String {
        val live = liveSource ?: ""
        val vod = vodSource ?: ""
        return when {
            scope == SCOPE_LIVE -> if (!live.isEmpty()) live else vod
            scope == SCOPE_VOD -> if (!vod.isEmpty()) vod else live
            !live.isEmpty() -> live
            else -> vod
        }
    }

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

    private fun disableRecordedSource(jar: String): Boolean {
        val scope = KV.get(KVKeys.BOOT_LOADING_SCOPE, "")
        val liveSource = KV.get(KVKeys.BOOT_LIVE_SOURCE, "")
        val vodSource = KV.get(KVKeys.BOOT_VOD_SOURCE, "")
        val source = pickSource(scope, liveSource, vodSource)
        val recorded = !isEmpty(source)
        if (recorded) {
            rememberDisabled(source, jar)
            KV.put(KVKeys.BOOT_SAFE_DISABLED, source)
        } else {
            AppLog.i("echo-boot-guard: no source recorded, jar stays enabled")
        }
        KV.put(KVKeys.BOOT_VOD_SOURCE, "")
        KV.put(KVKeys.BOOT_LIVE_SOURCE, "")
        return recorded
    }

    @JvmStatic
    fun takeSafeDisabledNotice(): String {
        try {
            val url = KV.get(KVKeys.BOOT_SAFE_DISABLED, "")
            if (!url.isEmpty()) KV.put(KVKeys.BOOT_SAFE_DISABLED, "")
            return url
        } catch (ignored: Throwable) {
            return ""
        }
    }

    @JvmStatic
    fun isDisabledSource(url: String?): Boolean {
        if (isEmpty(url)) return false
        val value = url ?: return false
        try {
            return disabledSources().contains(value.trim { it <= ' ' })
        } catch (ignored: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun disabledSources(): ArrayList<String> {
        try {
            return ArrayList<String>(KV.get(KVKeys.BOOT_DISABLED_SOURCES, ArrayList<String>()))
        } catch (ignored: Throwable) {
            return ArrayList()
        }
    }

    @JvmStatic
    fun disabledJars(): ArrayList<String> {
        val jars = ArrayList<String>()
        try {
            for (entry in disabledJarEntries()) {
                val jar = entryJar(entry)
                if (!jar.isEmpty() && !jars.contains(jar)) jars.add(jar)
            }
        } catch (ignored: Throwable) {
        }
        return jars
    }

    @JvmStatic
    fun isJarDisabled(path: String?): Boolean {
        if (isEmpty(path)) return false
        val value = path ?: return false
        return disabledJars().contains(value.trim { it <= ' ' })
    }

    @JvmStatic
    fun enableSource(url: String?) {
        if (isEmpty(url)) return
        try {
            KV.put(KVKeys.BOOT_DISABLED_SOURCES, removeDisabledSource(disabledSources(), url))
            KV.put(KVKeys.BOOT_DISABLED_JARS, removeDisabledJar(disabledJarEntries(), url))
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun forgetSources(urls: Collection<String>?) {
        if (urls == null || urls.isEmpty()) return
        try {
            var list = disabledSources()
            var jars = disabledJarEntries()
            for (url in urls) {
                list = removeDisabledSource(list, url)
                jars = removeDisabledJar(jars, url)
            }
            KV.put(KVKeys.BOOT_DISABLED_SOURCES, list)
            KV.put(KVKeys.BOOT_DISABLED_JARS, jars)
        } catch (ignored: Throwable) {
        }
    }

    private fun rememberDisabled(url: String?, jar: String?) {
        try {
            if (!isEmpty(url)) KV.put(KVKeys.BOOT_DISABLED_SOURCES, addDisabledSource(disabledSources(), url))
            if (!isEmpty(jar)) KV.put(KVKeys.BOOT_DISABLED_JARS, addDisabledJar(disabledJarEntries(), url, jar))
        } catch (ignored: Throwable) {
        }
    }

    private fun disabledJarEntries(): ArrayList<String> {
        try {
            return ArrayList<String>(KV.get(KVKeys.BOOT_DISABLED_JARS, ArrayList<String>()))
        } catch (ignored: Throwable) {
            return ArrayList()
        }
    }

    private fun entryJar(entry: String?): String {
        if (entry == null) return ""
        val index = entry.indexOf(ENTRY_SEPARATOR)
        return if (index < 0) "" else entry.substring(index + 1).trim { it <= ' ' }
    }

    private fun entryUrl(entry: String?): String {
        if (entry == null) return ""
        val index = entry.indexOf(ENTRY_SEPARATOR)
        return if (index < 0) entry.trim { it <= ' ' } else entry.substring(0, index).trim { it <= ' ' }
    }

    @JvmStatic
    fun addDisabledJar(list: ArrayList<String>?, url: String?, jar: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(jar)) return next
        val path = jar?.trim { it <= ' ' } ?: return next
        val entry = (url ?: "").trim { it <= ' ' } + ENTRY_SEPARATOR + path
        if (!next.contains(entry)) next.add(entry)
        return next
    }

    @JvmStatic
    fun removeDisabledJar(list: ArrayList<String>?, url: String?): ArrayList<String> {
        if (list == null) return ArrayList()
        if (isEmpty(url)) return ArrayList<String>(list)
        val target = url?.trim { it <= ' ' } ?: return ArrayList<String>(list)
        val next = ArrayList<String>()
        for (entry in list) {
            if (entryUrl(entry) == target) continue
            next.add(entry)
        }
        return next
    }

    @JvmStatic
    fun addDisabledSource(list: ArrayList<String>?, url: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(url)) return next
        val value = url?.trim { it <= ' ' } ?: return next
        if (!next.contains(value)) next.add(value)
        return next
    }

    @JvmStatic
    fun removeDisabledSource(list: ArrayList<String>?, url: String?): ArrayList<String> {
        val next = ArrayList<String>(if (list == null) ArrayList<String>() else list)
        if (isEmpty(url)) return next
        next.remove(url?.trim { it <= ' ' } ?: return next)
        return next
    }
}
