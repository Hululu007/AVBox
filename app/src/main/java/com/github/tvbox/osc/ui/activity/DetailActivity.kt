package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.engine.MediaSources
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.SpiderStorageNotice
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
private const val CAST_URL_POLL_MS = 250L
private const val CAST_URL_WAIT_ATTEMPTS = 20

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? by mutableStateOf<PlayContainer?>(null)
        private set
    private var appliedFullscreen = false
    private var pendingEpisodeSync = false
    private var videoSizeWatchArmed = false
    private var castWaitAttempts = 0

    private val castWaitRunnable = object : Runnable {
        override fun run() {
            val container = playContainer ?: return
            if (container.hasCastUrl()) {
                container.endCastPrepare()
                container.showCast()
                return
            }
            if (castWaitAttempts >= CAST_URL_WAIT_ATTEMPTS) {
                container.endCastPrepare()
                container.showCast()
                return
            }
            castWaitAttempts += 1
            window.decorView.postDelayed(this, CAST_URL_POLL_MS)
        }
    }

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
        try {
            SpiderStorageNotice.suppress()
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (vm.fullScreen.value) super.hideSysBar()
    }

    override fun keepStatusBarHidden(): Boolean = vm.fullScreen.value

    private var statusBarIconsLight = false

    fun setStatusBarIconsLight(light: Boolean) {
        if (statusBarIconsLight == light) return
        statusBarIconsLight = light
        applyStatusBarAppearance()
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = statusBarIconsLight
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (vm.enteringFullscreen.value) {
                    vm.cancelEntrySlide()
                    container?.stopForExitFullscreen()
                    return
                }
                if (vm.exitingFullscreen.value) return
                if (vm.fullScreen.value) {
                    if (container != null && container.onBackPressed()) return
                    if (DetailPlaybackPolicy.plan(DetailPlaybackEntry.ExitFullscreen).stopPlayback) {
                        container?.stopForExitFullscreen()
                    }
                    vm.onFullScreenToggleRequested(false, playbackFacts())
                    container?.hideTipOnUiThread()
                } else {
                    if (vm.backToPreviousTarget()) {
                        pendingEpisodeSync = false
                        return
                    }
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        vm.initFromIntent(intent)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    DetailScreen(activity = this, vm = vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEpisodeSync = false
        vm.onFullScreenToggleRequested(false, playbackFacts())
        playContainer?.hideTipOnUiThread()
        vm.pushTargetFromIntent(intent)
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.setPageHost(this)
                it.setPreviewMode(true)
                it.setOnQualitySelectedListener(vm::onQualitySelectionAccepted)
            }
        }
        return requireNotNull(playContainer) { "play container not created" }
    }

    fun preheatWebView() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { preheatWebView() }
            return
        }
        if (!webViewPreheated.compareAndSet(false, true)) return
        val startedAt = System.nanoTime()
        try {
            WebView(this).destroy()
            LOG.i("echo-webview-prewarm: " + (System.nanoTime() - startedAt) / 1_000_000L + "ms")
        } catch (th: Throwable) {
            LOG.e("DetailActivity", th)
        }
    }

    fun playbackFacts(): DetailPlaybackFacts = DetailPlaybackFacts(
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun onPlaybackLinesExhausted(): Boolean = startDetailFallbackAfterLinesExhausted()

    override fun showEpisodeSheet() {
        if (vm.vodInfo != null) vm.showEpisodeSheet()
    }

    fun playCurrent() {
        val container = ensurePlayContainer()
        cancelCastWait()
        container.endCastPrepare()
        container.clearCastAbort()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
    }

    fun ensurePlaying(): PlayContainer {
        val container = ensurePlayContainer()
        if (!engineOwnsDetailContent()) {
            playCurrent()
        } else {
            container.ensurePlaybackActive()
        }
        return container
    }

    /**
     * 引擎里已经装着内容、但装的是别的影片时（典型场景：在影片 A 的音乐页返回首页，
     * 再打开全新影片 B），不能直接复用，否则音乐页拿到的是 A 的海报与内容。
     */
    private fun engineOwnsDetailContent(): Boolean {
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return false
        val info = vm.vodInfo ?: return false
        return contentMatches(playing, info)
    }

    private fun contentMatches(playing: VodInfo, info: VodInfo): Boolean {
        if (playing.id != info.id) return false
        // 首页复用同一播放器实例时 controller 的 sourceKey 可能取自上次会话，
        // 因此只在本页确实用过该源时才要求源一致，避免把同一影片误判成新内容。
        val expectedSource = vm.firstsourceKey
        return expectedSource.isEmpty()
            || playing.sourceKey == expectedSource
            || vm.sourceKey == expectedSource
    }

    fun openCast() {
        val container = ensurePlayContainer()
        cancelCastWait()
        container.beginCastPrepare()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.endCastPrepare()
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        container.setData(session)
        window.decorView.postDelayed(castWaitRunnable, CAST_URL_POLL_MS)
    }

    private fun cancelCastWait() {
        castWaitAttempts = 0
        window.decorView.removeCallbacks(castWaitRunnable)
    }

    fun musicPlaybackDetected(): Boolean {
        if (!MusicSettings.autoOpenPage()) return false
        val container = playContainer ?: return false
        val engine = PlaybackService.peek() ?: return false
        if (engine.isReleased() || engine.attachedPage() !== container) return false
        val state = engine.player().playState
        val accepted = state == PlayState.PREPARING ||
            state == PlayState.PREPARED ||
            state == PlayState.BUFFERING ||
            state == PlayState.BUFFERED ||
            state == PlayState.PLAYING
        return accepted && isAudioContent()
    }

    fun isAudioContent(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        val url = controller.webPlayUrl() ?: return false
        return PlaybackController.looksLikeAudioUrl(url) || controller.isAudioOnlyContent()
    }

    fun openMusicPlayer() {
        if (vm.vodInfo == null) {
            Toast.makeText(this, getString(R.string.detail_content_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (isIsoPlayback()) {
            Toast.makeText(this, getString(R.string.player_iso_music_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        // 先按纯音频启动，避免详情页这一帧就把视频解码器建起来（交接后无法回收）。
        prepareAudioOnlyForMusicPage()
        ensurePlaying()
        if (!handOffToMusicPlayer()) {
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIsoPlayback(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        if (!engineOwnsDetailContent()) return false
        return MediaSources.isoHeaders(controller.webHeaderMap()) || controller.isIsoEscalated()
    }

    private fun prepareAudioOnlyForMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(true)
    }

    private fun restoreVideoAfterMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(false)
    }

    fun handOffToMusicPlayer(): Boolean {
        val container = playContainer ?: run {
            restoreVideoAfterMusicPage()
            return false
        }
        val playing = PlaybackService.peek()?.controller()?.vod()
        if (playing == null) {
            restoreVideoAfterMusicPage()
            return false
        }
        // 引擎里若还装着别的影片，先切到本页内容，避免音乐页显示上一部影片。
        val info = vm.vodInfo
        if (info != null && !contentMatches(playing, info)) {
            LOG.i("echo-music handoff switch content: " + playing.id + " -> " + info.id)
            restoreVideoAfterMusicPage()
            playCurrent()
        }
        prepareAudioOnlyForMusicPage()
        val keepDetailPage = !isAudioContent()
        container.setExitingPreview(true)
        container.handOverToNextPage()
        MusicPlayerActivity.start(this, vm.firstsourceKey)
        if (keepDetailPage) pendingEpisodeSync = true else finish()
        return true
    }

    private fun syncEpisodeAfterMusicPage() {
        if (!pendingEpisodeSync) return
        pendingEpisodeSync = false
        vm.syncEpisodeAfterMusicReturn()
    }

    fun applyFullscreen(full: Boolean) {
        if (appliedFullscreen == full) return
        appliedFullscreen = full
        requestedOrientation = orientationPolicyValue()
        if (full) {
            armVideoSizeWatch()
            hideSysBar()
        } else {
            videoSizeWatchArmed = false
            playContainer?.setVideoSizeReadyListener(null)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    private fun armVideoSizeWatch() {
        val container = playContainer ?: return
        if (container.hasVideoSize()) {
            applyVideoOrientation(container.isPortraitVideo())
            return
        }
        if (videoSizeWatchArmed) return
        videoSizeWatchArmed = true
        container.setVideoSizeReadyListener { portraitVideo -> onVideoSizeReady(portraitVideo) }
    }

    private fun onVideoSizeReady(portraitVideo: Boolean) {
        if (!videoSizeWatchArmed) return
        videoSizeWatchArmed = false
        playContainer?.setVideoSizeReadyListener(null)
        applyVideoOrientation(portraitVideo)
    }

    private fun applyVideoOrientation(portraitVideo: Boolean) {
        if (!vm.fullScreen.value || portraitVideo) return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        settleRotationAfterExit()
    }

    fun settleRotationAfterExit() {
        if (!vm.rotating.value) return
        vm.settleRotation()
        syncFullBoxSideEffects()
    }

    fun setPlayerTouchBlocked(blocked: Boolean) {
        playContainer?.setTouchBlocked(blocked)
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DetailFullScreenFrame.playerFullScreen(vm.rotating.value, vm.fullScreen.value, landNow)
    }

    private fun syncFullBoxSideEffects() {
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        playContainer?.hostResume()
        syncEpisodeAfterMusicPage()
        val container = playContainer ?: return
        vm.applyPlaybackEntry(
            DetailPlaybackEntry.MusicReturn,
            resumable = container.hasClaimedPlayback() && !container.ownsEngineContent(),
        )
    }

    override fun onPause() {
        playContainer?.hostPause()
        super.onPause()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(castWaitRunnable)
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }

    companion object {
        private val webViewPreheated = AtomicBoolean(false)
    }
}
