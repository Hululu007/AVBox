package com.github.avbox.osc.player.engine.iso

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoMplsFetchTest {

    @Test
    fun load_keepsOrderAndIsolatesFailures() {
        val entries = (0 until 6).map { index -> networked("00000$index.mpls", index.toLong()) }
        val results = IsoMplsFetch.load(entries) { entry ->
            if (entry.name == "000003.mpls") error("boom")
            MplFetchResult(entry.name.toByteArray(), null)
        }

        assertEquals(6, results.size)
        assertEquals("000000.mpls", String(results[0]!!.bytes))
        assertNull(results[3])
        assertEquals("000005.mpls", String(results[5]!!.bytes))
    }

    @Test
    fun load_keepsNetworkedEntriesOnSingleThread() {
        val entries = (0 until 8).map { index -> networked("$index.mpls", index.toLong()) }
        val threads = ConcurrentHashMap.newKeySet<Long>()
        val order = Collections.synchronizedList(ArrayList<String>())
        IsoMplsFetch.load(entries) { entry ->
            threads.add(Thread.currentThread().id)
            order.add(entry.name)
            Thread.sleep(5)
            MplFetchResult(entry.name.toByteArray(), null)
        }

        assertEquals(1, threads.size)
        assertEquals(entries.map { it.name }, order)
    }

    @Test
    fun runWithBudget_returnsNullOnTimeout() {
        val result = IsoMplsFetch.runWithBudget(50) {
            Thread.sleep(400)
            true
        }

        assertNull(result)
    }

    @Test
    fun runWithBudget_rethrowsErrorCause() {
        assertThrows(AssertionError::class.java) {
            IsoMplsFetch.runWithBudget(2_000) { throw AssertionError("boom") }
        }
    }

    @Test
    fun load_keepsInlineEntriesOnCallerThread() {
        val entries = (0 until 4).map { index -> embedded("$index.mpls", index.toLong()) }
        val threads = ConcurrentHashMap.newKeySet<Long>()
        IsoMplsFetch.load(entries) { entry ->
            threads.add(Thread.currentThread().id)
            MplFetchResult(entry.name.toByteArray(), null)
        }

        assertEquals(1, threads.size)
    }

    private fun networked(name: String, offset: Long): IsoEntry {
        val extents = listOf(IsoExtent(offset * 2048, 1024))
        return IsoEntry(name, false, 1024, extents, EntryData.Extents(extents))
    }

    private fun embedded(name: String, offset: Long): IsoEntry {
        val extents = listOf(IsoExtent(offset * 2048, 4))
        return IsoEntry(name, false, 4, extents, EntryData.Embedded(byteArrayOf(1, 2, 3, 4)))
    }
}
