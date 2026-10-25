package com.github.avbox.osc.util

import android.os.SystemClock
import android.os.Trace

import com.github.avbox.osc.BuildConfig

import java.util.concurrent.atomic.AtomicInteger

object Probe {

    private val frameKvCalls = AtomicInteger()

    @JvmStatic
    fun <T> step(label: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val startedAt = SystemClock.elapsedRealtime()
        val result = block()
        AppLog.i("echo-boot " + label + " dt=" + (SystemClock.elapsedRealtime() - startedAt) + "ms")
        return result
    }

    @JvmStatic
    fun countKvCall() {
        if (!BuildConfig.DEBUG) return
        frameKvCalls.incrementAndGet()
    }

    @JvmStatic
    fun measure(owner: Any, label: String, block: () -> Unit) {
        if (!BuildConfig.DEBUG) {
            block()
            return
        }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val callsBefore = frameKvCalls.get()
        try {
            block()
        } finally {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1000000.0
            val calls = frameKvCalls.get() - callsBefore
            AppLog.i("echo-frame kv:" + owner.javaClass.simpleName + "." + label + " dt=" + elapsedMs + "ms calls=" + calls)
        }
    }

    private inline fun <T> span(section: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        Trace.beginSection(section)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    @JvmStatic
    fun trace(section: String, block: () -> Unit) {
        span(section, block)
    }

    @JvmStatic
    fun <T> call(section: String, block: () -> T): T {
        return span(section, block)
    }
}
