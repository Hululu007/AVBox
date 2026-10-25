package com.github.avbox.osc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressIndexTest {

    @Test
    fun withEp_appendsAndRefreshesActiveTime() {
        val raw = WatchProgressIndex.withEp(null, "src剧1高清集名", 1000L)
        val entry = WatchProgressIndex.decode(raw)
        assertEquals(1000L, entry?.at)
        assertEquals(listOf("src剧1高清集名"), entry?.eps)
    }

    @Test
    fun withEp_sameEpisodeDoesNotDuplicate() {
        var raw = WatchProgressIndex.withEp(null, "epA", 1L)
        raw = WatchProgressIndex.withEp(raw, "epB", 2L)
        raw = WatchProgressIndex.withEp(raw, "epA", 3L)
        val entry = WatchProgressIndex.decode(raw)
        assertEquals(3L, entry?.at)
        assertEquals(listOf("epB", "epA"), entry?.eps)
    }

    @Test
    fun payload_roundTripKeepsSeparatorCharacters() {
        val nasty = "第01集;|\"{} 4K"
        val raw = WatchProgressIndex.withEp(null, nasty, 7L)
        assertEquals(listOf(nasty), WatchProgressIndex.decode(raw)?.eps)
    }

    @Test
    fun withoutEp_dropsEntryWhenLastEpisodeRemoved() {
        val raw = WatchProgressIndex.withEp(null, "epA", 1L)
        assertNull(WatchProgressIndex.withoutEp(raw, "epA"))
        val two = WatchProgressIndex.withEp(raw, "epB", 2L)
        val left = WatchProgressIndex.decode(WatchProgressIndex.withoutEp(two, "epA"))
        assertEquals(listOf("epB"), left?.eps)
    }

    @Test
    fun decode_corruptPayloadDegradesToNoIndex() {
        assertNull(WatchProgressIndex.decode(null))
        assertNull(WatchProgressIndex.decode(""))
        assertNull(WatchProgressIndex.decode("not-json"))
        assertNull(WatchProgressIndex.decode("[1,2,3]"))
    }

    @Test
    fun decode_missingFieldsTolerated() {
        assertEquals(0L, WatchProgressIndex.decode("{}")?.at)
        assertTrue(WatchProgressIndex.decode("{}")?.eps?.isEmpty() == true)
    }

    @Test
    fun trimEps_keepsMostRecent() {
        val eps = (1..10).map { "ep$it" }
        assertEquals(listOf("ep9", "ep10"), WatchProgressIndex.trimEps(eps, 2))
        assertEquals(eps, WatchProgressIndex.trimEps(eps, 10))
    }

    @Test
    fun pickEvictions_evictsOldestFirstAndStaysStable() {
        val entries = listOf("a" to 30L, "b" to 10L, "c" to 20L)
        assertEquals(emptyList<String>(), WatchProgressIndex.pickEvictions(entries, 3))
        assertEquals(listOf("b"), WatchProgressIndex.pickEvictions(entries, 2))
        assertEquals(listOf("b", "c"), WatchProgressIndex.pickEvictions(entries, 1))
        val ties = listOf("x" to 5L, "y" to 5L, "z" to 5L)
        assertEquals(listOf("x", "y"), WatchProgressIndex.pickEvictions(ties, 1))
    }

    @Test
    fun keyOfAndOwnerOf_areInverse() {
        val key = WatchProgressIndex.keyOf("源|片id")
        assertEquals("progress_index_源|片id", key)
        assertEquals("源|片id", WatchProgressIndex.ownerOf(key))
    }
}
