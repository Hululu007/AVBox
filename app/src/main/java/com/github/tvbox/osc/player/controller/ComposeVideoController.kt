package com.github.tvbox.osc.player.controller

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.media3.ui.SubtitleView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.util.GestureHelper
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.player.ui.PlayerOverlay
import com.github.tvbox.osc.player.usecase.M3u8PurifyUseCase
import com.github.tvbox.osc.player.usecase.PlayerSwitchUseCase
import com.github.tvbox.osc.player.usecase.WebParseUseCase
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PlaybackProgress
import org.greenrobot.eventbus.EventBus
import org.json.JSONException
import org.json.JSONObject
import xyz.doikki.videoplayer.controller.BaseVideoController
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import kotlin.math.abs

/**
 * 点播控制层 Compose 实现（Compose 化改造 §3.3 方案 C1，阶段 8 起为唯一控制层实现）。
 *
 * 结构：
 * - 继承 [BaseVideoController]，`getLayoutId() = 0`（不 inflate XML）；
 * - UI 由 [PlayerOverlay]（Compose）渲染，颜色/字号跟随 MaterialTheme，顶部/底栏补 scrim；
 * - 原生字幕视图（§5.4）作为控制器直接子 View 保留，位于 Compose 层之下；
 * - 事件经控制器钩子（onPlayStateChanged/setProgress/onLockStateChanged/onVisibilityChanged）
 *   汇入 [PlayerUiState]（替代旧 Handler msg 100/1000-1004 + myHandle 两套异步）；
 * - 手势照抄 BaseController（§4.4 方案 A：GestureDetector 保留）+ VodController 扩展
 *   （竖屏上下滑切集、长按 3.0x、单击显隐底栏、锁屏触摸守卫）；
 * - 对外契约通过 [PlayerControlApi] 对 PlayContainer / DanmuLoadController 等价（§5.1/§5.2）。
 */
@Suppress("MemberVisibilityCanBePrivate")
class ComposeVideoController @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseVideoController(context, attrs, defStyleAttr), PlayerControlApi, PlayerActions,
    GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {

    companion object {
        /**
         * 横滑进度灵敏度:全屏宽 = 240000ms(即 4 分钟)。
         * 原为 120000ms(照抄 GestureVideoController/BaseController),2026-09-13 用户要求
         * "调钝一点不要太灵敏" → 翻倍:同样时间跨度需要滑动两倍距离(约 1dp ≈ 0.58s)。
         * 手感仍嫌灵敏就继续调大此值,嫌迟钝就调回 120000f。
         */
        private const val SLIDE_POSITION_FULL_WIDTH_MS = 240000f
        /** 锁屏图标 3s 后隐藏 */
        private const val LOCK_HIDE_DELAY_MS = 3000L
        /** BugReview #32:倍速应用重试上限(100ms×30 = 3s),防长期不进播放态时主线程空转 */
        private const val SPEED_RETRY_MAX = 30
        /** SeekBar max 照搬旧布局 android:max="1000" */
        private const val SEEK_MAX = 1000
    }

    // ============================================================
    // 状态与桥接（initView 内赋值：super 构造期间属性初始化器尚未执行）
    // ============================================================

    private lateinit var state: PlayerUiState

    // —— 原生字幕视图（§5.4：PlayContainer 直接操作，保留 View 引用） ——
    private lateinit var mSubtitleView: SimpleSubtitleView
    private lateinit var mLyricView: SimpleSubtitleView
    private lateinit var mExoSubtitleView: SubtitleView

    // —— 手势引擎字段（照抄 BaseController） ——
    private var gestureDetector: GestureDetector? = null
    private var audioManager: AudioManager? = null
    private var isGestureEnabled = true
    private var streamVolume = 0
    private var brightness = 0f
    private var mSeekPosition = -1
    private var firstTouch = false
    private var changePosition = false
    private var changeBrightness = false
    private var changeVolume = false
    private var canChangePosition = true
    private var enableInNormal = false
    private var canSlide = false
    private var curPlayState = 0
    private var isDoubleTapTogglePlayEnabled = true

    // —— 控制层行为字段（照抄 VodController） ——
    private var previewMode = false
    private var fromLongPress = false
    private var speedOld = 1.0f
    /** BugReview #32:倍速应用重试计数 */
    private var speedRetryCount = 0
    private var skipEnd = true
    private var isClickBackBtn = false
    private var showParseFlag = false
    private var playerConfig: JSONObject? = null
    private var listener: VodControlListener? = null

    // 方向键/滚轮步进 seek 的累计进度与提交去抖
    private var keySeekProgress = 0

    // 底栏闲置隐藏（旧 myHandleSeconds：注释写6秒实为10秒，照搬）
    private val idleHideMillis = 10000L

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private val idleHideRunnable by lazy { Runnable { hideBottom() } }
    private val lockHideRunnable by lazy { Runnable { state.lockState = LockVisibility.HIDDEN } }
    private val keySeekCommitRunnable by lazy { Runnable { commitKeySeek() } }
    private val speedRetryRunnable by lazy { Runnable { applySpeedWhenReady() } }

    private val m3u8PurifyUseCase by lazy {
        M3u8PurifyUseCase(context, object : M3u8PurifyUseCase.Callback {
            override fun startPlayUrl(url: String?, headers: HashMap<String, String>?) {
                listener?.startPlayUrl(url ?: return, headers)
            }

            override fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?) {
                listener?.onM3u8ProxyUrl(proxyUrl ?: return, sourceUrl ?: return)
            }
        })
    }
    private val webParseUseCase by lazy { WebParseUseCase() }

    // FastClickCheckUtil 等价：同一动作 500ms 内只生效一次
    private val fastClickMap = HashMap<String, Long>()

    private fun fastClickAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = fastClickMap[key] ?: 0L
        if (now - last < 500) return false
        fastClickMap[key] = now
        return true
    }

    // ============================================================
    // 生命周期 / 初始化
    // ============================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        super.initView()
        state = PlayerUiState()

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        gestureDetector = GestureDetector(context, this)
        setOnTouchListener(this)

        initNativeSubtitleViews()
        initComposeLayer()

        // —— 初始状态（对齐旧 initView 屏显初始化） ——
        val display = KV.get(HawkConfig.SCREEN_DISPLAY, View.GONE)
        state.screenDisplayOn = display == View.VISIBLE
        state.topRightVisible = display == View.VISIBLE
        state.sysTimeVisible = display == View.VISIBLE
        state.netSpeedSideVisible = display == View.VISIBLE
        state.seekTimeVisible = display == View.VISIBLE
        state.isPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        updateDanmuBtnState()
        updateDanmuSearchBtnState()
        initSubtitleInfo()
    }

    override fun getLayoutId(): Int = 0

    /** 原生字幕视图（z-order：Compose 层之下，与旧布局一致） */
    private fun initNativeSubtitleViews() {
        val vs5 = resources.getDimensionPixelSize(R.dimen.vs_5)
        val vs15 = resources.getDimensionPixelSize(R.dimen.vs_15)
        val vs20 = resources.getDimensionPixelSize(R.dimen.vs_20)

        mSubtitleView = SimpleSubtitleView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(vs20, vs15, vs20, vs15)
            visibility = View.VISIBLE
        }
        addView(
            mSubtitleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        mExoSubtitleView = SubtitleView(context).apply { visibility = View.GONE }
        addView(mExoSubtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        mLyricView = SimpleSubtitleView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF00FF00.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textScaleX = 1.1f
            setPadding(vs5, vs20, vs5, vs20)
            visibility = View.GONE
        }
        addView(mLyricView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun initComposeLayer() {
        val composeView = ComposeView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // 全屏 reparent(DecorView) 后 detach 时 composition 会释放并重建，
            // 状态全部保存在 PlayerUiState（控制器持有），重建无感（§7.6）
            setContent {
                // 视频覆盖层挂在纯黑播放页:状态栏图标外观仍由宿主 Activity 断言,主题不接管
                AVBoxTheme(manageStatusBarIcons = false) {
                    PlayerOverlay(state, this@ComposeVideoController)
                }
            }
        }
        addView(composeView)
    }

    private fun initSubtitleInfo() {
        mSubtitleView.setTextSize(SubtitleHelper.getTextSize(mActivity).toFloat())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(idleHideRunnable)
        uiHandler.removeCallbacks(lockHideRunnable)
        uiHandler.removeCallbacks(keySeekCommitRunnable)
        uiHandler.removeCallbacks(speedRetryRunnable)
    }

    // ============================================================
    // dkplayer 事件钩子 → PlayerUiState
    // ============================================================

    override fun setPlayState(playState: Int) {
        super.setPlayState(playState)
        curPlayState = playState
    }

    override fun setPlayerState(playerState: Int) {
        super.setPlayerState(playerState)
        state.playerState = playerState
        if (playerState == VideoView.PLAYER_NORMAL) {
            canSlide = enableInNormal
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            canSlide = true
        }
    }

    override fun onPlayStateChanged(playState: Int) {
        super.onPlayStateChanged(playState)
        curPlayState = playState
        state.playState = playState
        // 时长可信的状态才对齐:同片接管/页面重挂只回灌当前状态(PAUSED/PLAYING),不会再有 PREPARED;
        // PREPARING 要排除 —— IJK 此时时长读作 0,会把点播误判成直播源而隐藏倍速/片头尾
        if (playState != VideoView.STATE_IDLE && playState != VideoView.STATE_ERROR &&
            playState != VideoView.STATE_PREPARING
        ) {
            updateLiveButtonsState()
        }
        when (playState) {
            VideoView.STATE_IDLE -> {
                savePlaybackProgress(notifyHistory = true)
                state.locked = false
                state.duration = 0
                state.position = 0
            }
            VideoView.STATE_PLAYING -> {
                initOrientationState()
                startProgress()
            }
            VideoView.STATE_PAUSED -> {
                state.topLeftVisible = false
                state.netSpeedTopRightVisible = false
                if (state.controlsVisible) hideBottom()
                savePlaybackProgress(notifyHistory = true)
            }
            VideoView.STATE_ERROR -> listener?.errReplay()
            VideoView.STATE_PREPARED -> listener?.prepared()
            VideoView.STATE_PLAYBACK_COMPLETED -> {
                savePlaybackProgress(notifyHistory = true)
                listener?.playNext(true)
            }
        }
    }

    override fun onLockStateChanged(isLocked: Boolean) {
        super.onLockStateChanged(isLocked)
        state.locked = isLocked
    }

    override fun onVisibilityChanged(isVisible: Boolean, anim: Animation?) {
        super.onVisibilityChanged(isVisible, anim)
        state.showing = isVisible
    }

    override fun setProgress(duration: Int, position: Int) {
        if (state.dragging) return
        super.setProgress(duration, position)
        state.duration = duration
        state.position = position
        PlaybackProgress.onProgress(position, duration)
        // 片尾自动跳下一集（skipEnd 防重，照搬）
        if (skipEnd && position != 0 && duration != 0) {
            val et = playerConfig?.optInt("et", 0) ?: 0
            if (et > 0 && position + et * 1000 >= duration) {
                skipEnd = false
                listener?.playNext(true)
            }
        }
        state.seekTimeText = formatSeekTime(position) + " | " + formatSeekTime(duration)
        state.bufferedPercent = runCatching { mControlWrapper?.bufferedPercentage ?: 0 }.getOrDefault(0)
    }

    private fun savePlaybackProgress(notifyHistory: Boolean) {
        val wrapperDuration = runCatching { mControlWrapper?.duration ?: 0L }.getOrDefault(0L).toInt()
        val wrapperPosition = runCatching { mControlWrapper?.currentPosition ?: 0L }.getOrDefault(0L).toInt()
        val duration = if (wrapperDuration > 0) wrapperDuration else state.duration
        val position = if (wrapperDuration > 0) wrapperPosition else state.position
        if (duration <= 0) return
        PlaybackProgress.flush(position, duration)
        // 值没变也必须通知:周期写入早已落盘,历史页手里的可能是进播放前的旧快照
        if (notifyHistory) EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    /** seek 提示（替代旧 updateSeekUI + msg 1000/1001，UI 侧 1s 自动隐藏） */
    private fun updateSeekUiHint(curr: Int, seekTo: Int, duration: Int) {
        state.seekHintForward = seekTo > curr
        state.seekHintText = PlayerUtils.stringForTime(seekTo) + " / " + PlayerUtils.stringForTime(duration)
        state.seekHintVisible = true
    }

    private fun formatSeekTime(timeMs: Int): String {
        val totalSeconds = timeMs.coerceAtLeast(0) / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60 % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun isInPlaybackState(): Boolean {
        return mControlWrapper != null &&
                curPlayState != VideoView.STATE_ERROR &&
                curPlayState != VideoView.STATE_IDLE &&
                curPlayState != VideoView.STATE_PREPARING &&
                curPlayState != VideoView.STATE_PREPARED &&
                curPlayState != VideoView.STATE_START_ABORT &&
                curPlayState != VideoView.STATE_PLAYBACK_COMPLETED
    }

    private fun canHandleGesture(event: MotionEvent): Boolean {
        return isInPlaybackState() &&
                isGestureEnabled &&
                canSlide &&
                !isLocked() &&
                !PlayerUtils.isEdge(context, event)
    }

    /**
     * 是否允许"上下滑调亮度/音量"(2026-09-13「禁用手势控制」设置项)。
     *
     * <p>与 [canHandleGesture] 分开是本设置项的硬要求:第一版把设置并进 `canHandleGesture`,
     * 会连带把别的滑动手势一起禁掉。
     */
    private fun canChangeBrightnessVolume(event: MotionEvent): Boolean {
        return canHandleGesture(event) && !GestureHelper.isControlDisabled()
    }

    override fun onDown(e: MotionEvent): Boolean {
        if (!isInPlaybackState() || !isGestureEnabled || PlayerUtils.isEdge(context, e)) {
            return true
        }
        streamVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val activity = PlayerUtils.scanForActivity(context)
        brightness = if (activity == null) 0f else activity.window.attributes.screenBrightness
        firstTouch = true
        changePosition = false
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
        if (previewMode) return true
        if (!canHandleGesture(e1)) return true
        val deltaX = e1.x - e2.x
        val deltaY = e1.y - e2.y
        if (firstTouch) {
            changePosition = abs(distanceX) >= abs(distanceY)
            if (!changePosition) {
                // 禁用手势控制:竖向滑动既不进度也不亮度/音量 —— 静默忽略,不给出任何反馈
                if (!canChangeBrightnessVolume(e1)) return true
                val halfScreen = PlayerUtils.getScreenWidth(context, true) / 2
                if (e2.x > halfScreen) changeVolume = true else changeBrightness = true
            }
            if (changePosition) changePosition = canChangePosition
            firstTouch = false
        }
        if (changePosition) {
            slideToChangePosition(deltaX)
        } else if (changeBrightness) {
            slideToChangeBrightness(deltaY)
        } else if (changeVolume) {
            slideToChangeVolume(deltaY)
        }
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        toggleControls()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        // 预览态（竖屏详情页）同样支持双击暂停/播放（2026-09-13 用户要求；旧版此态只放行单击显隐）。
        // ⚠️ GestureDetector 语义下单击显隐要等双击窗口超时（~300ms）才确认，是双击功能的固有代价。
        if (isDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()) {
            mControlWrapper?.togglePlay()
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        if (previewMode) return
        if (curPlayState != VideoView.STATE_PAUSED) {
            speedPlayStart()
        }
    }

    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false

    /** 锁屏触摸守卫 + 手势分发（旧 rootView OnTouch 与 BaseController.OnTouch 合并，行为等价） */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (previewMode) {
            // 预览态（竖屏详情页）：详情页透明点击层已移除，触摸直接落到控制器；
            // 放行单击显隐（onSingleTapConfirmed → toggleControls）与双击暂停/播放（onDoubleTap）；
            // 滑动/长按等其余手势在预览态不响应（对齐旧版预览态行为）
            return gestureDetector?.onTouchEvent(event) ?: false
        }
        if (isLocked()) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                showLockView()
            }
            return true
        }
        return gestureDetector?.onTouchEvent(event) ?: false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // BugReview #16:CANCEL(来电浮窗/下拉通知栏/父容器拦截)时也要结束倍速,
        // 否则长按 3.0x 永不恢复
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> speedPlayEnd()
        }
        if (gestureDetector?.onTouchEvent(event) != true) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (mSeekPosition >= 0) {
                        mControlWrapper?.seekTo(mSeekPosition.toLong())
                        mSeekPosition = -1
                    }
                }
                MotionEvent.ACTION_CANCEL -> mSeekPosition = -1
            }
        }
        return super.onTouchEvent(event)
    }

    private fun slideToChangePosition(deltaX: Float) {
        val width = measuredWidth
        if (width <= 0) return
        val wrapper = mControlWrapper ?: return
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        val currentPosition = PlayerUtils.safeTimeMs(wrapper.currentPosition)
        var position = (-deltaX / width * SLIDE_POSITION_FULL_WIDTH_MS + currentPosition).toInt()
        if (position > duration) position = duration
        if (position < 0) position = 0
        updateSeekUiHint(currentPosition, position, duration)
        mSeekPosition = position
    }

    private fun slideToChangeBrightness(deltaY: Float) {
        val activity = PlayerUtils.scanForActivity(context) ?: return
        val window = activity.window
        val attributes = window.attributes
        val height = measuredHeight
        if (height <= 0) return
        if (brightness == -1.0f) brightness = 0.5f
        var newBrightness = deltaY * 2 / height + brightness
        if (newBrightness < 0) newBrightness = 0f
        if (newBrightness > 1.0f) newBrightness = 1.0f
        val percent = (newBrightness * 100).toInt()
        attributes.screenBrightness = newBrightness
        window.attributes = attributes
        state.slideHintText = context.getString(R.string.player_brightness_value, percent)
        state.slideHintVisible = true
    }

    private fun slideToChangeVolume(deltaY: Float) {
        val am = audioManager ?: return
        val height = measuredHeight
        if (height <= 0) return
        val streamMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val deltaV = deltaY * 2 / height * streamMaxVolume
        var index = streamVolume + deltaV
        if (index > streamMaxVolume) index = streamMaxVolume.toFloat()
        if (index < 0) index = 0f
        val percent = (index / streamMaxVolume * 100).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        state.slideHintText = context.getString(R.string.player_volume_value, percent)
        state.slideHintVisible = true
    }

    // ============================================================
    // 底栏显隐（替代 msg 1002/1003 与 myHandle 闲置计时）
    // ============================================================

    override fun toggleControls() {
        if (!state.controlsVisible) showBottom() else hideBottom()
    }

    /** 详情页预览态点击视频区（PlayerControlApi 契约），与全屏单击同一切换逻辑 */
    override fun toggleControlBar() {
        toggleControls()
    }

    private fun showBottom() {
        applyShowBottom()
    }

    /** 等价旧 msg 1002（逐行照搬可见性规则，§7.8） */
    private fun applyShowBottom() {
        updateDanmuSearchBtnState()
        state.controlsVisible = true
        state.topLeftVisible = true
        state.topRightVisible = true
        state.netSpeedTopRightVisible = true
        if (!state.screenDisplayOn) {
            state.sysTimeVisible = true
        } else {
            state.netSpeedSideVisible = false
        }
        state.backVisible = !state.isPortrait
        showLockView()
        keepControlsAlive()
    }

    /** 等价旧 msg 1003 */
    fun hideBottom() {
        uiHandler.removeCallbacks(idleHideRunnable)
        // 拖拽/按键步进中把底栏移出组合会吞掉 onDragCancel（Compose 手势随组合销毁），
        // dragging 卡死会让 setProgress 永久早退、进度显示冻结，先复位
        if (state.dragging) onSeekCancelled()
        state.controlsVisible = false
        state.topLeftVisible = false
        state.netSpeedTopRightVisible = false
        if (!state.screenDisplayOn) {
            state.sysTimeVisible = false
        } else {
            state.netSpeedSideVisible = true
        }
        state.backVisible = false
        uiHandler.removeCallbacks(lockHideRunnable)
        if (state.lockState != LockVisibility.GONE) {
            state.lockState = LockVisibility.HIDDEN
        }
    }

    override fun keepControlsAlive() {
        if (state.controlsVisible) {
            uiHandler.removeCallbacks(idleHideRunnable)
            uiHandler.postDelayed(idleHideRunnable, idleHideMillis)
        }
    }

    private fun showLockView() {
        if (previewMode) {
            setLocked(false)
            uiHandler.removeCallbacks(lockHideRunnable)
            state.lockState = LockVisibility.GONE
            return
        }
        state.lockState = LockVisibility.SHOWN
        uiHandler.removeCallbacks(lockHideRunnable)
        if (isLocked()) {
            uiHandler.postDelayed(lockHideRunnable, LOCK_HIDE_DELAY_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initOrientationState()
    }

    private fun initOrientationState() {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        state.isPortrait = isPortrait
        if (isPortrait) {
            state.backVisible = false
        }
    }

    private fun updatePlayerCfgState() {
        val cfg = playerConfig ?: return
        try {
            val playerType = cfg.getInt("pl")
            state.playerType = playerType
            state.playerBtnText = PlayerHelper.getPlayerName(playerType)
            state.scaleBtnText = PlayerHelper.getScaleName(cfg.getInt("sc"))
            // 解码文案按当前内核读各自的键(2026-09-17):IJK 读 cfg.ijk,EXO 读 cfg.exo
            val codecName = cfg.optString(if (playerType == 2) "exo" else "ijk", "硬解码") // i18n: keep
            state.ijkBtnText = when (codecName) {
                // i18n: keep —— 解码取值是数据键,只显示走资源
                "硬解码" -> context.getString(R.string.player_decode_hard_short)
                "软解码" -> context.getString(R.string.player_decode_soft_short) // i18n: keep
                else -> codecName
            }
            state.speedBtnText = cfg.getDouble("sp").toString() + "x"
            val start = cfg.getInt("st")
            val end = cfg.getInt("et")
            state.timeStartText = if (start == 0) context.getString(R.string.player_time_start) else PlayerUtils.stringForTime(start * 1000)
            state.timeEndText = if (end == 0) context.getString(R.string.player_time_end) else PlayerUtils.stringForTime(end * 1000)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun updateDanmuBtnState() {
        state.danmuOpen = DanmuHelper.isOpen()
    }

    private fun updateDanmuSearchBtnState() {
        state.danmuSearchAvailable = ApiConfig.get().hasDanmuSearchUi()
    }

    /** 直播源(duration==0)隐藏倍速/片头尾按钮;取不到时长按可显示处理 */
    private fun updateLiveButtonsState() {
        state.liveButtonsVisible = runCatching { mControlWrapper?.duration ?: 0L != 0L }.getOrDefault(true)
    }

    private fun speedPlayStart() {
        fromLongPress = true
        try {
            val cfg = playerConfig ?: return
            // BugReview #16:倍速提速不入 playerCfg(原实现把 "sp":3.0 经 updatePlayerCfg
            // 持久化,手势被 CANCEL 中断或后续集数会持续 3.0x);只改播放器速度,配置保持原值
            speedOld = cfg.getDouble("sp").toFloat()
            // 长按倍速(2026-09-12):设置页滑块可调 2x~10x,每次长按实时读 KV,改设置立即生效
            val boost = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT).toFloat()
            mControlWrapper?.setSpeed(boost)
            state.speedBoostValue = boost
            state.speedBoostVisible = true
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun speedPlayEnd() {
        if (!fromLongPress) return
        fromLongPress = false
        // 恢复 DOWN 时快照的原速度;cfg 未被修改,无需回写与持久化
        mControlWrapper?.setSpeed(speedOld)
        state.speedBoostVisible = false
    }

    private fun applySpeedWhenReady() {
        if (isInPlaybackState()) {
            speedRetryCount = 0
            try {
                playerConfig?.let { mControlWrapper?.setSpeed(it.getDouble("sp").toFloat()) }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        } else if (speedRetryCount < SPEED_RETRY_MAX) {
            // BugReview #32:重试带上限,播放长期不进 playback 态时不再主线程空转;
            // 后续播放态就绪的事件(resetSpeed/切集)会重新触发应用
            speedRetryCount++
            uiHandler.removeCallbacks(speedRetryRunnable)
            uiHandler.postDelayed(speedRetryRunnable, 100)
        }
    }

    override fun getUiState(): PlayerUiState = state

    override fun getSubtitleView(): SimpleSubtitleView = mSubtitleView

    override fun getLyricView(): SimpleSubtitleView = mLyricView

    override fun getExoSubtitleView(): SubtitleView = mExoSubtitleView

    override fun setListener(l: VodControlListener?) {
        listener = l
    }

    override fun setPlayerConfig(playerCfg: JSONObject) {
        playerConfig = playerCfg
        updatePlayerCfgState()
    }

    override fun showParse(userJxList: Boolean) {
        showParseFlag = userJxList
        state.showParseRow = userJxList
    }

    override fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        state.previewMode = previewMode
        // 退出全屏回预览态时顺带收起全屏残留菜单，避免预览窗仍挂着底栏/中央三键
        if (previewMode && state.controlsVisible) hideBottom()
        uiHandler.removeCallbacks(lockHideRunnable)
        state.lockState = LockVisibility.GONE
    }

    override fun setTitle(playTitleInfo: String) {
        state.title = playTitleInfo
    }

    /** 暂停浮层已删,保留接口兼容 */
    override fun setUrlTitle(playTitleInfo: String) = Unit

    override fun setHasDanmu(hasDanmu: Boolean) {
        updateDanmuBtnState()
    }

    override fun setCanChangePosition(canChangePosition: Boolean) {
        this.canChangePosition = canChangePosition
    }

    override fun setEnableInNormal(enableInNormal: Boolean) {
        this.enableInNormal = enableInNormal
    }

    override fun setGestureEnabled(gestureEnabled: Boolean) {
        isGestureEnabled = gestureEnabled
    }

    /** 旧暂停浮层根已并入 Compose 层,View 版无需隐藏 */
    override fun hidePauseRoot() = Unit

    override fun setLifecyclePaused(paused: Boolean) {
        state.lifecyclePaused = paused
    }

    override fun resetSpeed() {
        skipEnd = true
        applySpeedWhenReady()
    }

    override fun onBackPressed(): Boolean {
        if (isClickBackBtn) {
            isClickBackBtn = false
            if (state.controlsVisible) hideBottom()
            return false
        }
        // 侧边返回手势(YouTube 式)：菜单唤出时先收菜单并消费本次返回，
        // 菜单收起后再滑才交还宿主退出全屏回竖屏详情页
        if (state.controlsVisible) {
            hideBottom()
            return true
        }
        return super.onBackPressed()
    }

    /**
     * 自动重试切换内核(EXO⇄IJK,仅由 PlayContainer.autoRetry 调用)。
     *
     * 只刷新 UI 状态与本次播放配置,**不**调用 listener?.updatePlayerCfg() ——
     * 后者会把自动切换结果写进该剧的播放记录("设置里是 EXO 却永远用 IJK"的根因,2026-09-13 修复);
     * 自动切换是临时容错,只对本次会话生效,下次播放仍先按用户设置/记录尝试。
     * 手动切内核(onPlayerClicked/onPlayerLongClicked)不在此列,仍持久化(按剧记忆语义)。
     */
    override fun switchPlayer(): Boolean {
        val cfg = playerConfig ?: JSONObject()
        return PlayerSwitchUseCase.switchPlayer(cfg) { updatePlayerCfgState() }
    }

    override fun stopOther() {
        PlayerSwitchUseCase.stopOther()
    }

    override fun playM3u8(url: String?, headers: HashMap<String, String>?) {
        m3u8PurifyUseCase.playM3u8(url ?: return, headers)
    }

    override fun encodeUrl(url: String?): String = PlayerSwitchUseCase.encodeUrl(url)

    override fun firstUrlByArray(url: String?): String = PlayerSwitchUseCase.firstUrlByArray(url)

    override fun evaluateScript(sourceBean: SourceBean?, url: String?, view: WebView?) {
        webParseUseCase.evaluateScript(sourceBean, url, view)
    }

    override fun getWebPlayUrlIfNeeded(webPlayUrl: String?): String {
        return webParseUseCase.getWebPlayUrlIfNeeded(webPlayUrl)
    }

    // ============================================================
    // PlayerActions 实现（§5.3 按钮清单）
    // ============================================================

    override fun onNextClicked() {
        listener?.playNext(false)
        hideBottom()
    }

    override fun onPreClicked() {
        listener?.playPre()
        hideBottom()
    }

    override fun onPlayPauseClicked() {
        // 与其余按钮一致 500ms 防抖：触摸误双击＝两次 togglePlay 净零
        if (!fastClickAllowed("play_pause")) return
        mControlWrapper?.togglePlay()
        keepControlsAlive()
    }

    override fun onRefreshClicked() {
        listener?.replay(false)
        hideBottom()
    }

    override fun onScaleClicked() {
        keepControlsAlive()
        showScaleDialog()
    }

    override fun onScaleLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("scale_long")) return
        try {
            playerConfig?.put("sc", 0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            mControlWrapper?.setScreenScaleType(0)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onSpeedClicked() {
        keepControlsAlive()
        showSpeedDialog()
    }

    override fun onSpeedLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("speed_long")) return
        try {
            playerConfig?.put("sp", 1.0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            speedOld = 1.0f
            mControlWrapper?.setSpeed(1.0f)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onPlayerClicked() {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            var playerType = cfg.getInt("pl")
            val existPlayerTypes = PlayerHelper.getExistPlayerTypes()
            var playerTypeIdx = 0
            for (i in existPlayerTypes.indices) {
                if (playerType == existPlayerTypes[i]) {
                    playerTypeIdx = if (i == existPlayerTypes.size - 1) 0 else i + 1
                }
            }
            playerType = existPlayerTypes[playerTypeIdx]
            cfg.put("pl", playerType)
            // ⚠️ 必须先于 updatePlayerCfg():它会让"自动切内核"态作废,否则本次落库会被回填成自动切换前的内核
            listener?.setAllowSwitchPlayer(false)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            listener?.replay(false)
            hideBottom()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onPlayerLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("player_long")) return
        try {
            val cfg = playerConfig ?: return
            val playerType = cfg.getInt("pl")
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            val names = ArrayList<String>()
            for (p in players.indices) {
                names.add(PlayerHelper.getPlayerName(players[p]))
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_player),
                items = names,
                defaultIndex = defaultPos,
                onSelected = { pos ->
                    try {
                        val thisPlayType = players[pos]
                        if (thisPlayType != playerType) {
                            cfg.put("pl", thisPlayType)
                            // ⚠️ 必须先于 updatePlayerCfg()(同上:让自动切内核态作废,用户选择才能落库)
                            listener?.setAllowSwitchPlayer(false)
                            updatePlayerCfgState()
                            listener?.updatePlayerCfg()
                            listener?.replay(false)
                            hideBottom()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
            )
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onIjkClicked() {
        try {
            val cfg = playerConfig ?: return
            val playerType = cfg.optInt("pl", 2)
            if (playerType == 2) {
                // EXO(2026-09-17):硬解/软解两个取值直接互切;软解 = 系统软件解码器(c2.android.*)优先,
                // 不看 ApiConfig.ijkCodes —— 那是 IJK 的 options 列表,与 media3 的选择器无关
                val current = cfg.optString("exo", "硬解码") // i18n: keep
                cfg.put("exo", if (current == "软解码") "硬解码" else "软解码") // i18n: keep
            } else {
                var ijk = cfg.getString("ijk")
                val codecs = ApiConfig.get().ijkCodes
                for (i in codecs.indices) {
                    if (ijk == codecs[i].name) {
                        ijk = if (i >= codecs.size - 1) codecs[0].name else codecs[i + 1].name
                        break
                    }
                }
                cfg.put("ijk", ijk)
            }
            // 按剧记忆标记(2026-09-15;2026-09-17 拆成两内核各一个):只有用户**在该内核下**显式选过解码方式,
            // 记录里对应的解码键才优先;否则设置页的新值会一直被播放记录里的旧值压住(见 PlaybackController.initPlayerCfg)
            cfg.put(if (playerType == 2) "exoSet" else "ijkSet", 1)
            // 用户显式选了解码:本次播放不再自动回退软解,自动软解态作废(用户的值要能落库;见 setAllowDecodeFallback)
            listener?.setAllowDecodeFallback(false)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            listener?.replay(false)
            hideBottom()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onTimeStartClicked() {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            val wrapper = mControlWrapper ?: return
            val current = PlayerUtils.safeTimeMs(wrapper.currentPosition)
            val duration = PlayerUtils.safeTimeMs(wrapper.duration)
            if (current > duration / 2) return
            cfg.put("st", current / 1000)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onTimeStartLongClicked() {
        try {
            playerConfig?.put("st", 0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onTimeEndClicked() {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            val wrapper = mControlWrapper ?: return
            val current = PlayerUtils.safeTimeMs(wrapper.currentPosition)
            val duration = PlayerUtils.safeTimeMs(wrapper.duration)
            if (current < duration / 2) return
            cfg.put("et", (duration - current) / 1000)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onTimeEndLongClicked() {
        try {
            playerConfig?.put("et", 0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onTimeResetClicked() {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("et", 0)
            cfg.put("st", 0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onEpisodeClicked() {
        if (!fastClickAllowed("episode")) return
        listener?.showEpisodes()
        hideBottom()
    }

    override fun onCastClicked() {
        listener?.clickCast()
    }

    override fun onSubtitleClicked() {
        if (!fastClickAllowed("zimu")) return
        listener?.selectSubtitle()
        hideBottom()
    }

    override fun onSubtitleLongClicked() {
        if (!fastClickAllowed("zimu_long")) return
        // 照搬旧长按：关闭全部字幕
        try {
            mSubtitleView.visibility = View.GONE
            mSubtitleView.destroy()
            mSubtitleView.clearSubtitleCache()
            mSubtitleView.isInternal = false
            mLyricView.visibility = View.GONE
            mLyricView.destroy()
            mLyricView.clearSubtitleCache()
            mExoSubtitleView.visibility = View.GONE
        } catch (e: Exception) {
            e.printStackTrace()
        }
        hideBottom()
        Toast.makeText(context, context.getString(R.string.player_subtitle_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onAudioTrackClicked() {
        if (!fastClickAllowed("audio")) return
        listener?.selectAudioTrack()
        hideBottom()
    }

    override fun onVideoTrackClicked() {
        if (!fastClickAllowed("video")) return
        listener?.selectVideoTrack()
        hideBottom()
    }

    override fun onDanmuSettingClicked() {
        if (!fastClickAllowed("danmu")) return
        listener?.showDanmuSetting()
    }

    override fun onDanmuSettingLongClicked() {
        if (!fastClickAllowed("danmu_long")) return
        val opened = listener?.toggleDanmu() ?: false
        hideBottom()
        Toast.makeText(context, context.getString(if (opened) R.string.player_danmu_opened else R.string.player_danmu_temp_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onDanmuSearchClicked() {
        listener?.searchDanmuUi(false)
        hideBottom()
    }

    override fun onDanmuSearchLongClicked() {
        listener?.searchDanmuUi(true)
        hideBottom()
    }

    override fun onRotateClicked() {
        if (isLocked()) return
        if (!fastClickAllowed("rotate")) return
        val toPortrait =
            resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        mActivity?.requestedOrientation =
            if (toPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideBottom()
    }

    override fun onScreenDisplayClicked() {
        val newDisplay = if (state.screenDisplayOn) View.GONE else View.VISIBLE
        KV.put(HawkConfig.SCREEN_DISPLAY, newDisplay)
        state.screenDisplayOn = newDisplay == View.VISIBLE
        state.seekTimeVisible = state.screenDisplayOn
        state.netSpeedSideVisible = state.screenDisplayOn
        if (state.screenDisplayOn) state.sysTimeVisible = true
        hideBottom()
    }

    override fun onBackClicked() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            mActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideBottom()
            return
        }
        isClickBackBtn = true
        mActivity?.onBackPressed()
    }

    override fun onLockClicked() {
        val newLocked = !isLocked()
        setLocked(newLocked)
        if (newLocked) hideBottom()
        showLockView()
    }

    override fun onParseSelected(position: Int) {
        val parseBeanList = ApiConfig.get().parseBeanList
        if (position < 0 || position >= parseBeanList.size) return
        val parseBean: ParseBean = parseBeanList[position]
        ApiConfig.get().setDefaultParse(parseBean)
        state.parseListVersion++
        listener?.changeParse(parseBean)
        hideBottom()
    }

    override fun onSeekStarted() {
        if (!state.controlsVisible) applyShowBottom()
        if (state.dragging) return
        state.dragging = true
        mControlWrapper?.stopProgress()
        mControlWrapper?.stopFadeOut()
        keepControlsAlive()
    }

    override fun onSeekPreview(progress: Int) {
        val wrapper = mControlWrapper ?: return
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        state.seekPreviewPositionMs = seekBarToPosition(progress, duration)
    }

    override fun onSeekFinished(progress: Int) {
        keepControlsAlive()
        val wrapper = mControlWrapper
        if (wrapper != null) {
            val duration = PlayerUtils.safeTimeMs(wrapper.duration)
            wrapper.seekTo(seekBarToPosition(progress, duration))
        }
        state.dragging = false
        keySeekProgress = 0
        mControlWrapper?.startProgress()
        mControlWrapper?.startFadeOut()
    }

    override fun onSeekCancelled() {
        state.dragging = false
        keySeekProgress = 0
        mControlWrapper?.startProgress()
        mControlWrapper?.startFadeOut()
    }

    override fun onSeekStep(dir: Int) {
        val wrapper = mControlWrapper ?: return
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        if (duration <= 0) return
        if (!state.controlsVisible) applyShowBottom()
        if (!state.dragging) {
            state.dragging = true
            wrapper.stopProgress()
            wrapper.stopFadeOut()
        }
        keySeekProgress = (keySeekProgress + keySeekIncrement(duration) * dir).coerceIn(0, SEEK_MAX)
        state.seekPreviewPositionMs = seekBarToPosition(keySeekProgress, duration)
        updateSeekUiHint(
            PlayerUtils.safeTimeMs(wrapper.currentPosition),
            state.seekPreviewPositionMs.toInt(),
            duration,
        )
        uiHandler.removeCallbacks(keySeekCommitRunnable)
        uiHandler.postDelayed(keySeekCommitRunnable, 400)
    }

    private fun commitKeySeek() {
        if (!state.dragging) return
        onSeekFinished(keySeekProgress)
    }

    private fun seekBarToPosition(progress: Int, duration: Int): Long {
        if (duration <= 0) return 0L
        return duration.toLong() * progress / SEEK_MAX
    }

    private fun keySeekIncrement(duration: Int): Int {
        val increment: Long = when {
            duration > 3 * 60 * 60 * 1000 -> 5 * 60 * 1000L
            duration > 30 * 60 * 1000 -> 60 * 1000L
            duration > 15 * 60 * 1000 -> 30 * 1000L
            duration > 10 * 60 * 1000 -> 15 * 1000L
            else -> 10 * 1000L
        }
        return maxOf(1, (increment * SEEK_MAX / duration).toInt())
    }


    override fun refreshSystemInfo() {
        val wrapper = mControlWrapper ?: return
        state.sysTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        readBattery()
        val speed = runCatching { wrapper.tcpSpeed }.getOrDefault(0L)
        state.netSpeedTopRight = PlayerHelper.getDisplaySpeed(speed, true)
        state.netSpeedCenter = PlayerHelper.getDisplaySpeed(speed, false)
        val size = runCatching { wrapper.videoSize }.getOrDefault(intArrayOf(0, 0))
        state.videoSize = "" + size[0] + " X " + size[1]
    }

    /** 系统电量与充电状态（读不到/越界时 batteryPercent=-1 不显示） */
    private fun readBattery() {
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            state.batteryPercent = if (level in 0..100) level else -1
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            state.batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    override fun hideSeekHint() {
        state.seekHintVisible = false
    }

    override fun hideSlideHint() {
        state.slideHintVisible = false
    }

    private fun showScaleDialog() {
        try {
            val cfg = playerConfig ?: return
            val scaleType = cfg.getInt("sc")
            val scales = ArrayList<String>()
            for (i in 0..5) {
                scales.add(PlayerHelper.getScaleName(i))
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_scale),
                items = scales,
                defaultIndex = scaleType.coerceIn(0, 5),
                onSelected = { index ->
                    try {
                        cfg.put("sc", index)
                        updatePlayerCfgState()
                        listener?.updatePlayerCfg()
                        mControlWrapper?.setScreenScaleType(index)
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                },
            )
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun showSpeedDialog() {
        try {
            val cfg = playerConfig ?: return
            val speed = cfg.getDouble("sp").toFloat()
            val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
            val speeds = ArrayList<String>()
            for (value in speedOptions) {
                speeds.add(value.toString() + "x")
            }
            var defaultPos = 1
            for (i in speedOptions.indices) {
                if (speedOptions[i] == speed) {
                    defaultPos = i
                    break
                }
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_speed),
                items = speeds,
                defaultIndex = defaultPos,
                onSelected = { index ->
                    try {
                        val value = speedOptions[index]
                        cfg.put("sp", value.toDouble())
                        updatePlayerCfgState()
                        listener?.updatePlayerCfg()
                        speedOld = value
                        mControlWrapper?.setSpeed(value)
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                },
            )
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }
}
