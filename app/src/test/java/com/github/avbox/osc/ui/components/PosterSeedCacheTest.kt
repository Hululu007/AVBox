package com.github.avbox.osc.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterSeedCacheTest {

    @Test
    fun evictsLeastRecentlyUsedBeyondCapacity() {
        val cache = PosterSeedCache(capacity = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")
        cache.put("c", 3)
        assertTrue(cache.has("a"))
        assertFalse(cache.has("b"))
        assertTrue(cache.has("c"))
    }

    @Test
    fun cachesNullSeedAsKnownMiss() {
        val cache = PosterSeedCache(capacity = 4)
        cache.put("a", null)
        assertTrue(cache.has("a"))
        assertNull(cache.get("a"))
    }

    @Test
    fun unknownKeyReportsAbsent() {
        val cache = PosterSeedCache(capacity = 4)
        assertFalse(cache.has("x"))
        assertNull(cache.get("x"))
    }

    @Test
    fun putOverwritesExistingKey() {
        val cache = PosterSeedCache(capacity = 4)
        cache.put("a", 1)
        cache.put("a", null)
        assertTrue(cache.has("a"))
        assertNull(cache.get("a"))
    }
}
