package com.github.avbox.osc.player

object PreloadCachePolicy {
    const val TTL_MS = 180_000L
    const val MAX_ENTRIES = 5

    @JvmStatic
    fun isExpired(nowMs: Long, cachedAtMs: Long): Boolean = nowMs - cachedAtMs > TTL_MS

    @JvmStatic
    fun sizeExceeded(size: Int): Boolean = size > MAX_ENTRIES
}
