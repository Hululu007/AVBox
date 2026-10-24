package com.github.tvbox.osc.player.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressUiGateTest {

    @Test
    fun loadingPhase_rejectsEvenWhenGenMatches() {
        assertFalse(ProgressUiGate.accept(ProgressPhase.LOADING, 3, 3))
        assertFalse(ProgressUiGate.accept(ProgressPhase.LOADING, 3, 4))
        assertFalse(ProgressUiGate.accept(ProgressPhase.LOADING, -1, 0))
    }

    @Test
    fun idlePhase_rejects() {
        assertFalse(ProgressUiGate.accept(ProgressPhase.IDLE, 3, 3))
    }

    @Test
    fun activePhase_acceptsMatchingGen() {
        assertTrue(ProgressUiGate.accept(ProgressPhase.ACTIVE, 3, 3))
    }

    @Test
    fun activePhase_rejectsMismatchedGen() {
        assertFalse(ProgressUiGate.accept(ProgressPhase.ACTIVE, 3, 4))
        assertFalse(ProgressUiGate.accept(ProgressPhase.ACTIVE, 4, 3))
    }

    @Test
    fun activePhase_rejectsUnarmedController() {
        assertFalse(ProgressUiGate.accept(ProgressPhase.ACTIVE, -1, 0))
        assertFalse(ProgressUiGate.accept(ProgressPhase.ACTIVE, -1, 7))
    }
}
