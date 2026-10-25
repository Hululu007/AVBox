package com.github.avbox.osc.player.core

import java.util.concurrent.atomic.AtomicLong

object SlowOpenSignal {

    private val bytes = AtomicLong(0L)

    @Volatile
    private var armed: Boolean = false

    fun reset() {
        bytes.set(0L)
        armed = false
    }

    fun arm() {
        bytes.set(0L)
        armed = true
    }

    fun disarm() {
        armed = false
    }

    fun note(readBytes: Int) {
        if (readBytes > 0 && armed) bytes.addAndGet(readBytes.toLong())
    }

    fun bytes(): Long = bytes.get()

    fun shouldExtend(started: Boolean, alreadyExtended: Boolean): Boolean =
        !started && !alreadyExtended && bytes.get() > 0
}
