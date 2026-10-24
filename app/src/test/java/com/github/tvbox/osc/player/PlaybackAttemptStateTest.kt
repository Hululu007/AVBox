package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAttemptStateTest {

    private fun bootRetriedState(): PlaybackAttemptState =
        PlaybackAttemptState(hasRetriedSameUrlOnBoot = true)

    @Test
    fun sameUrlRetry_defaultsToFalse() {
        assertFalse(PlaybackAttemptState().hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_clearedByBeginNewPlay() {
        assertFalse(bootRetriedState().beginNewPlay().hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_clearedByUserSelfRescue() {
        assertFalse(bootRetriedState().userSelfRescue().hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_clearedByLadderReset() {
        assertFalse(bootRetriedState().resetAutoRetryLadder().hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_keptBySessionBoundary() {
        assertTrue(bootRetriedState().beginSession().hasRetriedSameUrlOnBoot)
    }

    @Test
    fun preloadedResultFlag_clearedByBeginNewPlay() {
        val st = PlaybackAttemptState(usedPreloadedResult = true).beginNewPlay()
        assertFalse(st.usedPreloadedResult)
    }

    @Test
    fun httpStatus_clearedByBeginNewPlay() {
        assertEquals(0, PlaybackAttemptState(lastHttpStatus = 403).beginNewPlay().lastHttpStatus)
    }

    @Test
    fun httpStatus_clearedBySessionBoundary() {
        assertEquals(0, PlaybackAttemptState(lastHttpStatus = 500).beginSession().lastHttpStatus)
    }

    @Test
    fun triedLines_clearedByLadderReset() {
        val st = PlaybackAttemptState(triedLineFlags = setOf("a", "b")).resetAutoRetryLadder()
        assertTrue(st.triedLineFlags.isEmpty())
    }

    @Test
    fun triedLine_addedWithoutMutatingSource() {
        val base = PlaybackAttemptState()
        val next = base.withTriedLine("line1")
        assertTrue(base.triedLineFlags.isEmpty())
        assertEquals(setOf("line1"), next.triedLineFlags)
    }

    @Test
    fun skipEndLatch_defaultsArmedAndConsumedOnce() {
        val base = PlaybackAttemptState()
        assertTrue(base.skipEndPending)
        val consumed = base.consumeSkipEnd()
        assertFalse(consumed.skipEndPending)
        assertTrue(base.skipEndPending)
    }

    @Test
    fun skipEndLatch_notRearmedByBeginNewPlay() {
        assertFalse(PlaybackAttemptState(skipEndPending = false).beginNewPlay().skipEndPending)
    }
}
