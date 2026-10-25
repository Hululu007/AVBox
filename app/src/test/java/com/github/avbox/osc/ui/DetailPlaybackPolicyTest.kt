package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPlaybackPolicyTest {

    private fun decide(entry: DetailPlaybackEntry, resumable: Boolean = false) =
        DetailPlaybackPolicy.plan(entry, resumable)

    @Test
    fun playCapsuleStartsAndEntersFullScreen() {
        val plan = decide(DetailPlaybackEntry.PlayCapsule)
        assertTrue(plan.startPlayback)
        assertTrue(plan.enterFullScreen)
        assertFalse(plan.stopPlayback)
    }

    @Test
    fun episodeEntryStartsAndEntersFullScreen() {
        val plan = decide(DetailPlaybackEntry.Episode)
        assertTrue(plan.startPlayback)
        assertTrue(plan.enterFullScreen)
        assertFalse(plan.stopPlayback)
    }

    @Test
    fun lineNeverStartsAndNeverEntersFullScreen() {
        val plan = decide(DetailPlaybackEntry.Line)
        assertFalse(plan.startPlayback)
        assertFalse(plan.enterFullScreen)
        assertFalse(plan.stopPlayback)
        val resumable = decide(DetailPlaybackEntry.Line, resumable = true)
        assertFalse(resumable.startPlayback)
        assertFalse(resumable.enterFullScreen)
    }

    @Test
    fun castOnlyResolvesAddress() {
        val plan = decide(DetailPlaybackEntry.Cast)
        assertFalse(plan.startPlayback)
        assertFalse(plan.enterFullScreen)
        assertFalse(plan.stopPlayback)
        val resumable = decide(DetailPlaybackEntry.Cast, resumable = true)
        assertFalse(resumable.startPlayback)
        assertFalse(resumable.enterFullScreen)
    }

    @Test
    fun exitFullscreenStopsWithoutStarting() {
        val plan = decide(DetailPlaybackEntry.ExitFullscreen)
        assertTrue(plan.stopPlayback)
        assertFalse(plan.startPlayback)
        assertFalse(plan.enterFullScreen)
    }

    @Test
    fun rollbackResumesOnlyWhenPlaybackWasRunning() {
        val notPlaying = decide(DetailPlaybackEntry.SwitchRollback)
        assertFalse(notPlaying.startPlayback)
        assertFalse(notPlaying.enterFullScreen)
        val wasPlaying = decide(DetailPlaybackEntry.SwitchRollback, resumable = true)
        assertTrue(wasPlaying.startPlayback)
        assertFalse(wasPlaying.enterFullScreen)
    }

    @Test
    fun musicReturnResumesOwnedPlaybackWithoutFullScreen() {
        val resumed = decide(DetailPlaybackEntry.MusicReturn, resumable = true)
        assertTrue(resumed.startPlayback)
        assertFalse(resumed.enterFullScreen)
        val nothingToResume = decide(DetailPlaybackEntry.MusicReturn)
        assertFalse(nothingToResume.startPlayback)
        assertFalse(nothingToResume.enterFullScreen)
    }

    @Test
    fun onlyExitFullscreenStopsPlayback() {
        for (entry in DetailPlaybackEntry.entries) {
            for (resumable in listOf(false, true)) {
                val plan = decide(entry, resumable)
                if (entry == DetailPlaybackEntry.ExitFullscreen) {
                    assertTrue("$entry 应停播", plan.stopPlayback)
                } else {
                    assertFalse("$entry 不应停播", plan.stopPlayback)
                }
            }
        }
    }

    @Test
    fun onlyPlayEntriesEnterFullScreen() {
        for (entry in DetailPlaybackEntry.entries) {
            val plan = decide(entry, resumable = true)
            val shouldEnterFullScreen = entry == DetailPlaybackEntry.PlayCapsule ||
                entry == DetailPlaybackEntry.Episode
            if (shouldEnterFullScreen) {
                assertTrue("$entry 应进全屏", plan.enterFullScreen)
            } else {
                assertFalse("$entry 不应进全屏", plan.enterFullScreen)
            }
        }
    }

    @Test
    fun onlyPlayAndResumableEntriesStartPlayback() {
        for (entry in DetailPlaybackEntry.entries) {
            val plan = decide(entry, resumable = true)
            val shouldStart = entry == DetailPlaybackEntry.PlayCapsule ||
                entry == DetailPlaybackEntry.Episode ||
                entry == DetailPlaybackEntry.SwitchRollback ||
                entry == DetailPlaybackEntry.MusicReturn
            if (shouldStart) {
                assertTrue("$entry 应起播", plan.startPlayback)
            } else {
                assertFalse("$entry 不应起播", plan.startPlayback)
            }
        }
    }
}
