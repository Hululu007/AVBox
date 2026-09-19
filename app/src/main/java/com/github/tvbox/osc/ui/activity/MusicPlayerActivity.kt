package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.CacheManager
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackEngine
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackPage
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.ui.music.MusicLrc
import com.github.tvbox.osc.ui.music.MusicPlayMode
import com.github.tvbox.osc.ui.music.MusicPlayerScreen
import com.github.tvbox.osc.ui.music.MusicPlayerState
import com.github.tvbox.osc.ui.player.PlayerTipBridge
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.PlayerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.TextureRenderViewFactory

private const val POSITION_TICK_MS = 400L

class MusicPlayerActivity : BaseActivity(), PlaybackPage {

    companion object {
        private const val EXTRA_HISTORY_SOURCE_KEY = "historySourceKey"

        /** [historySourceKey] 必须是详情页写历史用的那个 key(firstsourceKey),否则换过源会写出第二条记录 */
        fun start(context: Context, historySourceKey: String? = null) {
            context.startActivity(
                Intent(context, MusicPlayerActivity::class.java)
                    .putExtra(EXTRA_HISTORY_SOURCE_KEY, historySourceKey),
            )
        }
    }

    private lateinit var engine: PlaybackEngine
    private lateinit var controller: PlaybackController
    private lateinit var player: MyVideoView
    private lateinit var renderSlot: FrameLayout
    private lateinit var bridge: MusicPageBridge
    private lateinit var host: MusicHost

    private val ui = MusicPlayerState()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var vod: VodInfo
    private var sourceKey = ""
    private var historySourceKey = ""
    private var ready = false
    private var lifecyclePaused = false
    private var lyricSource: String? = null
    private var lyricJob: Job? = null

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {}

    override fun init() {
        engine = PlaybackService.engine(this)
        controller = engine.controller()
        player = engine.player()
        vod = controller.vod() ?: run {
            finish()
            return
        }
        sourceKey = controller.sourceKey().orEmpty()
        historySourceKey = intent?.getStringExtra(EXTRA_HISTORY_SOURCE_KEY)
            ?.takeIf { it.isNotEmpty() }
            ?: sourceKey
        enableTransparentEdgeToEdge()
        renderSlot = FrameLayout(this)
        addContentView(renderSlot, ViewGroup.LayoutParams(1, 1))
        bridge = MusicPageBridge(this, engine.headlessBridge())
        host = MusicHost()
        engine.attach(this)
        player.addOnStateChangeListener(stateListener)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                MusicPlayerScreen(
                    state = ui,
                    onBack = { finish() },
                    onTogglePlay = { togglePlay() },
                    onPrevious = { host.playPrevious() },
                    onNext = { host.playNext(false) },
                    onSeek = { seekTo(it) },
                    onSelectQueue = { playAt(it, false) },
                    onCyclePlayMode = { cyclePlayMode() },
                    onToggleCollect = { toggleCollect() },
                    onCast = { showCast() },
                )
            }
        }
        ui.playMode = MusicPlayMode.of(MusicSettings.playMode())
        ui.collected = RoomDataManger.isVodCollect(sourceKey, vod.id)
        refreshMeta()
        syncLyric()
        ready = true
        main.post(positionTick)
    }

    override fun renderSlot(): ViewGroup = renderSlot

    override fun viewBridge(): PlaybackViewBridge = bridge

    override fun onServiceStopped() {
        main.removeCallbacksAndMessages(null)
        PlayerTipBridge.hide()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        LOG.i("echo-music page onResume lifecyclePaused=$lifecyclePaused playing=${player.isPlaying} audioOnly=${controller.isConfirmedAudioOnly()}")
        host.hostResume()
        main.removeCallbacks(positionTick)
        main.post(positionTick)
    }

    override fun onPause() {
        if (ready) {
            LOG.i("echo-music page onPause lifecyclePaused=$lifecyclePaused playing=${player.isPlaying} audioOnly=${controller.isConfirmedAudioOnly()}")
            host.hostPause()
            main.removeCallbacks(positionTick)
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        lyricJob = null
        if (ready) {
            player.removeOnStateChangeListener(stateListener)
            PlayerTipBridge.hide()
            // 退出页面时再落一次:刷新 updateTime(历史列表按时间排序)并记下最后播到哪首
            syncHistory()
            engine.detach(this)
        }
        super.onDestroy()
    }

    private val positionTick = object : Runnable {
        override fun run() {
            ui.positionMs = player.currentPosition.coerceAtLeast(0L)
            ui.durationMs = player.duration.coerceAtLeast(0L)
            val state = player.currentPlayState
            ui.buffering = state == VideoView.STATE_PREPARING || state == VideoView.STATE_BUFFERING
            ui.playing = player.isPlaying
            main.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private val stateListener = object : VideoView.SimpleOnStateChangeListener() {
        override fun onPlayStateChanged(playState: Int) {
            when (playState) {
                VideoView.STATE_PREPARING, VideoView.STATE_BUFFERING -> ui.buffering = true
                VideoView.STATE_PREPARED, VideoView.STATE_BUFFERED -> ui.buffering = false
                VideoView.STATE_PLAYING -> {
                    ui.buffering = false
                    ui.playing = true
                }
                VideoView.STATE_PAUSED -> {
                    ui.buffering = false
                    ui.playing = false
                }
                VideoView.STATE_PLAYBACK_COMPLETED -> {
                    ui.buffering = false
                    ui.playing = false
                    onSongCompleted()
                }
                VideoView.STATE_ERROR -> {
                    ui.buffering = false
                    ui.playing = false
                }
            }
            ui.durationMs = player.duration.coerceAtLeast(0L)
            if (playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_PREPARED
                || playState == VideoView.STATE_PLAYING
            ) {
                refreshMeta()
                syncLyric()
            }
        }
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.start()
        ui.playing = player.isPlaying
        controller.updateMusicSession()
    }

    private fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        ui.positionMs = positionMs.coerceAtLeast(0L)
        controller.updateMusicSession()
    }

    private fun queueList(): List<VodInfo.VodSeries> =
        vod.seriesMap?.get(vod.playFlag).orEmpty()

    private fun playAt(index: Int, removeProgress: Boolean) {
        val list = queueList()
        if (index < 0 || index >= list.size || index == vod.playIndex) return
        // 必须在 engine.play() 之前(见 PlaybackController.beginSwitchPlayback)
        controller.beginSwitchPlayback()
        if (removeProgress) {
            controller.progressKey()?.let { CacheManager.delete(MD5.string2MD5(it), 0) }
        }
        vod.playIndex = index
        list.forEachIndexed { i, series -> series.selected = i == index }
        controller.clearTriedLines()
        controller.setReusePlayerOnSwitch(true)
        engine.play(false)
        ui.queueIndex = index
        refreshMeta()
        syncHistory()
    }

    /**
     * 刷新观看历史。音乐页不走详情页的 preparePlaySession,而 RoomDataManger.insertVodRecord 是历史的
     * 唯一落库点 —— 不在这里补,历史会永远停在详情页交接那一刻(集数/备注/时间都不再更新)。
     */
    private fun syncHistory() {
        vod.playNote = queueList().getOrNull(vod.playIndex)?.name.orEmpty()
        RoomDataManger.insertVodRecord(historySourceKey, vod)
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    private fun onSongCompleted() {
        when (ui.playMode) {
            MusicPlayMode.SINGLE -> replayCurrent()
            MusicPlayMode.LIST -> {
                val list = queueList()
                if (list.isEmpty()) return
                val next = (vod.playIndex + 1) % list.size
                if (next == vod.playIndex) replayCurrent() else playAt(next, true)
            }
            MusicPlayMode.ORDER -> playAt(vod.playIndex + 1, true)
        }
    }

    private fun replayCurrent() {
        // 同 playAt:必须在 engine.play() 之前
        controller.beginSwitchPlayback()
        controller.progressKey()?.let { CacheManager.delete(MD5.string2MD5(it), 0) }
        controller.clearTriedLines()
        controller.setReusePlayerOnSwitch(true)
        engine.play(true)
    }

    private fun cyclePlayMode() {
        val mode = ui.playMode.toggled()
        ui.playMode = mode
        MusicSettings.setPlayMode(mode.name)
    }

    private fun toggleCollect() {
        if (ui.collected) {
            RoomDataManger.deleteVodCollect(sourceKey, vod)
            ui.collected = false
        } else {
            RoomDataManger.insertVodCollect(sourceKey, vod)
            ui.collected = true
        }
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
    }

    // 与 PlayContainer.showCastDialog 同一套取数口径:可播地址 + 头部 + 当前位置,标题 = 影片名 + 集名
    private fun showCast() {
        val url = controller.webPlayUrl()
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, "暂无可投屏播放地址", Toast.LENGTH_SHORT).show()
            return
        }
        val title = listOf(ui.title, ui.subtitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "TVBox" }
        val headers = controller.webHeaderMap()?.let { HashMap(it) }
        ui.castSheet = CastSheetState(
            CastVideo(
                controller.getCastUrl(url),
                title,
                headers,
                player.currentPosition.coerceAtLeast(0L),
            ),
            onCastSuccess = {
                player.pause()
                controller.updateMusicSession()
            },
        )
    }

    private fun refreshMeta() {
        val series = vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)
        val title = vod.name.orEmpty().ifBlank { "音乐" }
        val sourceName = ApiConfig.get().getSource(sourceKey)?.name.orEmpty()
        ui.title = title
        ui.subtitle = series?.name?.takeIf { it.isNotBlank() }.orEmpty()
        ui.sourceName = sourceName
        ui.artwork = listOfNotNull(
            controller.currentArtwork()?.takeIf { it.isNotBlank() },
            controller.playArtwork()?.takeIf { it.isNotBlank() },
            vod.pic?.takeIf { it.isNotBlank() },
        ).firstOrNull().orEmpty()
        ui.queue = queueList().map { it.name.orEmpty() }
        ui.queueIndex = vod.playIndex
        ui.waveSeed = (vod.id.orEmpty() + "#" + vod.playIndex).hashCode()
    }

    private fun syncLyric() {
        val source = controller.playLyric()
        if (source.isNullOrBlank()) {
            if (lyricSource != null) {
                lyricSource = null
                ui.lyrics = emptyList()
            }
            return
        }
        if (source == lyricSource) return
        lyricSource = source
        lyricJob?.cancel()
        lyricJob = scope.launch {
            val result = runCatching { MusicLrc.load(source) }
            // 禁止静默吞错:MusicLrc 类初始化失败(标签正则被 ICU 拒绝)与"解析出 0 行"症状完全一样
            // (界面无歌词、无任何提示),不打日志根本分不清是哪一种
            result.exceptionOrNull()?.let { LOG.i("echo-music lyric parse failed: $it") }
            val lines = result.getOrDefault(emptyList())
            LOG.i("echo-music lyric parsed: ${lines.size} lines")
            if (source == lyricSource) ui.lyrics = lines
        }
    }

    private fun applyPlayerConfig(forceKernel: Int) {
        val cfg = controller.playerCfg() ?: return
        if (forceKernel > 0) {
            PlayerHelper.updateCfg(player, cfg, forceKernel)
        } else {
            PlayerHelper.updateCfg(player, cfg)
        }
    }

    private fun startPlayback(url: String, headers: HashMap<String, String>?, forceExoPlayer: Boolean) {
        PlayerTipBridge.hide()
        val rebuildKernel = player.consumeKernelRebuildRequired()
        val reusePlayer = !forceExoPlayer && player.mediaPlayer != null && !rebuildKernel
        if (!reusePlayer && player.mediaPlayer != null) engine.releasePlayer()
        player.setProgressKey(controller.progressKey())
        controller.markContentStarted()
        if (headers != null) player.setUrl(url, headers) else player.setUrl(url)
        controller.startSwitchLinePlayTimeout()
        if (reusePlayer) {
            player.skipPositionWhenPlay(controller.playTimeoutBasePosition().toInt())
            player.replay(false)
        } else {
            player.start()
        }
    }

    private inner class MusicHost : PlaybackHostApi {

        override fun setData(session: PlaybackSession) {
            engine.setData(session)
        }

        override fun play(reset: Boolean) {
            engine.play(reset)
        }

        override fun playNext(rmProgress: Boolean) {
            playAt(vod.playIndex + 1, rmProgress)
        }

        override fun playPrevious() {
            playAt(vod.playIndex - 1, false)
        }

        override fun selectQuality(position: Int): Boolean = controller.selectQuality(position)

        override fun setAutoSwitchLineEnabled(enabled: Boolean) {
            controller.setAutoSwitchLineEnabled(enabled)
        }

        override fun setPreviewMode(previewMode: Boolean) {}

        override fun toggleControllerControls() {}

        override fun onBackPressed(): Boolean = false

        override fun setExitingPreview(exitingPreview: Boolean) {}

        override fun setPlayTitle(show: Boolean) {}

        override fun stopForSourceSwitch(tip: String) {
            controller.markStoppedForSourceSwitch()
            controller.stopMusicSessionForFailedPlayback()
        }

        override fun clearSourceSwitchTip() {}

        override fun showCast() {}

        override fun onLocalSubtitlePicked(uri: Uri) {}

        override fun hostResume() {
            if (lifecyclePaused) {
                lifecyclePaused = false
                player.resume()
            }
        }

        override fun hostPause() {
            if (!controller.isConfirmedAudioOnly()) {
                lifecyclePaused = player.isPlaying
                // 与"通知消失"同判据:留痕才能区分音频轨读不到与真判成影视
                LOG.i("echo-music hostPause -> pause player (lifecyclePaused=$lifecyclePaused)")
                player.pause()
            }
        }

        override fun hostDestroy() {}

        override fun resumeFromMediaSession() {
            player.start()
            controller.updateMusicSession()
        }

        override fun pauseFromMediaSession() {
            player.pause()
            controller.updateMusicSession()
        }

        override fun stopFromMediaSession() {
            player.pause()
            controller.stopMusicSession()
        }

        override fun seekFromMediaSession(position: Long) {
            player.seekTo(position)
            controller.updateMusicSession()
        }
    }

    private class MusicPageBridge(
        private val activity: MusicPlayerActivity,
        private val base: PlaybackViewBridge,
    ) : PlaybackViewBridge by base {

        private val main = Handler(Looper.getMainLooper())

        override fun isPageAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed

        override fun runOnUi(action: Runnable) {
            main.post { if (isPageAlive()) action.run() }
        }

        override fun context(): Context = activity

        override fun playbackHost(): PlaybackHostApi = activity.host

        override fun toast(text: CharSequence) {
            if (isPageAlive()) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }

        override fun showTip(msg: String, loading: Boolean, error: Boolean) {
            PlayerTipBridge.setTip(msg.orEmpty(), loading, error)
        }

        override fun hideTipOnUiThread() {
            main.post { PlayerTipBridge.hide() }
        }

        override fun showErrorWithRetry(err: String, finish: Boolean) {
            PlayerTipBridge.setTip(err.orEmpty(), false, true)
            toast(err)
        }

        override fun requestNotificationPermission() {
            PermissionHelper.requestNotificationIfNeeded(activity)
        }

        override fun setArtwork(url: String) {
            activity.ui.artwork = url.orEmpty()
        }

        override fun clearArtwork() {
            activity.ui.artwork = ""
        }

        override fun clearLyric() {
            activity.lyricSource = null
            activity.ui.lyrics = emptyList()
        }

        override fun onNewPlayStarted() {
            activity.refreshMeta()
        }

        override fun applyPlayerConfigToView(forceKernel: Int) {
            activity.applyPlayerConfig(forceKernel)
        }

        override fun startVideoPlayback(
            url: String,
            headers: HashMap<String, String>?,
            forceExoPlayer: Boolean,
        ) {
            activity.startPlayback(url, headers, forceExoPlayer)
        }

        override fun useTextureRenderForAudio() {
            activity.player.setRenderViewFactory(TextureRenderViewFactory.create())
        }
    }
}
