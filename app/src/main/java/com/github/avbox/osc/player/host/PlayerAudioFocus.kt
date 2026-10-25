package com.github.avbox.osc.player.host

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

interface AudioFocusTarget {

    fun isPlaybackPlaying(): Boolean

    fun isPlaybackMuted(): Boolean

    fun startPlayback()

    fun pausePlayback()

    fun setPlaybackVolume(volume: Float)
}

class AudioFocusActions(private val target: AudioFocusTarget) {

    private var startRequested = false

    private var pausedForLoss = false

    var focusGranted = false
        private set

    var lastFocusChange = 0
        private set

    fun shouldDispatch(focusChange: Int): Boolean {
        if (lastFocusChange == focusChange) return false
        lastFocusChange = focusChange
        return true
    }

    fun handle(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                focusGranted = true
                if ((startRequested || pausedForLoss) && !target.isPlaybackPlaying()) {
                    target.startPlayback()
                }
                startRequested = false
                pausedForLoss = false
                if (!target.isPlaybackMuted()) {
                    target.setPlaybackVolume(1.0f)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusGranted = false
                if (target.isPlaybackPlaying()) {
                    pausedForLoss = true
                    target.pausePlayback()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (target.isPlaybackPlaying() && !target.isPlaybackMuted()) {
                    target.setPlaybackVolume(0.1f)
                }
            }
        }
    }

    fun onNewPlayback() {
        startRequested = false
        pausedForLoss = false
    }

    fun onRequestResult(granted: Boolean) {
        if (granted) {
            focusGranted = true
            return
        }
        startRequested = true
    }

    fun onAbandon() {
        focusGranted = false
        startRequested = false
    }
}

class PlayerAudioFocus(context: Context, target: AudioFocusTarget) : AudioManager.OnAudioFocusChangeListener {

    private val handler = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val actions = AudioFocusActions(target)

    override fun onAudioFocusChange(focusChange: Int) {
        if (!actions.shouldDispatch(focusChange)) {
            return
        }
        handler.post { actions.handle(focusChange) }
    }

    fun onNewPlayback() {
        actions.onNewPlayback()
    }

    fun requestFocus() {
        if (actions.focusGranted) {
            return
        }
        val manager = audioManager ?: return
        val status = manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        actions.onRequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED == status)
    }

    fun abandonFocus() {
        val manager = audioManager ?: return
        actions.onAbandon()
        manager.abandonAudioFocus(this)
    }
}
