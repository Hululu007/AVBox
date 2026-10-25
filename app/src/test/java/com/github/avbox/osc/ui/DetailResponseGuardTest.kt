package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailResponseGuardTest {

    @Test
    fun responseFromCurrentGenerationPasses() {
        assertTrue(DetailResponseGuard.isCurrent(requestToken = 7, responseToken = 7))
    }

    @Test
    fun lateResponseFromPreviousGenerationIsDropped() {
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 8, responseToken = 7))
    }

    @Test
    fun responseAheadOfCurrentGenerationIsDropped() {
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 7, responseToken = 8))
    }

    @Test
    fun untaggedResponseIsNotBelieved() {
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 1, responseToken = null))
    }

    @Test
    fun fallbackCandidatesAllCountAsCurrent() {
        val generation = 11
        assertTrue(DetailResponseGuard.isCurrent(generation, generation))
        assertFalse(DetailResponseGuard.isCurrent(generation + 1, generation))
    }

    @Test
    fun detailTargetsThatCannotBeLoaded() {
        assertTrue(DetailResponseGuard.isUnloadableTarget(vodId = "", sourceMissing = false))
        assertTrue(DetailResponseGuard.isUnloadableTarget(vodId = "msearch:123", sourceMissing = false))
        assertTrue(DetailResponseGuard.isUnloadableTarget(vodId = "12345", sourceMissing = true))
    }

    @Test
    fun loadableTargetIsNotTreatedAsUnavailable() {
        assertFalse(DetailResponseGuard.isUnloadableTarget(vodId = "12345", sourceMissing = false))
        assertFalse(DetailResponseGuard.isUnloadableTarget(vodId = "msearch123", sourceMissing = false))
    }
}
