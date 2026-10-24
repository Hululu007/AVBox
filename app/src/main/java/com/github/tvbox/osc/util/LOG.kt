package com.github.tvbox.osc.util

import android.util.Log

import com.github.tvbox.osc.BuildConfig

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object LOG {
    private var TAG = "TVBox-runtime"
    private const val MAX_LOG_LENGTH = 3000

    private val FILE_LOG = BuildConfig.DEBUG
    private val FILE_LOG_PREFIXES = arrayOf("echo-preload", "echo-setDataSource", "echo-play-cache", "echo-kv", "echo-progress", "echo-bar-draw", "echo-exo", "echo-music", "echo-lyric", "echo-sub", "echo-danmu", "echo-p2", "echo-p3", "echo-p4", "echo-p5", "clearCache", "echo--jar", "echo-local-src", "echo-setTrack", "echo-autoRetry", "echo-player", "echo-switch", "echo-goPlayUrl", "echo-history", "echo-iso", "echo-render", "echo-surface", "echo-output-size", "echo-picture", "echo-anime4k", "echo--list", "echo--getList", "echo--parse", "echo--getSort", "echo--sort", "echo-detail", "echo-thunder", "echo-proxy", "echo-home-backdrop", "echo-cast", "echo-resolvePlayUrl", "echo-playM3u8", "echo-tmdb", "echo-live", "echo-load", "echo-zero-start", "echo-storage", "echo-config-refresh", "echo-boot")
    private const val FILE_LOG_NAME = "preload_debug.log"
    private const val FILE_LOG_BACKUP = ".1"
    internal const val FILE_LOG_MAX_BYTES = 8L * 1024 * 1024
    private var fileLogExecutor: ExecutorService? = null

    internal fun shouldRotateLog(sizeBytes: Long): Boolean = sizeBytes >= FILE_LOG_MAX_BYTES

    private fun fileLog(level: String, msg: String?) {
        if (!FILE_LOG || msg == null) return
        var match = false
        for (prefix in FILE_LOG_PREFIXES) {
            if (msg.startsWith(prefix)) {
                match = true
                break
            }
        }
        if (!match) return
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " " + level + " " + msg
        synchronized(LOG::class.java) {
            if (fileLogExecutor == null) fileLogExecutor = Executors.newSingleThreadExecutor()
        }
        val executor = fileLogExecutor
        try {
            executor?.execute {
                try {
                    val dir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.filesDir
                    val file = File(dir, FILE_LOG_NAME)
                    if (shouldRotateLog(file.length())) {
                        val backup = File(dir, FILE_LOG_NAME + FILE_LOG_BACKUP)
                        if (backup.exists()) backup.delete()
                        file.renameTo(backup)
                    }
                    FileWriter(file, true).use { writer ->
                        writer.write(line + "\n")
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun e(msg: String?) {
        Log.e(TAG, "" + msg)
        fileLog("E", msg.toString())
    }

    @JvmStatic
    fun i(msg: String?) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "" + msg)
        fileLog("I", msg.toString())
    }

    @JvmStatic
    fun debugI(msg: String?) {
        if (!BuildConfig.DEBUG) return
        i(msg)
    }

    @JvmStatic
    fun d(tag: String?, msg: String?) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, tag + ": " + msg)
        fileLog("D", msg.toString())
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?) {
        Log.e(TAG, tag + ": " + msg, tr)
        fileLog("E", msg.toString())
        if (tr != null) tr.printStackTrace()
    }

    @JvmStatic
    fun e(tag: String?, tr: Throwable?) {
        e(tag, tr?.toString() ?: "null", tr)
    }

    @JvmStatic
    fun longI(prefix: String?, msg: String?) {
        if (!BuildConfig.DEBUG) return
        longLog(Log.INFO, prefix, msg)
    }

    @JvmStatic
    fun longE(prefix: String?, msg: String?) {
        longLog(Log.ERROR, prefix, msg)
    }

    private fun longLog(priority: Int, prefix: String?, msg: String?) {
        val text = msg ?: "null"
        val title = prefix ?: ""
        val length = text.length
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text)
            return
        }
        val count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH
        for (i in 0 until count) {
            val start = i * MAX_LOG_LENGTH
            val end = Math.min(start + MAX_LOG_LENGTH, length)
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end))
        }
    }
}
