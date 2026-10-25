package com.github.avbox.osc.player.state

import com.github.avbox.osc.player.core.KernelPlayback
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.core.deriveKernelPlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelPlayStateDerivationTest {

    @Test
    fun readyPlayingMapsToPlaying() {
        assertEquals(
            PlayState.PLAYING,
            deriveKernelPlayState(
                KernelPlayback.READY,
                playWhenReady = true,
                isPlaying = true,
                suppressed = false,
            ),
        )
    }

    @Test
    fun readyStoppedMapsToPaused() {
        assertEquals(
            PlayState.PAUSED,
            deriveKernelPlayState(
                KernelPlayback.READY,
                playWhenReady = false,
                isPlaying = false,
                suppressed = false,
            ),
        )
    }

    @Test
    fun bufferingFollowsPlayWhenReady() {
        assertEquals(
            PlayState.BUFFERING,
            deriveKernelPlayState(
                KernelPlayback.BUFFERING,
                playWhenReady = true,
                isPlaying = false,
                suppressed = false,
            ),
        )
        assertEquals(
            PlayState.PAUSED,
            deriveKernelPlayState(
                KernelPlayback.BUFFERING,
                playWhenReady = false,
                isPlaying = false,
                suppressed = false,
            ),
        )
    }

    @Test
    fun suppressedKeepsCurrentState() {
        assertNull(
            deriveKernelPlayState(
                KernelPlayback.READY,
                playWhenReady = true,
                isPlaying = false,
                suppressed = true,
            ),
        )
        assertNull(
            deriveKernelPlayState(
                KernelPlayback.BUFFERING,
                playWhenReady = true,
                isPlaying = false,
                suppressed = true,
            ),
        )
    }

    @Test
    fun readyWithoutPlaybackSignalKeepsCurrentState() {
        assertNull(
            deriveKernelPlayState(
                KernelPlayback.READY,
                playWhenReady = true,
                isPlaying = false,
                suppressed = false,
            ),
        )
    }

    @Test
    fun idleWithPlayWhenReadyKeepsCurrentState() {
        assertNull(
            deriveKernelPlayState(
                KernelPlayback.IDLE,
                playWhenReady = true,
                isPlaying = false,
                suppressed = false,
            ),
        )
    }

    @Test
    fun idleKeepsCurrentState() {
        assertNull(
            deriveKernelPlayState(
                KernelPlayback.IDLE,
                playWhenReady = false,
                isPlaying = false,
                suppressed = false,
            ),
        )
    }

    @Test
    fun endedMapsToCompleted() {
        assertEquals(
            PlayState.COMPLETED,
            deriveKernelPlayState(
                KernelPlayback.ENDED,
                playWhenReady = true,
                isPlaying = false,
                suppressed = false,
            ),
        )
        assertEquals(
            PlayState.COMPLETED,
            deriveKernelPlayState(
                KernelPlayback.ENDED,
                playWhenReady = false,
                isPlaying = false,
                suppressed = false,
            ),
        )
    }
}
