package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PermissionHelper
import kotlinx.coroutines.launch
import xyz.doikki.videoplayer.player.VideoView

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? = null
        private set
    private var fullScreen = false
    private var pendingEpisodeSync = false

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
        try {
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (fullScreen) super.hideSysBar()
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (fullScreen) {
                    if (container != null && container.onBackPressed()) return
                    vm.setFullScreen(false)
                } else {
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        vm.initFromIntent(intent)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                // 独立 Activity 页面:套窗口根槽位,弹层无论写在哪都能全屏弹出(见 SheetHostScaffold)
                SheetHostScaffold {
                    DetailScreen(activity = this, vm = vm)
                }
            }
        }
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.setPageHost(this)
                it.setPreviewMode(true)
            }
        }
        return playContainer!!
    }

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

    /** 入口可见性由播放侧判定(剧集/线路多于一个才显示按钮),这里只兜住详情数据尚未就绪的时序 */
    override fun showEpisodeSheet() {
        if (vm.vodInfo != null) vm.showEpisodeSheet()
    }

    fun playCurrent() {
        val container = playContainer ?: return
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
    }

    fun musicPlaybackDetected(): Boolean {
        if (!MusicSettings.autoOpenPage()) return false
        val container = playContainer ?: return false
        val engine = PlaybackService.peek() ?: return false
        if (engine.isReleased() || engine.attachedPage() !== container) return false
        val state = engine.player().currentPlayState
        if (state != VideoView.STATE_PREPARING &&
            state != VideoView.STATE_PREPARED &&
            state != VideoView.STATE_BUFFERING &&
            state != VideoView.STATE_BUFFERED &&
            state != VideoView.STATE_PLAYING
        ) {
            return false
        }
        return isAudioContent()
    }

    /** 当前内容是否可判定为音频:URL 后缀或轨道确认(纯音频三态判定见 §4.4) */
    fun isAudioContent(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        val url = controller.webPlayUrl() ?: return false
        return PlaybackController.looksLikeAudioUrl(url) || controller.isConfirmedAudioOnly()
    }

    /** 详情页手动进音乐播放页:会话还没建就先按当前集起播,再交接(影视内容交接后本页留在栈里) */
    fun openMusicPlayer() {
        if (vm.vodInfo == null) {
            Toast.makeText(this, getString(R.string.detail_content_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (PlaybackService.peek()?.controller()?.vod() == null) playCurrent()
        if (!handOffToMusicPlayer()) {
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
        }
    }

    fun handOffToMusicPlayer(): Boolean {
        val container = playContainer ?: return false
        if (PlaybackService.peek()?.controller()?.vod() == null) return false
        val keepDetailPage = !isAudioContent()
        container.setExitingPreview(true)
        container.handOverToNextPage()
        // 传 firstsourceKey:音乐页据此刷新历史,必须与 insertVod 落库用的 key 一致
        MusicPlayerActivity.start(this, vm.firstsourceKey)
        // 影视内容保留本页在栈里(音乐页返回即回到竖屏详情页);纯音频没有回头路,直接收掉
        if (keepDetailPage) pendingEpisodeSync = true else finish()
        return true
    }

    /**
     * 音乐页可能切过歌,而它改的是 session.vod(预览副本),本页 vm.vodInfo 是另一个对象 ——
     * 不同步回来,选集高亮会停在交接那一集,点播放还会跳回那一集。
     */
    private fun syncEpisodeAfterMusicPage() {
        if (!pendingEpisodeSync) return
        pendingEpisodeSync = false
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return
        val info = vm.vodInfo ?: return
        if (playing.id != info.id) return
        if (playing.playFlag == info.playFlag && playing.playIndex == info.playIndex) return
        info.playFlag = playing.playFlag
        info.playIndex = playing.playIndex
        vm.bumpRevision()
    }

    fun applyFullscreen(full: Boolean) {
        playContainer?.setAutoSwitchLineEnabled(!full)
        if (fullScreen == full) return
        fullScreen = full
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            // 恢复窗口档策略值:大屏上硬写竖屏会把平板压回信箱模式
            orientationPolicyValue()
        }
        if (full) {
            hideSysBar()
        } else {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.rotating.value = false
        syncFullBoxSideEffects()
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (vm.rotating.value) !landNow else fullScreen
    }

    private fun syncFullBoxSideEffects() {
        // 字幕字号随形态缩放(预览 0.6×)已收口到 PlayContainer.setPreviewMode,这里不再单独下发
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        playContainer?.hostResume()
        syncEpisodeAfterMusicPage()
    }

    override fun onPause() {
        playContainer?.hostPause()
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }
}
