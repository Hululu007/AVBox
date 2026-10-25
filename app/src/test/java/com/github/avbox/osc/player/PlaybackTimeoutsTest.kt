package com.github.avbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimeoutsTest {

    @Test
    fun isoSession_usesFortyFiveSeconds() {
        assertEquals(45_000L, PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession = true))
    }

    @Test
    fun nonIsoSession_keepsTwentySeconds() {
        assertEquals(20_000L, PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession = false))
    }

    @Test
    fun localProxySession_usesPanWindow() {
        assertEquals(
            35_000L,
            PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession = false, localProxy = true),
        )
    }

    @Test
    fun isoSession_winsOverLocalProxy() {
        assertEquals(
            45_000L,
            PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession = true, localProxy = true),
        )
    }

    @Test
    fun slowOpenGrace_isShorterThanEveryPlayWindow() {
        assertTrue(PlaybackTimeouts.SLOW_OPEN_GRACE_MS < PlaybackTimeouts.SWITCH_LINE_PLAY_TIMEOUT_MS)
        assertTrue(PlaybackTimeouts.SLOW_OPEN_GRACE_MS < PlaybackTimeouts.PAN_SWITCH_LINE_PLAY_TIMEOUT_MS)
    }
}
