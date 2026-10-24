package com.github.catvod.crawler

import android.os.SystemClock

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SpiderReaper {

    private const val RETIRE_GRACE_MS = 20_000L

    private const val REAP_PERIOD_MS = 3_000L

    private val inflight = ConcurrentHashMap<Spider, AtomicInteger>()

    private val retired = ConcurrentHashMap<Spider, Long>()

    private val started = AtomicBoolean(false)

    /** 包裹一次真实的爬虫调用，保证期间实例不会被销毁。 */
    fun <T> track(spider: Spider?, block: () -> T): T {
        if (spider == null || spider is SpiderNull) return block()
        begin(spider)
        return try {
            block()
        } finally {
            end(spider)
        }
    }

    fun begin(spider: Spider) {
        inflight.computeIfAbsent(spider) { AtomicInteger() }.incrementAndGet()
    }

    fun end(spider: Spider) {
        val counter = inflight[spider] ?: return
        if (counter.decrementAndGet() <= 0) inflight.remove(spider, counter)
    }

    /** 让 spider 退役，稍后安全销毁。 */
    fun retire(spider: Spider?) {
        if (spider == null || spider is SpiderNull) return
        ensureJanitor()
        retired[spider] = SystemClock.elapsedRealtime()
    }

    private fun ensureJanitor() {
        if (started.compareAndSet(false, true)) {
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "spider-reaper").apply { isDaemon = true }
            }
            scheduler.scheduleWithFixedDelay({ reap() }, REAP_PERIOD_MS, REAP_PERIOD_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun reap() {
        val now = SystemClock.elapsedRealtime()
        for (entry in retired.entries) {
            val spider = entry.key
            val retireAt = entry.value
            if (now - retireAt < RETIRE_GRACE_MS) continue
            if ((inflight[spider]?.get() ?: 0) > 0) continue
            if (!retired.remove(spider, retireAt)) continue
            try {
                spider.destroy()
            } catch (ignored: Throwable) {
            }
            inflight.remove(spider)
        }
    }
}
