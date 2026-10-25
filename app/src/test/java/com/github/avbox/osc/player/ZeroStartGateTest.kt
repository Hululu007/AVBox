package com.github.avbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroStartGateTest {

    @Test
    fun mark_dedupesKeys() {
        val pending = LinkedHashSet<String>()
        assertTrue(ZeroStartGate.mark(pending, listOf("a", "b", "a")))
        assertEquals(setOf("a", "b"), pending)
        assertFalse(ZeroStartGate.mark(pending, listOf("a", "b")))
    }

    @Test
    fun mark_ignoresBlankKeys() {
        val pending = LinkedHashSet<String>()
        assertFalse(ZeroStartGate.mark(pending, listOf("", "")))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun mark_capsOldestBeyondLimit() {
        val pending = LinkedHashSet<String>()
        ZeroStartGate.mark(pending, listOf("k1", "k2", "k3"))
        ZeroStartGate.mark(pending, (4..(ZeroStartGate.MAX_KEYS + 1)).map { "k$it" })
        assertEquals(ZeroStartGate.MAX_KEYS, pending.size)
        assertFalse(pending.contains("k1"))
        assertTrue(pending.contains("k2"))
        assertTrue(pending.contains("k${ZeroStartGate.MAX_KEYS + 1}"))
    }

    @Test
    fun consume_matchesOnlyExactKeyAndRemovesIt() {
        val pending = LinkedHashSet<String>(listOf("src|1#线路A#0正片"))
        assertFalse(ZeroStartGate.consume(pending, "src|1#线路A#1正片"))
        assertTrue(ZeroStartGate.consume(pending, "src|1#线路A#0正片"))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun consume_blankKeyIsNoop() {
        val pending = LinkedHashSet<String>(listOf("k"))
        assertFalse(ZeroStartGate.consume(pending, null))
        assertFalse(ZeroStartGate.consume(pending, ""))
        assertTrue(pending.contains("k"))
    }
}
