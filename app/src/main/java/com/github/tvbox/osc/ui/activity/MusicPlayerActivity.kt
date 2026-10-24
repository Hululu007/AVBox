package com.github.tvbox.osc.ui.activity

import androidx.activity.viewModels
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
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackController
import com.github.tvbox.osc.player.PlaybackEngine
import com.github.tvbox.osc.player.PlaybackHostApi
import com.github.tvbox.osc.player.PlaybackPage
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.PlaybackViewBridge
import com.github.tvbox.osc.player.engine.MediaSources
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.ui.music.MusicLrc
import com.github.tvbox.osc.ui.music.MusicPlayerScreen
import com.github.tvbox.osc.ui.music.MusicPlayerState
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory

private const val POSITION_TICK_MS = 400L

class MusicPlayerActivity : BaseActivity(), PlaybackPage {

    companion object {
        private const val EXTRA_HISTORY_SOURCE_KEY = "historySourceKey"

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

    private val dataViewModel: MusicPlayerViewModel by viewModels()

    private val ui: MusicPlayerState get() = dataViewModel.ui
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var vod: VodInfo
    private var sourceKey = ""
    private var historySourceKey = ""
    private var ready = false
    private var isoBailed = false
    private var lifecyclePaused = false
    private var lyricJob: Job? = null

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {}

    override fun init() {
        engine = PlaybackService.engine(this)
        controller = engine.controller()
        player = engine.player()
        vod = controller.vod() ?: run {
            controller.setMusicAudioOnly(false)
            finish()
            return
        }
        if (isIsoSession()) {
            controller.setMusicAudioOnly(false)
            Toast.makeText(this, getString(R.string.player_iso_music_unsupported), Toast.LENGTH_SHORT).show()
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
        scope.launch(Dispatchers.Main.immediate) {
            player.playStateFlow.collect { playState -> onPlayStateChanged(playState) }
        }
        scope.launch(Dispatchers.Main.immediate) {
            controller.selection.collect { controller.vod()?.let { vod = it } }
        }
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
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
        }
        dataViewModel.initPlayMode()
        dataViewModel.refreshCollected(sourceKey, vod.id)
        refreshMeta()
        syncLyric()
        controller.setMusicAudioOnly(true)
        ready = true
        main.post(positionTick)
    }

    override fun renderSlot(): ViewGroup = renderSlot

    override fun viewBridge(): PlaybackViewBridge = bridge

    override fun isAudioOnlyPage(): Boolean = true

    override fun onServiceStopped() {
        main.removeCallbacksAndMessages(null)
        dataViewModel.clearTip()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        LOG.i("echo-music page onResume lifecyclePaused=$lifecyclePaused playing=${player.isPlaying} audioOnly=${controller.isConfirmedAudioOnly()}")
        controller.setMusicAudioOnly(true)
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
        if (ready || engine.attachedPage() === this) {
            dataViewModel.clearTip()
            syncHistory()
            controller.setMusicAudioOnly(false)
            if (isoBailed) {
                engine.detachForHandover(this)
            } else {
                engine.detach(this)
            }
        }
        super.onDestroy()
    }

    private val positionTick = object : Runnable {
        override fun run() {
            val state = player.playState
            dataViewModel.applyTick(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L),
                buffering = state == PlayState.PREPARING || state == PlayState.BUFFERING,
                playing = player.isPlaying,
            )
            main.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private fun onPlayStateChanged(playState: PlayState) {
        if (playState == PlayState.PREPARING
            || playState == PlayState.PREPARED
            || playState == PlayState.PLAYING
        ) {
            if (isIsoSession()) {
                bailIsoSession()
                return
            }
        }
        dataViewModel.applyPlayState(playState, player.duration.coerceAtLeast(0L))
        if (playState == PlayState.COMPLETED) onSongCompleted()
        if (playState == PlayState.PREPARING
            || playState == PlayState.PREPARED
            || playState == PlayState.PLAYING
        ) {
            refreshMeta()
            syncLyric()
        }
    }

    private fun isIsoSession(): Boolean =
        MediaSources.isoHeaders(controller.webHeaderMap()) || controller.isIsoEscalated()

    private fun bailIsoSession() {
        if (isoBailed) return
        isoBailed = true
        LOG.i("echo-iso-music-bail")
        controller.setMusicAudioOnly(false)
        Toast.makeText(this, getString(R.string.player_iso_music_unsupported), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.start()
        dataViewModel.setPlaying(player.isPlaying)
        controller.updateMusicSession()
    }

    private fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        player.saveCurrentProgress()
        dataViewModel.setPosition(positionMs.coerceAtLeast(0L))
        controller.updateMusicSession()
    }

    private fun queueList(): List<VodInfo.VodSeries> =
        vod.seriesMap?.get(vod.playFlag).orEmpty()

    private fun playAt(index: Int, removeProgress: Boolean) {
        val list = queueList()
        if (index < 0 || index >= list.size || index == vod.playIndex) return
        controller.beginSwitchPlayback()
        if (removeProgress) {
            controller.progressKey()?.let { dataViewModel.clearWatchProgress(controller.progressOwner(), it) }
        }
        controller.updateSelection(vod.playFlag, index)
        controller.vod()?.let { vod = it }
        controller.clearTriedLines()
        controller.setReusePlayerOnSwitch(true)
        engine.play(false)
        dataViewModel.setQueueIndex(index)
        refreshMeta()
        syncHistory()
    }

    private fun syncHistory() {
        dataViewModel.syncHistory(historySourceKey, vod)
    }

    private fun onSongCompleted() {
        when (val action = dataViewModel.songCompletedAction(vod.playIndex, queueList().size)) {
            SongAction.None -> {}
            SongAction.Replay -> replayCurrent()
            is SongAction.Play -> playAt(action.index, action.removeProgress)
        }
    }

    private fun replayCurrent() {
        controller.beginSwitchPlayback()
        controller.progressKey()?.let { dataViewModel.clearWatchProgress(controller.progressOwner(), it) }
        controller.clearTriedLines()
        controller.setReusePlayerOnSwitch(true)
        engine.play(true)
    }

    private fun cyclePlayMode() {
        dataViewModel.cyclePlayMode()
    }

    private fun toggleCollect() {
        dataViewModel.toggleCollect(sourceKey, vod)
    }

    private fun showCast() {
        val url = controller.webPlayUrl()
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        val title = listOf(ui.title, ui.subtitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "TVBox" }
        val headers = controller.webHeaderMap()?.let { HashMap(it) }
        ui.openCastSheet(CastSheetState(
            CastVideo(
                controller.getCastUrl(url) ?: url,
                title,
                headers,
                player.currentPosition.coerceAtLeast(0L),
            ),
            onCastSuccess = {
                player.pause()
                controller.updateMusicSession()
            },
        ))
    }

    private fun refreshMeta() {
        val series = vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)
        dataViewModel.applyMeta(
            title = vod.name.orEmpty().ifBlank { getString(R.string.music_default_name) },
            subtitle = series?.name?.takeIf { it.isNotBlank() }.orEmpty(),
            sourceName = ApiConfig.get().getSource(sourceKey)?.name.orEmpty(),
            artwork = listOfNotNull(
                controller.currentArtwork()?.takeIf { it.isNotBlank() },
                controller.playArtwork()?.takeIf { it.isNotBlank() },
                vod.pic?.takeIf { it.isNotBlank() },
            ).firstOrNull().orEmpty(),
            queue = queueList().map { it.name.orEmpty() },
            queueIndex = vod.playIndex,
            seedKey = vod.id.orEmpty() + "#" + vod.playIndex,
        )
    }

    private fun syncLyric() {
        val source = controller.playLyric()
        if (source.isNullOrBlank()) {
            dataViewModel.clearLyric()
            return
        }
        if (!dataViewModel.beginLyric(source)) return
        lyricJob?.cancel()
        lyricJob = scope.launch {
            val lines = try {
                MusicLrc.load(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.i("echo-music lyric parse failed: $e")
                emptyList()
            }
            LOG.i("echo-music lyric parsed: ${lines.size} lines")
            dataViewModel.applyLyric(source, lines)
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
        dataViewModel.clearTip()
        controller.startVideoPlayback(
            player,
            url,
            headers,
            forceExoPlayer,
            releaseKernel = { engine.releasePlayer() },
            trackMemoryKey = "",
            renderRebuildGuard = { !controller.isConfirmedAudioOnly() },
            startLineTimeoutIso = false,
        )
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
            engine.consumeServiceLostKeep(true)
            if (lifecyclePaused) {
                lifecyclePaused = false
                player.resume()
            }
        }

        override fun hostPause() {
            if (!controller.isConfirmedAudioOnly()) {
                lifecyclePaused = player.isPlaying
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
            activity.dataViewModel.setTip(msg.orEmpty(), loading, error)
        }

        override fun hideTipOnUiThread() {
            main.post { activity.dataViewModel.clearTip() }
        }

        override fun showErrorWithRetry(err: String, finish: Boolean) {
            activity.dataViewModel.setTip(err.orEmpty(), false, true)
            toast(err)
        }

        override fun requestNotificationPermission() {
            PermissionHelper.requestNotificationIfNeeded(activity)
        }

        override fun setArtwork(url: String) {
            activity.dataViewModel.setArtwork(url)
        }

        override fun clearArtwork() {
            activity.dataViewModel.setArtwork("")
        }

        override fun clearLyric() {
            activity.dataViewModel.clearLyric()
        }

        override fun onNewPlayStarted(sameContent: Boolean) {
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
            activity.player.setRenderViewFactory(EngineTextureRenderViewFactory.create())
        }

        override fun playExternalPlayer(
            playerType: Int,
            url: String,
            title: String,
            subtitle: String?,
            headers: HashMap<String, String>?,
            progress: Long,
        ): Boolean {
            if (!isPageAlive()) return false
            return PlayerHelper.runExternalPlayer(playerType, activity, url, title, subtitle.orEmpty(), headers, progress)
        }
    }
}
