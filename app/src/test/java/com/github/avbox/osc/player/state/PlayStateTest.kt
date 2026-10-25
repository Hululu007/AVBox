package com.github.avbox.osc.player.state

import com.github.avbox.osc.player.core.PlayState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStateTest {

    @Test
    fun isInPlaybackStateMatchesWhitelist() {
        val expected = mapOf(
            PlayState.IDLE to false,
            PlayState.PREPARING to false,
            PlayState.PREPARED to true,
            PlayState.PLAYING to true,
            PlayState.PAUSED to true,
            PlayState.COMPLETED to false,
            PlayState.BUFFERING to true,
            PlayState.BUFFERED to true,
            PlayState.ERROR to false,
            PlayState.START_ABORT to false,
        )
        PlayState.entries.forEach { state ->
            assertEquals(expected.getValue(state), state.isInPlaybackState)
        }
    }

    @Test
    fun enumMembersArePinned() {
        assertEquals(
            listOf("IDLE", "PREPARING", "PREPARED", "PLAYING", "PAUSED", "COMPLETED", "BUFFERING", "BUFFERED", "ERROR", "START_ABORT"),
            PlayState.entries.map { it.name },
        )
    }
}
