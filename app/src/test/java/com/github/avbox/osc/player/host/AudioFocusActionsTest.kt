package com.github.avbox.osc.player.host

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusActionsTest {

    private class Recorder(
        var playing: Boolean = false,
        var muted: Boolean = false,
    ) : AudioFocusTarget {
        val startCount = ArrayList<Int>()
        val pauseCount = ArrayList<Int>()
        val volumes = ArrayList<Float>()

        override fun isPlaybackPlaying(): Boolean = playing

        override fun isPlaybackMuted(): Boolean = muted

        override fun startPlayback() {
            startCount.add(1)
        }

        override fun pausePlayback() {
            pauseCount.add(1)
        }

        override fun setPlaybackVolume(volume: Float) {
            volumes.add(volume)
        }
    }

    @Test
    fun loss_whenPlaying_pausesAndRemembers() {
        val target = Recorder(playing = true)
        val actions = AudioFocusActions(target)

        actions.handle(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(1, target.pauseCount.size)
        assertFalse(actions.focusGranted)

        target.playing = false
        actions.handle(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, target.startCount.size)
        assertEquals(1.0f, target.volumes.last(), 0.0001f)
    }

    @Test
    fun loss_whenIdle_doesNotPause() {
        val target = Recorder(playing = false)
        val actions = AudioFocusActions(target)

        actions.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(target.pauseCount.isEmpty())
    }

    @Test
    fun gain_doesNotRestartWhenAlreadyPlaying() {
        val target = Recorder(playing = true)
        val actions = AudioFocusActions(target)

        actions.handle(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(target.startCount.isEmpty())
        assertEquals(1.0f, target.volumes.last(), 0.0001f)
    }

    @Test
    fun gain_afterFailedRequest_startsPlayback() {
        val target = Recorder(playing = false)
        val actions = AudioFocusActions(target)
        actions.onRequestResult(granted = false)

        actions.handle(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, target.startCount.size)
    }

    @Test
    fun gain_keepsMutedSilence() {
        val target = Recorder(playing = true, muted = true)
        val actions = AudioFocusActions(target)

        actions.handle(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(target.volumes.isEmpty())
    }

    @Test
    fun duck_lowersVolumeOnlyWhilePlayingAndUnmuted() {
        val target = Recorder(playing = true)
        val actions = AudioFocusActions(target)

        actions.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(0.1f, target.volumes.last(), 0.0001f)
    }

    @Test
    fun duck_whenMuted_isNoOp() {
        val target = Recorder(playing = true, muted = true)
        val actions = AudioFocusActions(target)

        actions.handle(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertTrue(target.volumes.isEmpty())
    }

    @Test
    fun onNewPlayback_clearsPendingRecovery() {
        val target = Recorder(playing = true)
        val actions = AudioFocusActions(target)
        actions.handle(AudioManager.AUDIOFOCUS_LOSS)
        target.playing = false

        actions.onNewPlayback()
        actions.handle(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(target.startCount.isEmpty())
    }

    @Test
    fun abandon_resetsGrantedState() {
        val target = Recorder(playing = false)
        val actions = AudioFocusActions(target)
        actions.onRequestResult(granted = true)
        assertTrue(actions.focusGranted)

        actions.onAbandon()

        assertFalse(actions.focusGranted)
    }

    @Test
    fun duplicateFocusEvent_isIgnored() {
        val actions = AudioFocusActions(Recorder())

        assertTrue(actions.shouldDispatch(AudioManager.AUDIOFOCUS_LOSS))
        assertFalse(actions.shouldDispatch(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(actions.shouldDispatch(AudioManager.AUDIOFOCUS_GAIN))
    }
}
