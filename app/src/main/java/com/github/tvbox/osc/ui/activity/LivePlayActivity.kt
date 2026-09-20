@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LivePlayerManager
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.controller.ComposeLiveController
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerHelper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.github.tvbox.osc.util.KV
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

internal class LiveListRow(
    val group: LiveChannelGroup?,
    val channel: LiveChannelItem?,
    val channelPos: Int,
    val key: String,
)

class LivePlayActivity : BaseActivity() {

    companion object {
        private const val TAG = "LivePlayActivity"
        private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
        private const val RESOLUTION_INFO_MAX_RETRY = 10
        private const val RESOLUTION_INFO_RETRY_DELAY = 300L
        private const val RESOLUTION_INFO_HIDE_DELAY = 3000L
        private const val OVERLAY_HIDE_DELAY = 6000L
        private const val CONNECT_TIMEOUT_SWITCH_DELAY = 3500L
        private val FORMAT_DATE1 = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    /** 界面状态与设置项分发都在 ViewModel;同名转发使调用点不用改,转发仍走 snapshot state,Compose 订阅不变 */
    private val vm: LivePlayViewModel by viewModels()

    internal var pageState by VmVar(LivePlayViewModel::pageState)
    internal var playState by VmVar(LivePlayViewModel::playState)
    internal var snapshotVisible by VmVar(LivePlayViewModel::snapshotVisible)
    internal var snapshotBitmap by VmVar(LivePlayViewModel::snapshotBitmap)
    private var fullScreen by VmVar(LivePlayViewModel::fullScreen)
    private var rotating by VmVar(LivePlayViewModel::rotating)
    internal var overlayVisible by VmVar(LivePlayViewModel::overlayVisible)
    internal var isBackState by VmVar(LivePlayViewModel::isBackState)
    internal var epgSheetVisible by VmVar(LivePlayViewModel::epgSheetVisible)
    internal var settingsSheetVisible by VmVar(LivePlayViewModel::settingsSheetVisible)
    internal var passwordDialogTarget by VmVar(LivePlayViewModel::passwordDialogTarget)
    internal var settingsVersion by VmVar(LivePlayViewModel::settingsVersion)
    internal var channelVersion by VmVar(LivePlayViewModel::channelVersion)
    internal var epgVersion by VmVar(LivePlayViewModel::epgVersion)
    internal var scrollTick by VmVar(LivePlayViewModel::scrollTick)
    internal var resolutionText by VmVar(LivePlayViewModel::resolutionText)
    internal var resolutionVisible by VmVar(LivePlayViewModel::resolutionVisible)
    internal var showTimeOn by VmVar(LivePlayViewModel::showTimeOn)
    internal var showNetSpeedOn by VmVar(LivePlayViewModel::showNetSpeedOn)
    internal var timeText by VmVar(LivePlayViewModel::timeText)
    internal var netSpeedText by VmVar(LivePlayViewModel::netSpeedText)
    internal var gestureHintText by VmVar(LivePlayViewModel::gestureHintText)
    internal var tsPosition by VmVar(LivePlayViewModel::tsPosition)
    internal var tsDuration by VmVar(LivePlayViewModel::tsDuration)
    internal var channelInfoUi by VmVar(LivePlayViewModel::channelInfoUi)
    internal val expandedGroups by VmVal(LivePlayViewModel::expandedGroups)

    /**
     * 转发属性读写到 ViewModel。
     * 不能写成 `by vm::x`:绑定属性引用会在 Activity 构造期求值 vm,此时未 attach,getViewModelStore 会抛。
     */
    private inner class VmVar<T>(private val ref: KMutableProperty1<LivePlayViewModel, T>) : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = ref.get(vm)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = ref.set(vm, value)
    }

    private inner class VmVal<T>(private val ref: KProperty1<LivePlayViewModel, T>) : ReadOnlyProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = ref.get(vm)
    }

    internal var mVideoView: MyVideoView? = null
    private var liveController: ComposeLiveController? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private val liveChannelGroupList = ArrayList<LiveChannelGroup>()
    internal var currentChannelGroupIndex = 0
    internal var currentLiveChannelIndex = -1
    internal var currentLiveLookBackIndex = -1
    private var currentLiveChangeSourceTimes = 0
    private var allowLiveSwitchPlayer = true
    private var currentLiveChannelItem: LiveChannelItem? = null
    private var pendingLiveRefreshChannelName: String? = null
    private var pendingLiveRefreshSourceIndex = -1
    private var refreshingLiveChannelList = false
    private val livePlayerManager = LivePlayerManager()
    private val channelGroupPasswordConfirmed = ArrayList<Int>()
    internal var channelName: LiveChannelItem? = null
    internal var epgdata = ArrayList<Epginfo>()
    private var catchup: JsonObject? = null
    private var logoUrl: String? = null
    private var isSHIYI = false
    private var playUrl: String? = null
    private var shiyiTimeC = 0
    private var selectedChannelGroupIndex = 0
    private var resolutionInfoRetryCount = 0
    private var resolutionInfoPending = false
    private var exitingLivePlay = false
    private var loadingLiveConfigOnEnter = false
    private var liveSettingGroupList: List<LiveSettingGroup> = ArrayList()
    private var nowday = Date()

    /** EPG 取数与缓存;列表状态仍由本 Activity 持有,控制器只回调通知 */
    private val epgController = LiveEpgController(object : LiveEpgController.Host {
        override fun currentChannel(): LiveChannelItem? = channelName

        override fun currentChannelHasLogo(): Boolean = !logoUrl.isNullOrEmpty()

        override fun onEpgListChanged(list: ArrayList<Epginfo>) {
            epgdata = list
            epgVersion++
        }

        override fun onEpgSettled() {
            updateChannelInfoUi()
        }
    })

    /** 代理直播源加载 */
    private val proxyLoader = LiveProxyLoader(object : LiveProxyLoader.Host {
        override fun isRefreshing(): Boolean = refreshingLiveChannelList

        override fun onLoading() {
            pageState = PageState.LOADING
        }

        override fun onEmpty() {
            setEmptyLiveChannelList()
        }

        override fun onGroupsLoaded(groups: List<LiveChannelGroup>) {
            applyLiveChannelGroups(groups)
        }
    })

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (fullScreen) super.hideSysBar()
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    epgSheetVisible -> epgSheetVisible = false
                    settingsSheetVisible -> settingsSheetVisible = false
                    fullScreen -> applyFullscreen(false)
                    isBackState -> backToLiveFromEpg()
                    else -> {
                        exitingLivePlay = true
                        finish()
                    }
                }
            }
        })
        epgController.reloadAddress()
        nowday = Date()
        epgController.setDayKey(FORMAT_DATE1.format(nowday))
        initVideoView()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                LiveScreen(activity = this)
            }
        }
        initLiveChannelList()
        initLiveSettingGroupList()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        exitingLivePlay = false
        val takenOverByVod = PlaybackService.peek()?.enterLiveState() ?: false
        rebindLiveControllerIfNeeded()
        if (takenOverByVod) {
            replayCurrentChannelAfterTakeover()
        } else {
            mVideoView?.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!exitingLivePlay) mVideoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideSwitchChannelSnapshot()
        PlaybackService.peek()?.exitLive()
        mVideoView = null
        mHandler.removeCallbacksAndMessages(null)
        // 这两个自带 Handler:延迟任务不再挂在 mHandler 上,必须显式取消,否则销毁后仍会回调到已销毁的界面
        epgController.cancelAll()
        proxyLoader.cancelAll()
    }

    private fun initVideoView() {
        val controller = ComposeLiveController(this)
        controller.setListener(liveControlListener)
        liveController = controller
        val view = PlaybackService.engine(this).also { it.enterLive() }.player()
        view.setVideoController(controller)
        view.setProgressManager(null)
        mVideoView = view
    }

    private fun rebindLiveControllerIfNeeded() {
        val view = mVideoView ?: return
        val controller = liveController ?: return
        if (view.videoController !== controller) {
            view.setVideoController(controller)
            LOG.i("echo-p4 re-bind live controller")
        }
    }

    private fun replayCurrentChannelAfterTakeover() {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        currentLiveLookBackIndex = -1
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        stopTimeshiftTicker()
        hideSwitchChannelSnapshot()
        // 与 playChannel 同款对齐(2026-09-17):接管路径可能紧跟在点播会话之后,别让 rtmp 频道用残留值
        videoView.setEffectiveIjkCodec(livePlayerManager.effectiveIjkCodecName())
        videoView.setUrl(item.url, liveChannelHeader())
        videoView.start()
        showResolutionAfterChannelSwitch()
        loadEpgAfterChannelStarted()
        epgVersion++
    }

    private fun releasePlayerKernel() {
        val eng = PlaybackService.peek()
        if (eng != null && !eng.isReleased()) eng.releasePlayer()
        else mVideoView?.release()
    }

    private val liveControlListener = object : ComposeLiveController.LiveControlListener {
        override fun onSingleTap(): Boolean {
            if (fullScreen) {
                overlayVisible = !overlayVisible
                if (overlayVisible) scheduleOverlayHide()
            } else {
                applyFullscreen(true)
            }
            return true
        }

        override fun onLongPress() {
            if (isBackState) {
                overlayVisible = true
                scheduleOverlayHide()
            } else {
                openSettingsSheet()
            }
        }

        override fun onPlayStateChanged(playState: Int) {
            this@LivePlayActivity.playState = playState
            handleAutoSourceSwitch(playState)
        }

        override fun onHorizontalFling(direction: Int) {
            if (direction > 0) playNext() else playPrevious()
        }

        override fun onGesturePercent(isBrightness: Boolean, percent: Int) {
            gestureHintText = (if (isBrightness) "亮度" else "音量") + " " + percent + "%"
            mHandler.removeCallbacks(mHideGestureHintRun)
            mHandler.postDelayed(mHideGestureHintRun, 1000)
        }
    }

    private fun handleAutoSourceSwitch(state: Int) {
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        when (state) {
            VideoView.STATE_IDLE, VideoView.STATE_PAUSED -> {}
            VideoView.STATE_PREPARED, VideoView.STATE_BUFFERED, VideoView.STATE_PLAYING -> {
                hideSwitchChannelSnapshot()
                if (resolutionInfoPending) {
                    resolutionInfoRetryCount = 0
                    mHandler.removeCallbacks(mUpdateResolutionInfoRun)
                    mHandler.post(mUpdateResolutionInfoRun)
                }
                currentLiveChangeSourceTimes = 0
                allowLiveSwitchPlayer = true
            }
            VideoView.STATE_ERROR, VideoView.STATE_PLAYBACK_COMPLETED -> {
                hideSwitchChannelSnapshot()
                mHandler.postDelayed(mConnectTimeoutChangeSourceRun, CONNECT_TIMEOUT_SWITCH_DELAY)
            }
            VideoView.STATE_PREPARING, VideoView.STATE_BUFFERING -> {
                mHandler.postDelayed(
                    mConnectTimeoutChangeSourceRun,
                    (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L,
                )
            }
            else -> LOG.i("echo-Unexpected live_play state: $state")
        }
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        rotating = (full != (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
        fullScreen = full
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (full) {
            overlayVisible = true
            scheduleOverlayHide()
            super.hideSysBar()
        } else {
            overlayVisible = false
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rotating = false
        applyStatusBarAppearance()
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (rotating) landNow else fullScreen
    }

    private fun playChannel(channelGroupIndex: Int, liveChannelIndex: Int, changeSource: Boolean): Boolean {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
            || (changeSource && currentLiveChannelItem?.sourceNum == 1)
        ) {
            return true
        }
        val groupChannels = getLiveChannels(channelGroupIndex)
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size) {
            return false
        }
        val showPreviousFrame = currentLiveChannelItem != null && mVideoView?.isPlaying == true
        val previousLivePlayerType = livePlayerManager.livePlayerType
        allowLiveSwitchPlayer = true
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex
            currentLiveChannelIndex = liveChannelIndex
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex)?.get(currentLiveChannelIndex)
            KV.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem?.channelName ?: "")
        }
        channelName = currentLiveChannelItem
        currentLiveLookBackIndex = -1
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        stopTimeshiftTicker()
        val item = currentLiveChannelItem ?: return false
        item.include_back = canCurrentChannelCatchup()
        updateChannelInfoUi()
        val videoView = mVideoView
        if (videoView != null) {
            // 有效 IJK 解码值对齐(2026-09-17):点播由 PlayerHelper.updateCfg 在每次起播前下发,直播切台不走它 ——
            // 这里显式对齐成"直播配置 → 全局",避免 rtmp 频道(强制 IJK)误用上一段点播会话的残留值
            videoView.setEffectiveIjkCodec(livePlayerManager.effectiveIjkCodecName())
            // EXO 解码方式变更标记(2026-09-17):复用内核不会重选解码器,切台必须重建 ——
            // 与点播侧 PlayContainer.startVideoPlayback 同款;标记只在"EXO 解码值确实变了"时才被置上
            val rebuildKernel = videoView.consumeKernelRebuildRequired()
            val reusePlayer = !rebuildKernel && canReusePlayer(previousLivePlayerType)
            val keepExoFrame = reusePlayer && previousLivePlayerType == 2
            if (showPreviousFrame && !keepExoFrame) {
                showSwitchChannelSnapshot()
            } else {
                hideSwitchChannelSnapshot()
            }
            val liveUrl = item.url
            if (reusePlayer) {
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.replay(true)
            } else {
                releasePlayerKernel()
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.start()
            }
            showResolutionAfterChannelSwitch()
        }
        loadEpgAfterChannelStarted()
        epgVersion++
        return true
    }

    private fun canReusePlayer(previousLivePlayerType: Int): Boolean {
        val videoView = mVideoView ?: return false
        return videoView.currentPlayState != VideoView.STATE_IDLE &&
                previousLivePlayerType == livePlayerManager.livePlayerType
    }

    private fun loadEpgAfterChannelStarted() {
        epgController.loadAfterChannelStarted()
    }

    private fun playNext() {
        if (!isCurrentLiveChannelValid()) return
        val next = getNextChannel(1)
        playChannel(next[0], next[1], false)
    }

    private fun playPrevious() {
        if (!isCurrentLiveChannelValid()) return
        val next = getNextChannel(-1)
        playChannel(next[0], next[1], false)
    }

    private fun playNextSource() {
        if (!isCurrentLiveChannelValid()) return
        currentLiveChannelItem?.nextSource()
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    private val mConnectTimeoutChangeSourceRun = Runnable {
        if (switchLivePlayerAndReplay()) {
            return@Runnable
        }
        currentLiveChangeSourceTimes++
        if (currentLiveChannelItem?.sourceNum == currentLiveChangeSourceTimes) {
            currentLiveChangeSourceTimes = 0
            val next = getNextChannel(if (KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) -1 else 1)
            playChannel(next[0], next[1], false)
        } else {
            playNextSource()
        }
    }

    private fun switchLivePlayerAndReplay(): Boolean {
        val videoView = mVideoView
        val item = currentLiveChannelItem ?: return false
        if (!allowLiveSwitchPlayer || videoView == null) {
            return false
        }
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        releasePlayerKernel()
        if (!livePlayerManager.switchLivePlayer(videoView)) {
            allowLiveSwitchPlayer = false
            return false
        }
        allowLiveSwitchPlayer = false
        val retryUrl = if (isSHIYI && !TextUtils.isEmpty(playUrl)) playUrl!! else item.url
        videoView.setUrl(retryUrl, liveChannelHeader())
        videoView.start()
        return true
    }

    /** 上/下一台:索引计算在 LiveChannelNavigator,这里只注入当前状态(跨组开关 + 密码可见性) */
    private fun getNextChannel(direction: Int): IntArray {
        return LiveChannelNavigator.nextPosition(
            groups = liveChannelGroupList,
            currentGroupIndex = currentChannelGroupIndex,
            currentChannelIndex = currentLiveChannelIndex,
            direction = direction,
            crossGroup = KV.get(HawkConfig.LIVE_CROSS_GROUP, false),
            channelsOf = { groupIndex -> getLiveChannels(groupIndex) },
        )
    }

    private fun isCurrentLiveChannelValid(): Boolean {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance(), "请先选择频道", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
        selectedChannelGroupIndex = groupIndex
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, liveChannelIndex)
            return
        }
        if (liveChannelIndex > -1) {
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
        } else {
            if (!expandedGroups.contains(groupIndex)) expandedGroups.add(groupIndex)
            channelVersion++
        }
    }

    fun toggleChannelGroup(groupIndex: Int) {
        if (expandedGroups.contains(groupIndex)) {
            expandedGroups.remove(groupIndex)
            return
        }
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1)
            return
        }
        expandedGroups.add(groupIndex)
    }

    fun selectChannel(groupIndex: Int, position: Int) {
        selectedChannelGroupIndex = groupIndex
        clickLiveChannel(position)
    }

    private fun clickLiveChannel(position: Int) {
        playChannel(selectedChannelGroupIndex, position, false)
    }

    private fun loadChannelGroupDataAndPlay(groupIndex: Int, liveChannelIndex: Int) {
        selectedChannelGroupIndex = groupIndex
        if (!expandedGroups.contains(groupIndex)) expandedGroups.add(groupIndex)
        channelVersion++
        scrollTick++
        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex)
        }
    }

    private fun showPasswordDialog(groupIndex: Int, liveChannelIndex: Int) {
        passwordDialogTarget = groupIndex to liveChannelIndex
    }

    internal fun onPasswordConfirmed(password: String) {
        val target = passwordDialogTarget ?: return
        passwordDialogTarget = null
        val groupIndex = target.first
        if (password == liveChannelGroupList.getOrNull(groupIndex)?.groupPassword) {
            channelGroupPasswordConfirmed.add(groupIndex)
            channelVersion++
            loadChannelGroupDataAndPlay(groupIndex, target.second)
        } else {
            Toast.makeText(App.getInstance(), "密码错误", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNeedInputPassword(groupIndex: Int): Boolean {
        val group = liveChannelGroupList.getOrNull(groupIndex) ?: return false
        return group.groupPassword.isNotEmpty() && !isPasswordConfirmed(groupIndex)
    }

    private fun isPasswordConfirmed(groupIndex: Int): Boolean {
        for (confirmed in channelGroupPasswordConfirmed) {
            if (confirmed == groupIndex) return true
        }
        return false
    }

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? {
        val group = liveChannelGroupList.getOrNull(groupIndex) ?: return null
        return if (!isNeedInputPassword(groupIndex)) group.liveChannels else ArrayList()
    }

    private fun initLiveChannelList() {
        if (ApiConfig.get().shouldReloadLiveConfig()) {
            loadLiveConfigOnEnter()
            return
        }
        val list = ApiConfig.get().channelGroupList
        if (list.isEmpty()) {
            loadLiveConfigOnEnter()
            return
        }
        initLiveObj()
        if (list.size == 1 && list[0].groupName.startsWith("http://127.0.0.1")) {
            loadProxyLives(list[0].groupName)
        } else {
            applyLiveChannelGroups(ArrayList(list))
        }
    }

    internal fun loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return
        loadingLiveConfigOnEnter = true
        pageState = PageState.LOADING
        ApiConfig.get().loadLiveConfig(true, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                mHandler.post {
                    loadingLiveConfigOnEnter = false
                    initLiveChannelList()
                    initLiveSettingGroupList()
                }
            }

            override fun error(msg: String) {
                mHandler.post {
                    loadingLiveConfigOnEnter = false
                    setEmptyLiveChannelList()
                }
            }

            override fun notice(msg: String) {
                mHandler.post {
                    Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun loadProxyLives(url: String) {
        proxyLoader.load(url)
    }

    private fun applyLiveChannelGroups(groups: List<LiveChannelGroup>) {
        liveChannelGroupList.clear()
        liveChannelGroupList.addAll(groups)
        pageState = PageState.READY
        initLiveState()
    }

    private fun initLiveState() {
        refreshingLiveChannelList = false
        val lastChannelName = pendingLiveRefreshChannelName ?: KV.get(HawkConfig.LIVE_CHANNEL, "")
        val sourceIndex = pendingLiveRefreshSourceIndex
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1

        var lastChannelGroupIndex = -1
        var lastLiveChannelIndex = -1
        var lastLiveChannelItem: LiveChannelItem? = null
        for (group in liveChannelGroupList) {
            val groupChannels = group.liveChannels
            if (groupChannels == null || groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                if (item.channelName == lastChannelName) {
                    lastChannelGroupIndex = group.groupIndex
                    lastLiveChannelIndex = item.channelIndex
                    lastLiveChannelItem = item
                    break
                }
            }
            if (lastChannelGroupIndex != -1) break
        }
        if (lastChannelGroupIndex == -1) {
            val cctv1Channel = LiveChannelNavigator.firstChannelByName(
                liveChannelGroupList, "CCTV1"
            ) { groupIndex -> isNeedInputPassword(groupIndex) }
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0]
                lastLiveChannelIndex = cctv1Channel[1]
            } else {
                lastChannelGroupIndex = LiveChannelNavigator.firstUnlockedGroupIndex(liveChannelGroupList)
                if (lastChannelGroupIndex == -1) lastChannelGroupIndex = 0
                lastLiveChannelIndex = 0
            }
        }
        if (lastLiveChannelItem != null && sourceIndex >= 0 && lastLiveChannelItem.sourceNum > 0) {
            lastLiveChannelItem.sourceIndex = minOf(sourceIndex, lastLiveChannelItem.sourceNum - 1)
        }

        mVideoView?.let { livePlayerManager.init(it) }
        showTime()
        showNetSpeed()
        currentLiveChannelIndex = -1
        expandedGroups.clear()
        channelVersion++
        selectChannelGroup(lastChannelGroupIndex, lastLiveChannelIndex)
    }

    private fun refreshLiveChannelListAndPlay(channelName: String?, sourceIndex: Int) {
        refreshingLiveChannelList = true
        pendingLiveRefreshChannelName = channelName
        pendingLiveRefreshSourceIndex = sourceIndex
        currentLiveLookBackIndex = -1
        currentLiveChangeSourceTimes = 0
        allowLiveSwitchPlayer = true
        channelGroupPasswordConfirmed.clear()
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        epgController.cancelPending()
        hideSwitchChannelSnapshot()
        expandedGroups.clear()
        isBackState = false
        overlayVisible = false
        channelVersion++
        epgVersion++
        initLiveChannelList()
        initLiveSettingGroupList()
    }

    private fun clearLiveChannelList(releasePlayer: Boolean) {
        refreshingLiveChannelList = false
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1
        currentLiveChannelItem = null
        currentLiveChannelIndex = -1
        currentLiveLookBackIndex = -1
        currentLiveChangeSourceTimes = 0
        liveChannelGroupList.clear()
        ApiConfig.get().channelGroupList.clear()
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        epgController.cancelPending()
        hideSwitchChannelSnapshot()
        if (releasePlayer) releasePlayerKernel()
        expandedGroups.clear()
        selectedChannelGroupIndex = 0
        channelName = null
        epgdata = ArrayList()
        channelInfoUi = ChannelInfoUi()
        channelVersion++
        epgVersion++
        pageState = PageState.EMPTY
    }

    private fun setEmptyLiveChannelList(releasePlayer: Boolean = true) {
        clearLiveChannelList(releasePlayer)
    }

    private fun initLiveSettingGroupList() {
        liveSettingGroupList = ApiConfig.get().liveSettingGroupList
    }

    private fun loadCurrentSourceList() {
        liveSettingGroupList.getOrNull(0)?.liveSettingItems =
            LiveSettingsRules.sourceItems(currentLiveChannelItem?.channelSourceNames)
    }

    fun visibleSettingGroups(): List<LiveSettingGroup> {
        return LiveSettingsRules.visibleGroups(liveSettingGroupList, hasCurrentLiveChannelSource())
    }

    private fun hasCurrentLiveChannelSource(): Boolean {
        return LiveSettingsRules.hasChannelSource(currentLiveChannelItem)
    }

    internal fun openSettingsSheet() {
        ApiConfig.get().refreshLiveApiHistoryItems()
        loadCurrentSourceList()
        settingsVersion++
        settingsSheetVisible = true
    }

    fun settingSelectedIndex(groupIndex: Int): Int {
        return when (groupIndex) {
            0 -> currentLiveChannelItem?.sourceIndex ?: -1
            1 -> livePlayerManager.livePlayerScale
            2 -> livePlayerManager.livePlayerType
            3 -> KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)
            5 -> ApiConfig.getLiveGroupIndex()
            6 -> getCurrentLiveConfigIndex()
            else -> -1
        }
    }

    fun removeLiveConfigHistory(itemIndex: Int) {
        val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        if (itemIndex < 0 || itemIndex >= history.size) return
        if (history[itemIndex] == KV.get(HawkConfig.LIVE_API_URL, "")) {
            Toast.makeText(this, "当前使用中的配置不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        history.removeAt(itemIndex)
        KV.put(HawkConfig.LIVE_API_HISTORY, history)
        ApiConfig.get().refreshLiveApiHistoryItems()
        settingsVersion++
        Toast.makeText(this, "已从历史中删除", Toast.LENGTH_SHORT).show()
    }

    fun settingChecked(position: Int): Boolean {
        return when (position) {
            0 -> KV.get(HawkConfig.LIVE_SHOW_TIME, false)
            1 -> KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
            2 -> KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)
            3 -> KV.get(HawkConfig.LIVE_CROSS_GROUP, false)
            else -> false
        }
    }

    private fun getCurrentLiveConfigIndex(): Int {
        return LiveSettingsRules.currentConfigIndex(
            ApiConfig.isLiveFollowVod(),
            KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>()),
            KV.get(HawkConfig.LIVE_API_URL, ""),
        )
    }

    internal fun clickSettingItem(groupIndex: Int, position: Int) {
        vm.onSettingClicked(groupIndex, position, settingHost)
    }

    /** 设置项分发里"要动播放器/频道列表"的动作实现(判定与 KV 写在 ViewModel) */
    private val settingHost = object : LivePlayViewModel.Host {
        override fun currentChannelItem(): LiveChannelItem? = currentLiveChannelItem

        override fun currentPlayerScale(): Int = livePlayerManager.livePlayerScale

        override fun currentPlayerType(): Int = livePlayerManager.livePlayerType

        override fun replayCurrentChannel() {
            playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
        }

        override fun applyPlayerScale(position: Int) {
            mVideoView?.let { livePlayerManager.changeLivePlayerScale(it, position) }
        }

        override fun applyPlayerType(position: Int) {
            val videoView = mVideoView ?: return
            releasePlayerKernel()
            livePlayerManager.changeLivePlayerType(videoView, position)
            currentLiveChannelItem?.let { videoView.setUrl(it.url, liveChannelHeader()) }
            videoView.start()
        }

        override fun releasePlayerKernel() {
            this@LivePlayActivity.releasePlayerKernel()
        }

        override fun refreshTimeOverlay() {
            showTime()
        }

        override fun refreshNetSpeedOverlay() {
            showNetSpeed()
        }

        override fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int) {
            refreshLiveChannelListAndPlay(channelName, sourceIndex)
        }

        override fun setEmptyChannelList(releasePlayer: Boolean) {
            setEmptyLiveChannelList(releasePlayer)
        }

        override fun toast(msg: String) {
            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun isFinishing(): Boolean = this@LivePlayActivity.isFinishing

        override fun postToMain(action: Runnable) {
            mHandler.post(action)
        }
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return KV.get(HawkConfig.LIVE_WEB_HEADER)
    }

    private fun liveChannelHeader(): HashMap<String, String>? {
        val item = currentLiveChannelItem ?: return liveWebHeader()
        val header = HashMap<String, String>()
        liveWebHeader()?.let { header.putAll(it) }
        item.headers?.let { header.putAll(it) }
        if (item.channelFormat.isNotEmpty()) {
            header[ExoMediaSourceHelper.HEADER_FORMAT] = item.channelFormat
        }
        return if (header.isEmpty()) null else header
    }

    private fun initLiveObj() {
        catchup = null
        logoUrl = null
        val position = ApiConfig.getLiveGroupIndex()
        val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        if (liveGroups == null || liveGroups.size() == 0 || position < 0 || position >= liveGroups.size()) {
            return
        }
        val livesOBJ = liveGroups.get(position).asJsonObject
        val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else "0"
        if (livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject) {
            catchup = livesOBJ.getAsJsonObject("catchup")
            LOG.i("echo-catchup :$catchup")
        }
        if (livesOBJ.has("logo")) {
            logoUrl = livesOBJ.get("logo").asString
        }
        if (type == "3") {
            var pyJar = ""
            if (livesOBJ.has("jar")) {
                pyJar = livesOBJ.get("jar").asString
            } else if (livesOBJ.has("api")) {
                pyJar = livesOBJ.get("api").asString
                val ext = if (livesOBJ.has("ext") &&
                    (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)
                ) {
                    livesOBJ.get("ext").toString()
                } else {
                    DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                }
                LOG.i("echo-ext:$ext")
                if (ext.isNotEmpty()) pyJar = "$pyJar?extend=$ext"
            }
            ApiConfig.get().setLiveJar(pyJar)
        }
    }

    internal fun onEpgRowClicked(position: Int): Boolean {
        if (position == currentLiveLookBackIndex) return false
        val selectedData = epgdata.getOrNull(position) ?: return false
        if (selectedData.startdateTime == null || selectedData.enddateTime == null) return false
        val now = Date()
        if (now.before(selectedData.startdateTime)) return false
        if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return false
        currentLiveLookBackIndex = position
        var switched = false
        if (!now.before(selectedData.startdateTime) && !now.after(selectedData.enddateTime)) {
            backToLiveFromEpg()
            switched = true
        } else if (canCurrentChannelCatchup()) {
            startCatchupReplay(selectedData)
            switched = true
        }
        epgVersion++
        return switched
    }

    private fun startCatchupReplay(epg: Epginfo) {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        releasePlayerKernel()
        isSHIYI = true
        val shiyiUrl = buildCatchupUrl(item.url, epg)
        if (TextUtils.isEmpty(shiyiUrl)) return
        LOG.i("echo-回看地址playUrl :$shiyiUrl")
        playUrl = shiyiUrl
        videoView.setUrl(playUrl, liveChannelHeader())
        videoView.start()
        shiyiTimeC = LiveEpgParser.getCatchupDurationSeconds(epg)
        tsDuration = PlayerUtils.safeTimeMs(shiyiTimeC.toLong() * 1000)
        tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition)
        startTimeshiftTicker()
        isBackState = true
        overlayVisible = true
        scheduleOverlayHide()
        epgVersion++
    }

    private fun backToLiveFromEpg() {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        stopTimeshiftTicker()
        releasePlayerKernel()
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        videoView.setUrl(item.url, liveChannelHeader())
        videoView.start()
        epgVersion++
    }

    private val mHideOverlayRun = Runnable { overlayVisible = false }

    private fun scheduleOverlayHide() {
        mHandler.removeCallbacks(mHideOverlayRun)
        mHandler.postDelayed(mHideOverlayRun, OVERLAY_HIDE_DELAY)
    }

    private val mUpdateTimeshiftRun = object : Runnable {
        override fun run() {
            val videoView = mVideoView ?: return
            if (!isSHIYI) return
            tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition)
            mHandler.postDelayed(this, 1000)
        }
    }

    private fun startTimeshiftTicker() {
        mHandler.removeCallbacks(mUpdateTimeshiftRun)
        mHandler.postDelayed(mUpdateTimeshiftRun, 1000)
    }

    private fun stopTimeshiftTicker() {
        mHandler.removeCallbacks(mUpdateTimeshiftRun)
    }

    fun onTimeshiftSeek(progress: Float) {
        val videoView = mVideoView ?: return
        val target = progress.toInt().coerceIn(0, tsDuration.coerceAtLeast(1))
        videoView.seekTo(target.toLong())
        tsPosition = target
        scheduleOverlayHide()
    }

    fun onTimeshiftTogglePlay() {
        val videoView = mVideoView ?: return
        if (videoView.isPlaying) videoView.pause() else videoView.start()
        scheduleOverlayHide()
    }

    private fun showSwitchChannelSnapshot() {
        var bitmap: Bitmap? = null
        try {
            bitmap = mVideoView?.doScreenShot()
        } catch (ignored: Throwable) {
            LOG.d("LivePlayActivity", "doScreenShot failed, switch-channel snapshot skipped")
        }
        snapshotBitmap = bitmap
        snapshotVisible = true
    }

    private fun hideSwitchChannelSnapshot() {
        snapshotVisible = false
        snapshotBitmap = null
    }

    private fun showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true
        resolutionInfoRetryCount = 0
        resolutionText = ""
        resolutionVisible = false
        mHandler.removeCallbacks(mHideResolutionInfoRun)
        mHandler.removeCallbacks(mUpdateResolutionInfoRun)
        mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
    }

    private val mHideResolutionInfoRun = Runnable {
        resolutionVisible = false
    }

    private val mUpdateResolutionInfoRun = Runnable {
        val videoView = mVideoView ?: return@Runnable
        if (videoView.currentPlayState != VideoView.STATE_PREPARED &&
            videoView.currentPlayState != VideoView.STATE_BUFFERED &&
            videoView.currentPlayState != VideoView.STATE_PLAYING
        ) {
            retryOrHideResolutionInfo()
            return@Runnable
        }
        val videoSize = videoView.videoSize
        if (videoSize != null && videoSize.size >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
            resolutionInfoPending = false
            resolutionText = videoSize[0].toString() + " x " + videoSize[1]
            resolutionVisible = true
            mHandler.removeCallbacks(mHideResolutionInfoRun)
            mHandler.postDelayed(mHideResolutionInfoRun, RESOLUTION_INFO_HIDE_DELAY)
            return@Runnable
        }
        retryOrHideResolutionInfo()
    }

    private fun retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
        } else {
            resolutionVisible = false
        }
    }

    private fun showTime() {
        showTimeOn = KV.get(HawkConfig.LIVE_SHOW_TIME, false)
        mHandler.removeCallbacks(mUpdateTimeRun)
        if (showTimeOn) mHandler.post(mUpdateTimeRun)
    }

    private val mUpdateTimeRun = object : Runnable {
        override fun run() {
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            mHandler.postDelayed(this, 1000)
        }
    }

    private fun showNetSpeed() {
        showNetSpeedOn = KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
        mHandler.removeCallbacks(mUpdateNetSpeedRun)
        if (showNetSpeedOn) mHandler.post(mUpdateNetSpeedRun)
    }

    private val mUpdateNetSpeedRun = object : Runnable {
        override fun run() {
            val videoView = mVideoView ?: return
            netSpeedText = PlayerHelper.getDisplaySpeedBps(videoView.tcpSpeed, true)
            mHandler.postDelayed(this, 1000)
        }
    }

    private val mHideGestureHintRun = Runnable { gestureHintText = null }

    private fun updateChannelInfoUi() {
        if (isSHIYI) return
        val channel = channelName ?: return
        val name = channel.channelName ?: return
        var ui = ChannelInfoUi(name = name, num = channel.channelNum)
        ui = if (channel.sourceNum <= 0) {
            ui.copy(sourceText = "1/1")
        } else {
            ui.copy(sourceText = "线路" + (channel.sourceIndex + 1) + "/" + channel.sourceNum)
        }
        var current = ""
        var currentTitle = ""
        var next = ""
        var nextTitle = ""
        val arrayList = epgController.cachedEpg(name)
        if (arrayList != null && arrayList.isNotEmpty()) {
            epgdata = arrayList
        } else {
            epgdata = ArrayList()
        }
        val timeZone = TimeZone.getTimeZone("GMT+8:00")
        val currentStart = Calendar.getInstance(timeZone)
        currentStart.set(Calendar.MINUTE, 0)
        currentStart.set(Calendar.SECOND, 0)
        currentStart.set(Calendar.MILLISECOND, 0)
        val currentEnd = (currentStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val nextStart = (currentEnd.clone() as Calendar).apply { add(Calendar.MINUTE, 1) }
        val nextEnd = (nextStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeFormat.timeZone = timeZone
        var hasInfo = false
        val list = epgdata
        if (list.isNotEmpty()) {
            val date = Date()
            var size = list.size - 1
            while (size >= 0) {
                val info = list[size]
                if (info.startdateTime != null && info.enddateTime != null &&
                    date.after(info.startdateTime) && date.before(info.enddateTime)
                ) {
                    current = info.start + "-" + info.end
                    currentTitle = info.title
                    if (size != list.size - 1) {
                        next = list[size + 1].start + "-" + list[size + 1].end
                        nextTitle = list[size + 1].title
                    } else {
                        next = info.end + "-23:59"
                        nextTitle = "精彩节目-暂无节目预告信息"
                    }
                    hasInfo = true
                    break
                } else {
                    size--
                }
            }
        }
        if (!hasInfo) {
            current = timeFormat.format(currentStart.time) + "-" + timeFormat.format(currentEnd.time)
            currentTitle = "精彩节目"
            next = timeFormat.format(nextStart.time) + "-" + timeFormat.format(nextEnd.time)
            nextTitle = "暂无节目预告信息"
        }
        channelInfoUi = ui.copy(
            currentEpgTime = current,
            currentEpgTitle = currentTitle,
            nextEpgTime = next,
            nextEpgTitle = nextTitle,
        )
        epgVersion++
    }

    private fun currentChannelHasCatchup(): Boolean {
        return currentLiveChannelItem != null && LiveEpgParser.hasCatchupSource(currentLiveChannelItem?.channelCatchup)
    }

    private fun currentCatchup(): JsonObject? {
        if (currentChannelHasCatchup()) return currentLiveChannelItem!!.channelCatchup
        return catchup
    }

    internal fun canCurrentChannelCatchup(): Boolean {
        val item = currentLiveChannelItem ?: return false
        val url = item.url
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            val regex = LiveEpgParser.getCatchupValue(catchupObj, "regex")
            if (TextUtils.isEmpty(regex)) return true
            return try {
                url.contains(regex) || Pattern.compile(regex).matcher(url).find()
            } catch (ignored: Throwable) {
                false
            }
        }
        return url.contains("/PLTV/")
    }

    private fun buildCatchupUrl(url: String, epg: Epginfo?): String {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return ""
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            return LiveEpgParser.formatCatchupUrl(url, catchupObj!!, epg)
        }
        if (!url.contains("/PLTV/")) return ""
        val source = "?playseek=" + LiveEpgParser.formatCatchupTime(epg.startdateTime!!, "yyyyMMddHHmmss") +
                "-" + LiveEpgParser.formatCatchupTime(epg.enddateTime!!, "yyyyMMddHHmmss")
        return LiveEpgParser.appendCatchupUrl(url, "/PLTV/,/TVOD/", source)
    }

    internal fun buildChannelRows(): List<LiveListRow> {
        val rows = ArrayList<LiveListRow>()
        for (group in liveChannelGroupList) {
            rows.add(LiveListRow(group, null, -1, "g" + group.groupIndex))
            if (expandedGroups.contains(group.groupIndex)) {
                val channels = getLiveChannels(group.groupIndex)
                if (channels != null) {
                    for (i in channels.indices) {
                        rows.add(LiveListRow(group, channels[i], i, "c" + group.groupIndex + "_" + channels[i].channelIndex))
                    }
                }
            }
        }
        return rows
    }

    fun isPasswordConfirmedForUi(groupIndex: Int): Boolean = isPasswordConfirmed(groupIndex)

}
