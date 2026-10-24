package com.github.tvbox.osc.player.controller

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import com.github.tvbox.osc.player.AppPlayerView
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.GestureHelper
import com.github.tvbox.osc.util.PlayerUtils
import kotlin.math.abs

class ComposeLiveController(
    context: Context,
) : FrameLayout(context),
    AppPlayerView.VideoControllerHost,
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    View.OnTouchListener {

    companion object {
        private const val MIN_FLING_DISTANCE = 100
        private const val MIN_FLING_VELOCITY = 10
    }

    interface LiveControlListener {
        fun onSingleTap(): Boolean

        fun onLongPress()

        fun onPlayStateChanged(playState: PlayState)

        fun onHorizontalFling(direction: Int)

        fun onGesturePercent(isBrightness: Boolean, percent: Int) {}
    }

    private var listener: LiveControlListener? = null

    fun setListener(listener: LiveControlListener) {
        this.listener = listener
    }

    private var videoView: MyVideoView? = null

    private val gestureDetector: GestureDetector = GestureDetector(context, this)
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var streamVolume = 0
    private var brightness = 0f
    private var firstTouch = false
    private var changeBrightness = false
    private var changeVolume = false
    init {
        @Suppress("ClickableViewAccessibility")
        setOnTouchListener(this)
    }

    override fun setPlayState(playState: PlayState) {
        listener?.onPlayStateChanged(playState)
    }

    override fun setPlayerState(playerState: Int) {
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
    }

    override fun onVideoSizeCleared() {
    }

    override fun startProgress() {
    }

    private fun gesturePlaybackState(): Boolean {
        val view = videoView ?: return false
        return when (view.playState) {
            PlayState.PLAYING, PlayState.PAUSED, PlayState.BUFFERING, PlayState.BUFFERED -> true
            else -> false
        }
    }

    private fun canHandleGesture(event: MotionEvent): Boolean {
        return gesturePlaybackState() && !PlayerUtils.isEdge(context, event)
    }

    private fun canChangeBrightnessVolume(event: MotionEvent): Boolean {
        return canHandleGesture(event) && !GestureHelper.isControlDisabled()
    }

    override fun onDown(e: MotionEvent): Boolean {
        if (!gesturePlaybackState() || PlayerUtils.isEdge(context, e)) {
            return true
        }
        streamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val activity = PlayerUtils.scanForActivity(context)
        brightness = activity?.window?.attributes?.screenBrightness ?: 0f
        firstTouch = true
        changeBrightness = false
        changeVolume = false
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        if (e1 == null) return true
        if (!canHandleGesture(e1)) return true
        if (firstTouch) {
            if (abs(distanceX) < abs(distanceY)) {
                if (!canChangeBrightnessVolume(e1)) return true
                val halfScreen = PlayerUtils.getScreenWidth(context, true) / 2
                if (e2.x > halfScreen) changeVolume = true else changeBrightness = true
            }
            firstTouch = false
        }
        if (changeBrightness) {
            slideToChangeBrightness(e1.y - e2.y)
        } else if (changeVolume) {
            slideToChangeVolume(e1.y - e2.y)
        }
        return true
    }

    private fun slideToChangeBrightness(deltaY: Float) {
        val activity = PlayerUtils.scanForActivity(context) ?: return
        val window: Window = activity.window
        val attributes = window.attributes
        val height = measuredHeight
        if (height <= 0) return
        if (brightness == -1.0f) brightness = 0.5f
        var target = deltaY * 2 / height + brightness
        if (target < 0) target = 0f
        if (target > 1.0f) target = 1.0f
        val percent = (target * 100).toInt()
        attributes.screenBrightness = target
        window.attributes = attributes
        listener?.onGesturePercent(true, percent)
    }

    private fun slideToChangeVolume(deltaY: Float) {
        val height = measuredHeight
        if (height <= 0) return
        val streamMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val deltaV = deltaY * 2 / height * streamMaxVolume
        var index = streamVolume + deltaV
        if (index > streamMaxVolume) index = streamMaxVolume.toFloat()
        if (index < 0) index = 0f
        val percent = (index / streamMaxVolume * 100).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        listener?.onGesturePercent(false, percent)
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        val consumed = listener?.onSingleTap() ?: false
        if (consumed) return true
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean = true

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onShowPress(e: MotionEvent) {}

    override fun onLongPress(e: MotionEvent) {
        listener?.onLongPress()
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        if (e1 == null) return false
        if (e1.x - e2.x > MIN_FLING_DISTANCE && abs(velocityX) > MIN_FLING_VELOCITY) {
            listener?.onHorizontalFling(-1)
        } else if (e2.x - e1.x > MIN_FLING_DISTANCE && abs(velocityX) > MIN_FLING_VELOCITY) {
            listener?.onHorizontalFling(1)
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }
}
