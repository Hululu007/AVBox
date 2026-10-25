package com.github.avbox.osc.player.engine.iso

import com.github.avbox.osc.util.AppLog
import java.util.concurrent.atomic.AtomicLong

internal class IsoThroughputWindow(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val buckets = LongArray(BUCKET_COUNT)
    private val epochs = LongArray(BUCKET_COUNT) { NO_EPOCH }
    private var firstReadAt = NO_EPOCH
    private var lastReadAt = NO_EPOCH

    fun reset() {
        synchronized(lock) {
            buckets.fill(0)
            epochs.fill(NO_EPOCH)
            firstReadAt = NO_EPOCH
            lastReadAt = NO_EPOCH
        }
    }

    fun onRead(bytes: Int) {
        if (bytes <= 0) return
        val now = clock()
        synchronized(lock) {
            if (firstReadAt == NO_EPOCH) firstReadAt = now
            lastReadAt = now
            val epoch = now / BUCKET_MS
            val slot = (epoch % BUCKET_COUNT).toInt()
            if (epochs[slot] != epoch) {
                epochs[slot] = epoch
                buckets[slot] = 0
            }
            buckets[slot] += bytes.toLong()
        }
    }

    fun hasReads(): Boolean = synchronized(lock) { firstReadAt != NO_EPOCH }

    fun lastReadAgeMs(): Long = synchronized(lock) {
        if (lastReadAt == NO_EPOCH) -1L else clock() - lastReadAt
    }

    fun bytesPerSecond(): Long {
        val now = clock()
        synchronized(lock) {
            if (firstReadAt == NO_EPOCH) return 0
            val start = maxOf(firstReadAt, now - WINDOW_MS)
            val span = now - start
            if (span < MIN_SPAN_MS) return 0
            var sum = 0L
            for (index in buckets.indices) {
                val epoch = epochs[index]
                if (epoch == NO_EPOCH) continue
                val at = epoch * BUCKET_MS
                if (at <= now && at + BUCKET_MS > start) sum += buckets[index]
            }
            return sum * MILLIS_PER_SECOND / span
        }
    }

    private companion object {
        const val BUCKET_MS = 250L
        const val BUCKET_COUNT = 16
        const val WINDOW_MS = BUCKET_MS * BUCKET_COUNT
        const val MIN_SPAN_MS = 1500L
        const val MILLIS_PER_SECOND = 1000L
        const val NO_EPOCH = Long.MIN_VALUE
    }
}

internal class IsoBandwidthLogger(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var lastLogAt = 0L
    private var logged = 0

    @Synchronized
    fun reset() {
        lastLogAt = 0L
        logged = 0
    }

    @Synchronized
    fun due(): Boolean {
        if (logged >= MAX_LOGS) return false
        if (lastLogAt == 0L) return true
        return clock() - lastLogAt >= INTERVAL_MS
    }

    @Synchronized
    fun record(
        bytesPerSecond: Long,
        averageBytesPerSecond: Long,
        requiredBytesPerSecond: Long,
    ): String? {
        if (bytesPerSecond <= 0L) return null
        if (logged >= MAX_LOGS) return null
        val now = clock()
        if (lastLogAt != 0L && now - lastLogAt < INTERVAL_MS) return null
        lastLogAt = now
        logged++
        return "echo-iso-bps:$bytesPerSecond:$averageBytesPerSecond:$requiredBytesPerSecond"
    }

    private companion object {
        const val INTERVAL_MS = 10_000L
        const val MAX_LOGS = 60
    }
}

object IsoThroughput {

    private val window = IsoThroughputWindow()
    private val bandwidthLog = IsoBandwidthLogger()
    private val sessionBytes = AtomicLong()

    @Volatile
    var targetLength = 0L

    @Volatile
    private var sessionStart = 0L

    fun beginSession() {
        window.reset()
        bandwidthLog.reset()
        sessionBytes.set(0L)
        sessionStart = 0L
        targetLength = 0L
    }

    fun onRead(bytes: Int) {
        window.onRead(bytes)
        if (bytes <= 0) return
        val now = System.currentTimeMillis()
        if (sessionStart == 0L) sessionStart = now
        sessionBytes.addAndGet(bytes.toLong())
        if (!bandwidthLog.due()) return
        val line = bandwidthLog.record(
            window.bytesPerSecond(),
            averageBytesPerSecond(now),
            isoRequiredBytesPerSecond(effectiveIsoBytes(), IsoPlaylistState.durationMs()),
        ) ?: return
        AppLog.i(line)
    }

    private fun averageBytesPerSecond(now: Long): Long {
        val start = sessionStart
        if (start == 0L) return 0L
        val span = now - start
        if (span < MIN_AVERAGE_SPAN_MS) return 0L
        return sessionBytes.get() * MILLIS_PER_SECOND / span
    }

    private const val MIN_AVERAGE_SPAN_MS = 3_000L
    private const val MILLIS_PER_SECOND = 1_000L

    fun bytesPerSecond(): Long = window.bytesPerSecond()

    fun hasReads(): Boolean = window.hasReads()

    fun lastReadAgeMs(): Long = window.lastReadAgeMs()

    fun targetLength(): Long = targetLength
}
