package com.github.avbox.osc.sourcedata

import com.github.avbox.osc.bean.AbsSortXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRuntimeStateTest {

    private fun put(key: String) {
        synchronized(SourceRuntimeState.sortCache) {
            SourceRuntimeState.sortCache[key] = AbsSortXml()
        }
    }

    private fun get(key: String): AbsSortXml? = synchronized(SourceRuntimeState.sortCache) {
        SourceRuntimeState.sortCache[key]
    }

    @Test
    fun sortCacheKeepsOnlyFiveEntries() {
        SourceRuntimeState.clearRuntimeCache()
        (1..5).forEach { put("src$it") }
        assertSize(5)
        put("src6")
        assertSize(5)
        assertNull(get("src1"))
        assertTrue(get("src6") != null)
    }

    @Test
    fun sortCacheGetRefreshesRecency() {
        SourceRuntimeState.clearRuntimeCache()
        (1..5).forEach { put("src$it") }
        assertSame(SourceRuntimeState.sortCache["src1"], get("src1"))
        put("src6")
        assertTrue(get("src1") != null)
        assertNull(get("src2"))
    }

    @Test
    fun clearRuntimeCacheEmptiesBothCaches() {
        put("src1")
        SourceRuntimeState.extendCache["extend-key"] = "{}"
        SourceRuntimeState.clearRuntimeCache()
        assertSize(0)
        assertEquals(0, SourceRuntimeState.extendCache.size)
        assertTrue(mapInstanceStable())
    }

    private fun assertSize(expected: Int) = synchronized(SourceRuntimeState.sortCache) {
        assertEquals(expected, SourceRuntimeState.sortCache.size)
    }

    private fun mapInstanceStable(): Boolean {
        val before = System.identityHashCode(SourceRuntimeState.sortCache)
        SourceRuntimeState.clearRuntimeCache()
        return before == System.identityHashCode(SourceRuntimeState.sortCache)
    }
}
