package com.github.avbox.osc.api

import java.util.concurrent.atomic.AtomicLong

internal class LoadEpoch {

    private val latest = AtomicLong(0)

    fun next(): Long = latest.incrementAndGet()

    fun current(): Long = latest.get()

    fun isStale(snapshot: Long): Boolean = snapshot != latest.get()
}
