package com.github.avbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadCachePolicyTest {

    @Test
    fun freshEntryNotExpired() {
        assertFalse(PreloadCachePolicy.isExpired(nowMs = 1_000L, cachedAtMs = 0L))
    }

    @Test
    fun boundaryAtTtlStillValid() {
        assertFalse(PreloadCachePolicy.isExpired(PreloadCachePolicy.TTL_MS, 0L))
    }

    @Test
    fun beyondTtlExpired() {
        assertTrue(PreloadCachePolicy.isExpired(PreloadCachePolicy.TTL_MS + 1, 0L))
    }

    @Test
    fun capacityBoundary() {
        assertFalse(PreloadCachePolicy.sizeExceeded(PreloadCachePolicy.MAX_ENTRIES))
        assertTrue(PreloadCachePolicy.sizeExceeded(PreloadCachePolicy.MAX_ENTRIES + 1))
    }
}
