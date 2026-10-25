package com.github.avbox.osc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailFullScreenSlideTest {

    private val states = listOf(false, true).flatMap { full ->
        listOf(false, true).flatMap { entering ->
            listOf(false, true).map { exiting ->
                DetailFullScreenSlideState(fullScreen = full, entering = entering, exiting = exiting)
            }
        }
    }

    @Test
    fun enterOnlyFromIdlePoster() {
        states.forEach { state ->
            val next = DetailFullScreenSlide.enter(state)
            if (state.fullScreen || state.entering) {
                assertNull(next)
            } else {
                assertEquals(state.copy(entering = true, exiting = false), next)
            }
        }
    }

    @Test
    fun exitRequiresFullOrEntering() {
        states.forEach { state ->
            val next = DetailFullScreenSlide.exit(state)
            if (state.exiting || (!state.fullScreen && !state.entering)) {
                assertNull(next)
            } else {
                assertEquals(state.copy(fullScreen = false, entering = false, exiting = true), next)
            }
        }
    }

    @Test
    fun cancelEntryOnlyFromEntering() {
        states.forEach { state ->
            val next = DetailFullScreenSlide.cancelEntry(state)
            if (!state.entering || state.exiting) {
                assertNull(next)
            } else {
                assertEquals(state.copy(entering = false, exiting = true), next)
            }
        }
    }

    @Test
    fun entryFinishedClearsEnteringAndLandsWhenNotExiting() {
        states.forEach { state ->
            val next = DetailFullScreenSlide.entryFinished(state)
            if (!state.entering) {
                assertNull(next)
            } else {
                assertEquals(state.copy(entering = false, fullScreen = !state.exiting), next)
            }
        }
    }

    @Test
    fun exitFinishedAlwaysClearsExiting() {
        states.forEach { state ->
            val next = DetailFullScreenSlide.exitFinished(state)
            assertFalse(next.exiting)
            assertEquals(state.fullScreen, next.fullScreen)
            assertEquals(state.entering, next.entering)
        }
    }

    @Test
    fun everyTransitionResultKeepsFlagsExclusive() {
        states.forEach { state ->
            listOf(
                DetailFullScreenSlide.enter(state),
                DetailFullScreenSlide.exit(state),
                DetailFullScreenSlide.cancelEntry(state),
                DetailFullScreenSlide.entryFinished(state),
                DetailFullScreenSlide.exitFinished(state),
            ).forEach { next ->
                if (next != null) assertFalse(next.entering && next.exiting)
            }
        }
    }

    @Test
    fun idlePosterEntersAndFullScreenExits() {
        assertTrue(DetailFullScreenSlide.enter(DetailFullScreenSlideState()) != null)
        assertTrue(DetailFullScreenSlide.exit(DetailFullScreenSlideState(fullScreen = true)) != null)
    }

    @Test
    fun exitingStateCanBeReentered() {
        val next = DetailFullScreenSlide.enter(DetailFullScreenSlideState(exiting = true))
        assertEquals(DetailFullScreenSlideState(entering = true), next)
    }

    @Test
    fun enteringStateCancelsIntoExit() {
        val next = DetailFullScreenSlide.cancelEntry(DetailFullScreenSlideState(entering = true))
        assertEquals(DetailFullScreenSlideState(exiting = true), next)
    }
}
