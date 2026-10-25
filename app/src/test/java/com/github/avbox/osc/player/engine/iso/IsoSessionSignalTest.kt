package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoSessionSignalTest {

    private var now = 1_000L

    @Test
    fun escalation_isVisibleUntilReset() {
        val state = IsoSessionSignalState { now }
        assertFalse(state.escalated())
        state.markEscalated()
        assertTrue(state.escalated())
        state.reset()
        assertFalse(state.escalated())
    }

    @Test
    fun windowExtension_isGrantedOnce() {
        val state = IsoSessionSignalState { now }
        state.markEscalated()
        assertTrue(state.consumeWindowExtension())
        assertFalse(state.consumeWindowExtension())
    }

    @Test
    fun windowExtension_isDeniedWithoutEscalation() {
        val state = IsoSessionSignalState { now }
        assertFalse(state.consumeWindowExtension())
    }

    @Test
    fun staleEscalation_doesNotExtendWindow() {
        val state = IsoSessionSignalState { now }
        state.markEscalated()
        now += 121_000L
        assertFalse(state.consumeWindowExtension())
    }

    @Test
    fun escalation_rearmsExtension() {
        val state = IsoSessionSignalState { now }
        state.markEscalated()
        assertTrue(state.consumeWindowExtension())
        now += 5_000L
        state.markEscalated()
        assertTrue(state.consumeWindowExtension())
    }

    @Test
    fun stubBytes_areRecordedAndReset() {
        val state = IsoSessionSignalState { now }
        assertEquals(0L, state.stubBytes())
        state.markStub(6144)
        assertEquals(6144L, state.stubBytes())
        state.reset()
        assertEquals(0L, state.stubBytes())
    }
}
