package com.github.avbox.osc.player.gesture

interface VideoGestureActions {

    fun inPlayback(): Boolean

    fun onSingleTap()

    fun onDoubleTapTogglePlay()

    fun onLongPressStart()

    fun onLongPressEnd()

    fun onSeekPreview(totalDeltaX: Float)

    fun onSeekCommit()

    fun onSeekCancel()

    fun onBrightnessSlide(totalDeltaY: Float)

    fun onVolumeSlide(totalDeltaY: Float)
}

data class VideoGestureSession(
    val inPlayback: Boolean,
    val canChangePosition: Boolean,
    val enableInNormal: Boolean,
    val fullScreen: Boolean,
    val locked: Boolean,
    val previewMode: Boolean,
    val paused: Boolean,
    val gestureEnabled: Boolean,
    val verticalSlidingDisabled: Boolean,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val edge: Boolean,
    val fromTopBand: Boolean = false,
)

enum class GestureVerdict {
    IGNORE,

    CLAIMED,
}

class VideoGestureHandler(
    private val actions: VideoGestureActions,
    val longPressTimeoutMs: Long = 500L,
    val doubleTapTimeoutMs: Long = 300L,
    val doubleTapMinTimeMs: Long = 40L,
    private val commitFraction: Float = 0.10f,
) {

    enum class Mode { UNDECIDED, SEEK, BRIGHTNESS, VOLUME, NONE }

    private var session: VideoGestureSession? = null
    private var mode = Mode.UNDECIDED
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var moved = false
    private var longPressed = false

    var lastTapTime: Long = -1L
        private set

    var tapPending: Boolean = false
        private set

    val currentMode: Mode get() = mode

    val isLongPressing: Boolean get() = longPressed

    val hasMoved: Boolean get() = moved

    fun beginSession(session: VideoGestureSession, x: Float, y: Float): GestureVerdict {
        this.session = session
        this.downX = x
        this.downY = y
        this.lastX = x
        this.mode = Mode.UNDECIDED
        this.moved = false
        this.longPressed = false

        if (session.locked) return GestureVerdict.CLAIMED
        if (session.edge) return GestureVerdict.IGNORE
        return GestureVerdict.CLAIMED
    }

    fun onMove(x: Float, y: Float, slop: Float): Boolean {
        val s = session ?: return false
        if (longPressed) {
            lastX = x
            return false
        }
        lastX = x
        val dx = x - downX
        val dy = y - downY

        if (!moved) {
            if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                moved = true
            } else {
                return false
            }
        }

        if (mode == Mode.UNDECIDED) {
            val commitPx = s.height * commitFraction
            val adx = kotlin.math.abs(dx)
            val ady = kotlin.math.abs(dy)
            if (adx > ady) {
                if (adx <= commitPx) return false
            } else {
                if (ady <= commitPx) return false
            }
            mode = decideMode(dx, dy, s)
        }
        return when (mode) {
            Mode.SEEK -> {
                actions.onSeekPreview(dx)
                true
            }
            Mode.BRIGHTNESS -> {
                actions.onBrightnessSlide(dy)
                true
            }
            Mode.VOLUME -> {
                actions.onVolumeSlide(dy)
                true
            }
            Mode.NONE, Mode.UNDECIDED -> false
        }
    }

    private fun decideMode(dx: Float, dy: Float, s: VideoGestureSession): Mode {
        if (s.locked) return Mode.NONE
        if (!s.inPlayback) return Mode.NONE
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        if (horizontal) {
            return if (s.canChangePosition) Mode.SEEK else Mode.NONE
        }
        if (s.fromTopBand) return Mode.NONE
        if (s.previewMode) return Mode.NONE
        if (!s.fullScreen && !s.enableInNormal) return Mode.NONE
        if (s.verticalSlidingDisabled) return Mode.NONE
        return if (lastX >= s.screenWidth / 2f) Mode.VOLUME else Mode.BRIGHTNESS
    }

    fun maybeLongPress(): Boolean {
        val s = session ?: return false
        if (moved || longPressed) return false
        if (s.locked || s.previewMode || s.paused) return false
        if (!s.inPlayback) return false
        longPressed = true
        actions.onLongPressStart()
        return true
    }

    fun endSession(cancelled: Boolean, nowMs: Long): EndResult {
        val s = session ?: return EndResult.NONE
        val endedMode = mode
        session = null
        mode = Mode.UNDECIDED

        if (longPressed) {
            longPressed = false
            tapPending = false
            actions.onLongPressEnd()
            return EndResult.NONE
        }

        if (moved) {
            tapPending = false
            if (endedMode == Mode.SEEK) {
                if (cancelled) actions.onSeekCancel() else actions.onSeekCommit()
            }
            return EndResult.NONE
        }

        if (s.locked) {
            tapPending = false
            actions.onSingleTap()
            return EndResult.NONE
        }

        val last = lastTapTime
        if (last > 0 && nowMs - last in doubleTapMinTimeMs..doubleTapTimeoutMs) {
            lastTapTime = -1L
            tapPending = false
            actions.onDoubleTapTogglePlay()
            return EndResult.DOUBLE_TAP
        }
        lastTapTime = nowMs
        tapPending = true
        return EndResult.TAP_PENDING
    }

    enum class EndResult {
        DOUBLE_TAP,

        TAP_PENDING,

        NONE,
    }

    fun markSingleTapConfirmed(): Boolean {
        if (!tapPending) return false
        tapPending = false
        lastTapTime = -1L
        actions.onSingleTap()
        return true
    }

    fun withinDoubleTapWindow(nowMs: Long): Boolean {
        val last = lastTapTime
        return last > 0 && nowMs - last <= doubleTapTimeoutMs
    }
}

const val VERBOSE_GESTURE_LOG = false

internal fun isInBottomGestureBand(downY: Float, height: Int, bandPx: Float): Boolean =
    height > 0 && bandPx > 0f && downY >= height - bandPx
