package com.github.tvbox.osc.api

import java.util.concurrent.atomic.AtomicLong

internal class BootGeneration {

    private val latest = AtomicLong(0)

    fun next(): Long = latest.incrementAndGet()

    fun isLatest(generation: Long): Boolean = generation == latest.get()
}
