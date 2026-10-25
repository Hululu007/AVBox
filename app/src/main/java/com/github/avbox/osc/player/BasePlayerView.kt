package com.github.avbox.osc.player

import android.app.Activity
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Parcelable
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.avbox.osc.player.core.KernelPlayer
import com.github.avbox.osc.player.core.TrackInfoBean
import com.github.avbox.osc.player.core.VideoCodecObserver
import com.github.avbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.avbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.avbox.osc.player.host.PlayerRenderView
import com.github.avbox.osc.player.host.PlayerRenderViewFactory
import com.github.avbox.osc.player.host.PlayerAudioFocus
import com.github.avbox.osc.player.host.AudioFocusTarget
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.state.ScreenMode
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.PlayerUtils
import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

open class BasePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface ProgressSink {
        fun saveProgress(url: String?, progress: Long)

        suspend fun getSavedProgress(url: String?): Long
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var startPlaySeq = 0

    protected var kernelPlayer: KernelPlayer? = null

    protected var progressSinkRef: ProgressSink? = null

    protected val playerFrame: FrameLayout = FrameLayout(context)

    protected var activeRenderView: PlayerRenderView? = null

    protected var renderFactory: PlayerRenderViewFactory =
        if (com.github.avbox.osc.util.KV.get(com.github.avbox.osc.util.KVKeys.PLAY_RENDER, 1) == 1) {
            EngineSurfaceRenderViewFactory.create()
        } else {
            EngineTextureRenderViewFactory.create()
        }

    protected var currentScaleType = SCREEN_SCALE_DEFAULT

    protected var videoSizePx = intArrayOf(0, 0)

    protected var playUrl: String? = null

    @Volatile
    protected var contentGeneration = 0

    protected var progressRecordKey: String? = null

    protected var requestHeaders: Map<String, String>? = null

    protected var playbackPosition = 0L

    private val zeroStartKeys = LinkedHashSet<String>()

    private var lastReportedPlayState = PlayState.IDLE

    private val _playStateFlow = MutableSharedFlow<PlayState>(extraBufferCapacity = PLAY_STATE_FLOW_BUFFER)

    val playStateFlow: SharedFlow<PlayState> = _playStateFlow

    protected var playerStateCode = PLAYER_NORMAL

    protected var enableAudioFocus = true

    private var audioFocusHelper: PlayerAudioFocus? = null

    private val audioFocusTarget = object : AudioFocusTarget {
        override fun isPlaybackPlaying(): Boolean = isPlaying

        override fun isPlaybackMuted(): Boolean = false

        override fun startPlayback() {
            start()
        }

        override fun pausePlayback() {
            pause()
        }

        override fun setPlaybackVolume(volume: Float) {
            kernelPlayer?.setVolume(volume, volume)
        }
    }

    init {
        playerFrame.setBackgroundColor(Color.BLACK)
        addView(
            playerFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        enableAudioFocus = true
    }

    open fun start() {
        if (isInIdleState() || isInStartAbortState()) {
            startPlay()
            return
        }
        startInPlaybackState()
    }

    protected open fun startPlay(): Boolean {
        if (showNetWarning()) {
            kernelPlayer?.abortStart()
            dispatchPlayState(PlayState.START_ABORT)
            return false
        }
        if (enableAudioFocus) {
            ensureAudioFocusHelper()
            audioFocusHelper?.onNewPlayback()
        }
        kernelPlayer?.release()
        kernelPlayer = null
        initPlayer()
        addDisplay()
        val sink = progressSinkRef
        val key = progressKey()
        if (sink == null) {
            applyStartPosition(key, null)
            startPrepare(false)
            return true
        }
        val seq = ++startPlaySeq
        scope.launch {
            val saved = sink.getSavedProgress(key)
            if (seq != startPlaySeq) return@launch
            applyStartPosition(key, saved)
            startPrepare(false)
        }
        return true
    }

    private fun applyStartPosition(key: String?, saved: Long?) {
        if (saved != null) playbackPosition = saved
        if (ZeroStartGate.consume(zeroStartKeys, key)) {
            playbackPosition = 0
            AppLog.i("echo-zero-start apply key=" + key)
        }
    }

    protected open fun showNetWarning(): Boolean = false

    internal var videoCodecObserver: VideoCodecObserver? = null

    protected open fun initPlayer() {
        val player = createPlayer()
        (player as? ExoPlayer)?.videoCodecObserver = videoCodecObserver
        player.playerEventListener = kernelEventListener
        kernelPlayer = player
        setInitOptions()
        player.initPlayer()
        setOptions()
    }

    protected open fun createPlayer(): KernelPlayer = ExoPlayer(context)

    protected open fun setInitOptions() = Unit

    protected open fun setOptions() {
        kernelPlayer?.setLooping(false)
        kernelPlayer?.setVolume(1.0f, 1.0f)
    }

    open fun prewarmKernel() {
        if (kernelPlayer != null) return
        ensureAudioFocusHelper()
        initPlayer()
        addDisplay()
    }

    private fun ensureAudioFocusHelper() {
        if (enableAudioFocus && audioFocusHelper == null) {
            audioFocusHelper = PlayerAudioFocus(context, audioFocusTarget)
        }
    }

    protected open fun addDisplay() {
        activeRenderView?.let { render ->
            playerFrame.removeView(render.getView())
            kernelPlayer?.detachVideoSurface()
            render.release()
        }
        val render = renderFactory.createRenderView(context)
        kernelPlayer?.let { render.attachToPlayer(it) }
        activeRenderView = render
        playerFrame.addView(
            render.getView(),
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        render.getView().addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            notifyVideoRect(l, t, r - l, b - t)
        }
        render.getView().post { notifyVideoRect() }
    }

    protected fun startPrepare(reset: Boolean) {
        startPrepare(reset, false)
    }

    protected fun startPrepare(reset: Boolean, rebindRenderView: Boolean) {
        if (reset) {
            kernelPlayer?.reset()
            setOptions()
            if (rebindRenderView) {
                kernelPlayer?.let { player -> activeRenderView?.attachToPlayer(player) }
            }
        }
        if (prepareDataSource()) {
            kernelPlayer?.let { player ->
                player.setStartPosition(playbackPosition)
                player.prepareAsync()
            }
            reportPlayState()
            setPlayerState(PLAYER_NORMAL)
        }
    }

    protected open fun prepareDataSource(): Boolean {
        val url = playUrl
        if (!url.isNullOrEmpty()) {
            kernelPlayer?.setDataSource(url, requestHeaders)
            return true
        }
        return false
    }

    protected fun startInPlaybackState() {
        if (kernelPlayer?.start() != true) return
        reportPlayState()
        if (!isMute()) {
            audioFocusHelper?.requestFocus()
        }
        playerFrame.keepScreenOn = true
    }

    open fun pause() {
        if (kernelPlayer?.pause() != true) return
        reportPlayState()
        if (!isMute()) {
            audioFocusHelper?.abandonFocus()
        }
        playerFrame.keepScreenOn = false
    }

    open fun resume() {
        if (isInPlaybackState() && kernelPlayer?.isPlaying == false) {
            resumePlay()
        }
    }

    private fun resumePlay() {
        if (kernelPlayer?.start() != true) return
        reportPlayState()
        if (!isMute()) {
            audioFocusHelper?.requestFocus()
        }
        playerFrame.keepScreenOn = true
    }

    open fun stopPlaybackKeepPlayer() {
        if (kernelPlayer?.stop() != true) return
        reportPlayState()
    }

    open fun saveCurrentProgress() {
        saveProgress()
    }

    open fun release() {
        val hadActiveState = lastReportedPlayState != PlayState.IDLE
        if (hadActiveState) captureLivePosition()
        audioFocusHelper?.abandonFocus()
        audioFocusHelper = null
        kernelPlayer?.release()
        kernelPlayer = null
        if (hadActiveState) {
            activeRenderView?.let { render ->
                playerFrame.removeView(render.getView())
                render.release()
            }
            activeRenderView = null
            playerFrame.keepScreenOn = false
            saveProgress()
            playbackPosition = 0
            reportPlayState()
        }
        videoSizePx[0] = 0
        videoSizePx[1] = 0
    }

    protected fun saveProgress() {
        val sink = progressSinkRef ?: return
        captureLivePosition()
        if (playbackPosition > 0) {
            AppLog.d("AppPlayerView", "saveProgress: " + playbackPosition)
            sink.saveProgress(progressKey(), playbackPosition)
        }
    }

    private fun captureLivePosition() {
        if (!isInPlaybackState()) return
        val live = kernelPlayer?.currentPosition ?: 0L
        if (live > 0) playbackPosition = live
    }

    open fun resumePositionForReplay(fallback: Long): Long {
        if (isInPlaybackState()) {
            val live = kernelPlayer?.currentPosition ?: 0L
            if (live > 0) return live
        }
        if (playbackPosition > 0) return playbackPosition
        return fallback
    }

    protected fun progressKey(): String? = progressRecordKey ?: playUrl

    protected fun isInPlaybackState(): Boolean =
        kernelPlayer?.playState?.isInPlaybackState == true

    protected fun isInIdleState(): Boolean =
        (kernelPlayer?.playState ?: PlayState.IDLE) == PlayState.IDLE

    private fun isInStartAbortState(): Boolean = kernelPlayer?.playState == PlayState.START_ABORT

    private val kernelEventListener = object : KernelPlayer.Listener {

        override fun onPrepared() {
            val player = kernelPlayer ?: return
            if (playbackPosition > 0 && !player.isStartPositionApplied()) {
                player.seekTo(playbackPosition)
            }
            dispatchPlayState(PlayState.PREPARED)
            if (!isMute()) {
                audioFocusHelper?.requestFocus()
            }
        }

        override fun onKernelPlayStateChanged() {
            reportPlayState()
        }

        override fun onInfo(what: Int, extra: Int) {
            when (what) {
                KernelPlayer.MEDIA_INFO_BUFFERING_START -> reportPlayState()

                KernelPlayer.MEDIA_INFO_BUFFERING_END -> reportPlayState()

                KernelPlayer.MEDIA_INFO_RENDERING_START -> {
                    reportPlayState()
                    if (kernelPlayer?.playState == PlayState.PLAYING) {
                        playerFrame.keepScreenOn = true
                    }
                }

                KernelPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED ->
                    activeRenderView?.setVideoRotation(extra)
            }
        }

        override fun onError() {
            playerFrame.keepScreenOn = false
            reportPlayState()
        }

        override fun onCompletion() {
            playerFrame.keepScreenOn = false
            playbackPosition = 0
            progressSinkRef?.saveProgress(progressKey(), 0L)
            reportPlayState()
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            videoSizePx[0] = width
            videoSizePx[1] = height
            onVideoSizeReported(width, height)
            playController?.onVideoSizeChanged(width, height)
            activeRenderView?.let { render ->
                render.setScaleType(currentScaleType)
                render.setVideoSize(width, height)
            }
        }
    }

    protected open fun onVideoSizeReported(width: Int, height: Int) = Unit

    @get:JvmName("getMediaPlayer")
    val mediaPlayer: KernelPlayer?
        get() = kernelPlayer

    open val duration: Long
        get() = if (isInPlaybackState()) kernelPlayer?.duration ?: 0L else 0L

    open val currentPosition: Long
        get() {
            if (isInPlaybackState()) {
                playbackPosition = kernelPlayer?.currentPosition ?: 0L
                return playbackPosition
            }
            return 0
        }

    open fun seekTo(pos: Long) {
        if (isInPlaybackState()) {
            kernelPlayer?.seekTo(pos)
        }
    }

    open fun selectTrack(track: TrackInfoBean) {
        kernelPlayer?.setTrack(track)
        reportPlayState()
    }

    open val isPlaying: Boolean
        get() = isInPlaybackState() && kernelPlayer?.isPlaying == true

    val bufferedPercentage: Int
        get() = kernelPlayer?.bufferedPercentage ?: 0

    open val tcpSpeed: Long
        get() = kernelPlayer?.tcpSpeed ?: 0L

    open fun setSpeed(speed: Float) {
        if (isInPlaybackState()) kernelPlayer?.setSpeed(speed)
    }

    open val speed: Float
        get() = if (isInPlaybackState()) kernelPlayer?.speed ?: 1f else 1f

    open fun isMute(): Boolean = false

    open fun setUrl(url: String) {
        setUrl(url, null)
    }

    open fun setUrl(url: String, headers: Map<String, String>?) {
        playUrl = url
        requestHeaders = headers
        contentGeneration++
        videoSizePx[0] = 0
        videoSizePx[1] = 0
        playController?.onVideoSizeCleared()
    }

    /** 当前已下发给播放器的地址，用于判断播放器是否已完成内容切换。 */
    val currentUrl: String?
        get() = playUrl

    val contentGen: Int
        get() = contentGeneration

    open fun forgetVideoSize() {
        videoSizePx[0] = 0
        videoSizePx[1] = 0
    }

    open fun setProgressKey(key: String?) {
        progressRecordKey = key
    }

    fun markZeroStartKeys(keys: List<String>) {
        if (ZeroStartGate.mark(zeroStartKeys, keys)) {
            AppLog.i("echo-zero-start mark n=" + keys.size + " pending=" + zeroStartKeys.size)
        }
    }

    open fun setProgressSink(sink: ProgressSink?) {
        progressSinkRef = sink
    }

    open fun skipPositionWhenPlay(position: Int) {
        playbackPosition = position.toLong()
    }

    open fun replay(resetPosition: Boolean) {
        val zeroStart = ZeroStartGate.consume(zeroStartKeys, progressKey())
        if (zeroStart) {
            AppLog.i("echo-zero-start apply key=" + progressKey())
        }
        if (resetPosition || zeroStart) {
            playbackPosition = 0
        }
        val player = kernelPlayer
        if (player == null) {
            AppLog.i("replay() called without kernel, fallback to start()")
            start()
            return
        }
        if (enableAudioFocus) {
            audioFocusHelper?.onNewPlayback()
        }
        player.resetTrackSelection()
        if (player.keepRenderViewOnReset()) {
            player.reset()
            setOptions()
            player.setOptions()
            startPrepare(false)
        } else {
            startPrepare(true, true)
        }
    }

    open fun factoryRenderType(): Int =
        if (renderFactory is EngineTextureRenderViewFactory) 0 else 1

    open fun setRenderViewFactory(factory: PlayerRenderViewFactory) {
        renderFactory = factory
    }

    open fun needsRenderRebuild(targetRenderType: Int): Boolean {
        val render = activeRenderView ?: return false
        return (targetRenderType == 1) != (render.getView() is SurfaceView)
    }

    open fun setScreenScaleType(screenScaleType: Int) {
        currentScaleType = screenScaleType
        activeRenderView?.setScaleType(screenScaleType)
    }

    open val videoSize: IntArray
        get() = videoSizePx

    fun currentPlayerState(): Int = playerStateCode

    @Suppress("UNUSED_PARAMETER")
    open fun setMute(isMute: Boolean) = Unit

    private fun dispatchPlayState(playState: PlayState) {
        lastReportedPlayState = playState
        playController?.setPlayState(playState)
        if (!_playStateFlow.tryEmit(playState)) {
            AppLog.e("echo-player playState-flow-drop: " + playState)
        }
    }

    private fun reportPlayState() {
        val state = kernelPlayer?.playState ?: PlayState.IDLE
        if (state == lastReportedPlayState) return
        dispatchPlayState(state)
    }

    protected fun setPlayerState(playerState: Int) {
        playerStateCode = playerState
        playController?.setPlayerState(playerState)
    }

    interface VideoControllerHost {
        fun setPlayState(playState: PlayState)

        fun setPlayerState(playerState: Int)

        fun onVideoSizeChanged(width: Int, height: Int)

        fun onVideoSizeCleared()

        fun startProgress()

        /**
         * 控制器挂载到播放器容器上的 View。
         *
         * 默认返回控制器自身（直播等仍以 View 实现的控制器无需覆写）；
         * 纯 Compose 控制器本身不是 View，应覆写此方法返回其 ComposeView 宿主。
         */
        fun controllerView(): View? = this as? View

        fun onVideoRectChanged(left: Int, top: Int, width: Int, height: Int) = Unit
    }

    protected var playController: VideoControllerHost? = null

    open val videoController: VideoControllerHost?
        get() = playController

    open fun setVideoController(controller: VideoControllerHost?) {
        val old = playController
        old?.controllerView()?.let { playerFrame.removeView(it) }
        playController = controller
        lastVideoRect = null
        if (controller != null) {
            controller.controllerView()?.let { attach ->
                playerFrame.addView(
                    attach,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }
            val state = kernelPlayer?.playState ?: PlayState.IDLE
            controller.setPlayState(state)
            controller.setPlayerState(playerStateCode)
            if (state != PlayState.IDLE && state != PlayState.ERROR) {
                controller.startProgress()
            }
            notifyVideoRect()
        }
    }

    private var lastVideoRect: IntArray? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        notifyVideoRect()
    }

    private fun notifyVideoRect() {
        val view = activeRenderView?.getView() ?: return
        notifyVideoRect(view.left, view.top, view.width, view.height)
    }

    private fun notifyVideoRect(left: Int, top: Int, width: Int, height: Int) {
        val controller = playController ?: return
        if (width <= 0 || height <= 0) return
        val rect = intArrayOf(left, top, width, height)
        if (rect.contentEquals(lastVideoRect)) return
        lastVideoRect = rect
        AppLog.i("echo-render-rect:" + left + "," + top + ":" + width + "x" + height)
        controller.onVideoRectChanged(left, top, width, height)
    }

    open fun releaseController(): VideoControllerHost? {
        val old = playController
        setVideoController(null)
        return old
    }

    open fun isFullScreen(): Boolean = false

    open fun togglePlay() {
        if (isPlaying) pause() else start()
    }

    open fun doScreenShot(): Bitmap? = activeRenderView?.doScreenShot()

    open fun onBackPressed(): Boolean = false

    open fun attachContainerTo(host: ViewGroup?) {
        if (host == null) return
        val parent = playerFrame.parent as? ViewGroup
        if (parent === host) return
        kernelPlayer?.detachVideoSurface()
        parent?.removeView(playerFrame)
        val lp = playerFrame.layoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        host.addView(playerFrame, 0, lp)
    }

    open fun detachContainerFromHost() {
        val parent = playerFrame.parent as? ViewGroup
        if (parent == null || parent === this) return
        kernelPlayer?.detachVideoSurface()
        parent.removeView(playerFrame)
        addView(
            playerFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    open fun isContainerAttachedTo(host: ViewGroup?): Boolean =
        host != null && playerFrame.parent === host

    open fun playerContainer(): FrameLayout = playerFrame

    open fun bringControllerToFront() {
        playController?.controllerView()?.bringToFront()
    }

    fun hostActivity(): Activity? = PlayerUtils.scanForActivity(context)

    open val renderIsSurface: Boolean
        get() = activeRenderView?.getView() is SurfaceView

    protected fun renderView(): PlayerRenderView? = activeRenderView

    protected fun renderViewFactory(): PlayerRenderViewFactory = renderFactory

    override fun onSaveInstanceState(): Parcelable? {
        saveProgress()
        return super.onSaveInstanceState()
    }

    companion object {

        const val SCREEN_SCALE_DEFAULT = 0
        const val SCREEN_SCALE_16_9 = 1
        const val SCREEN_SCALE_4_3 = 2
        const val SCREEN_SCALE_MATCH_PARENT = 3
        const val SCREEN_SCALE_ORIGINAL = 4
        const val SCREEN_SCALE_CENTER_CROP = 5

        const val PLAYER_NORMAL = ScreenMode.PLAYER_NORMAL
        const val PLAYER_FULL_SCREEN = ScreenMode.PLAYER_FULL_SCREEN
        const val PLAYER_TINY_SCREEN = ScreenMode.PLAYER_TINY_SCREEN

        private const val PLAY_STATE_FLOW_BUFFER = 8
    }
}
