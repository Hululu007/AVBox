package com.github.avbox.osc.player.core

import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.StateFlow

abstract class KernelPlayer {

    interface Listener {
        fun onError()
        fun onCompletion()
        fun onInfo(what: Int, extra: Int)
        fun onPrepared()
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onKernelPlayStateChanged() {}
    }

    @JvmField
    var playerEventListener: Listener? = null

    @Volatile
    private var initialPosition: Long = 0

    @Volatile
    private var startPositionApplied: Boolean = false

    abstract fun initPlayer()

    abstract fun setDataSource(path: String, headers: Map<String, String>?)

    abstract fun start(): Boolean

    abstract fun pause(): Boolean

    abstract fun stop(): Boolean

    open fun abortStart() {}

    abstract fun prepareAsync()

    fun setStartPosition(position: Long) {
        initialPosition = maxOf(0L, position)
        startPositionApplied = false
    }

    protected val startPosition: Long
        get() = initialPosition

    protected fun markStartPositionApplied() {
        startPositionApplied = true
    }

    fun isStartPositionApplied(): Boolean = startPositionApplied

    abstract fun reset()

    open fun keepRenderViewOnReset(): Boolean = false

    open fun resetTrackSelection() {}

    open fun getTrackInfo(): TrackInfo = TrackInfo()

    open fun setTrack(track: TrackInfoBean?) {}

    abstract val isPlaying: Boolean

    abstract val playState: PlayState

    abstract val stateFlow: StateFlow<PlayState>

    abstract fun seekTo(time: Long)

    abstract fun release()

    abstract val currentPosition: Long

    abstract val duration: Long

    abstract val bufferedPercentage: Int

    abstract fun setSurface(surface: Surface?)

    abstract fun setDisplay(holder: SurfaceHolder?)

    open fun clearDisplay() {
        setSurface(null)
    }

    abstract fun detachVideoSurface()

    abstract fun setVolume(leftVolume: Float, rightVolume: Float)

    abstract fun setLooping(isLooping: Boolean)

    abstract fun setOptions()

    abstract fun setSpeed(speed: Float)

    abstract val speed: Float

    abstract val tcpSpeed: Long

    companion object {

        const val MEDIA_INFO_RENDERING_START = 3

        const val MEDIA_INFO_BUFFERING_START = 701

        const val MEDIA_INFO_BUFFERING_END = 702

        const val MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001
    }
}
