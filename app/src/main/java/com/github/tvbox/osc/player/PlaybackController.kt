package com.github.tvbox.osc.player

import android.os.Looper
import android.text.TextUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.sourcedata.SourceHelper
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.player.state.NearEndRules
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.player.engine.SourcePolicy
import com.github.tvbox.osc.player.engine.iso.IsoSessionSignal
import com.github.tvbox.osc.player.usecase.M3u8PurifyUseCase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.HashMap
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var vod: VodInfo? = null

    @Volatile
    private var playerCfg: JSONObject? = null
    private var sourceKey: String = ""
    private var sourceBean: SourceBean? = null

    data class PlaybackSelection(val flag: String?, val index: Int)

    private val _selection = MutableStateFlow(PlaybackSelection(null, 0))

    val selection: StateFlow<PlaybackSelection> = _selection.asStateFlow()

    private val _quality = MutableStateFlow<JSONObject?>(null)

    val quality: StateFlow<JSONObject?> = _quality.asStateFlow()

    private val _persistedPlayerCfg = MutableStateFlow<String?>(null)

    val persistedPlayerCfg: StateFlow<String?> = _persistedPlayerCfg.asStateFlow()

    private val progress: PlaybackProgressTracker = PlaybackProgressTracker(object : PlaybackProgressTracker.Host {
        override fun playerCfg(): JSONObject? = this@PlaybackController.playerCfg

        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun currentSession(): PlaybackSession? = this@PlaybackController.currentSession

        override fun attemptState(): PlaybackAttemptState = st

        override fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState) {
            this@PlaybackController.updateAttemptState(block)
        }
    })

    private var subtitleCacheKey: String? = null
    private var playSubtitle: String? = null
    private var playLyric: String? = null
    private var lyricCacheKey: String? = null

    private var qualityResult: JSONObject? = null

    private var m3u8ProxyUrl: String? = null
    private var m3u8SourceUrl: String? = null

    fun startSession(session: PlaybackSession) {
        cancelInFlight()
        timeouts.cancelPendingCompletionDrop()
        resolver.resetGen()
        val previousSession = currentSession
        val contentChanged = previousSession == null ||
            !TextUtils.equals(previousSession.playbackKey(), session.playbackKey())
        if (contentChanged) {
            music.clearArtworks()
        }
        // 纯音频确认必须绑定当前内容：否则「先播纯音频、再播带视频轨的内容」时这个标记会残留，
        // 使视频内容被 isConfirmedAudioOnly() 误判为音频并自动跳进音乐页。
        music.onNewContent()
        currentSession = session
        if (contentChanged) {
            progress.clearStartedPlaybackKey()
        }
        if (st.castAborted) LOG.i("echo-cast abort clear: new playback session")
        st = st.beginSession()
        clearM3u8ProxyUrl()
        vod = session.vod()
        _selection.value = PlaybackSelection(vod?.playFlag, vod?.playIndex ?: 0)
        qualityResult = null
        _quality.value = null
        sourceKey = session.sourceKey()
        sourceBean = ApiConfig.get().getSource(sourceKey)
        ApiConfig.get().setCurrentPlaySourceKey(sourceKey)
        initPlayerCfg()
        _persistedPlayerCfg.value = vod?.playerCfg
    }

    fun initPlayerCfg() {
        config.initPlayerCfg()
    }

    fun updateSelection(flag: String?, index: Int) {
        val current = vod ?: return
        val updated = current.copy(
            playFlag = flag,
            playIndex = index,
            playNote = current.seriesMap?.get(flag)?.getOrNull(index)?.name ?: "",
        )
        vod = updated
        currentSession?.adoptVod(updated)
        view?.onSessionVodUpdated(updated)
        _selection.value = PlaybackSelection(flag, index)
    }

    private fun publishPersistedPlayerCfg(cfg: String) {
        val updated = vod?.copy(playerCfg = cfg)
        if (updated != null) {
            vod = updated
            currentSession?.adoptVod(updated)
        }
        _persistedPlayerCfg.value = cfg
    }

    fun updatePlayerCfg(
        mutate: (JSONObject) -> Unit,
        suppressAutoSwitch: Boolean = false,
        suppressDecodeFallback: Boolean = false,
    ) {
        commitPlayerCfg(mutate, true, suppressAutoSwitch, suppressDecodeFallback)
    }

    fun commitPlayerCfg(
        mutate: (JSONObject) -> Unit,
        persist: Boolean,
        suppressAutoSwitch: Boolean = false,
        suppressDecodeFallback: Boolean = false,
    ): Boolean {
        if (suppressAutoSwitch) config.setAllowSwitchPlayer(false)
        if (suppressDecodeFallback) config.setAllowDecodeFallback(false)
        val current = playerCfg ?: return false
        val next = try {
            JSONObject(current.toString())
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
            return false
        }
        try {
            mutate(next)
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
            return false
        }
        playerCfg = next
        val bridge = view
        if (bridge != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                bridge.applyPlayerConfig(next)
            } else {
                bridge.runOnUi {
                    val latest = playerCfg
                    if (latest != null) bridge.applyPlayerConfig(latest)
                }
            }
        }
        if (persist) {
            val persisted = (config.playerCfgForPersist() ?: next).toString()
            scope.launch { publishPersistedPlayerCfg(persisted) }
        }
        return true
    }

    suspend fun getSavedProgress(url: String?): Long {
        return progress.getSavedProgress(url)
    }

    fun inheritProgressFrom(key: String?, position: Long) {
        progress.inheritProgressFrom(key, position)
    }

    fun inheritProgressIfNeeded() {
        progress.inheritProgressIfNeeded()
    }

    fun migrateProgressOnLineSwitch() {
        val started = progress.startedProgressKey()
        val key = progressKey()
        val userPicked = st.userPickedLine
        val live = view?.currentPosition() ?: 0L
        if (live > 0 || started.isNullOrEmpty()) {
            migrateProgressOnLineSwitchNow(userPicked, started, key, if (live > 0) live else 0L)
            return
        }
        scope.launch {
            migrateProgressOnLineSwitchNow(userPicked, started, key, getSavedProgress(started))
        }
    }

    private fun migrateProgressOnLineSwitchNow(userPicked: Boolean, started: String?, key: String?, position: Long) {
        if (!ProgressSampling.lineSwitchMigrate(userPicked, started, key, position)) return
        val duration = view?.duration() ?: 0L
        WatchProgressStore.save(progressOwner(), key, position, duration)
        LOG.i("echo-switchLine migrate pos=" + position + " dur=" + duration + " key=" + key)
    }

    fun progressOwner(): String? = progress.progressOwner()

    fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? {
        val currentVod = vod ?: return null
        val seriesMap = currentVod.seriesMap ?: return null
        if (flag == null) return null
        val currentList = seriesMap[flag] ?: return null
        if (currentList.isEmpty()) return null
        val safeIndex = Math.max(0, Math.min(index, currentList.size - 1))
        return currentList[safeIndex]
    }

    fun isStalePlayResult(info: JSONObject): Boolean {
        val currentVod = vod
        if (currentVod == null || currentVod.seriesMap == null || TextUtils.isEmpty(progress.progressKey())) return false
        val resultKey = info.optString("proKey", "")
        if (!TextUtils.isEmpty(resultKey) && progress.progressKey() != resultKey) return true
        val resultFlag = info.optString("flag", "")
        if (!TextUtils.isEmpty(resultFlag) && resultFlag != currentVod.playFlag) return true
        val sourceUrl = info.optString("key", "")
        if (!TextUtils.isEmpty(sourceUrl)) {
            val vs = currentSeries(currentVod.playFlag, currentVod.playIndex)
            return vs != null && sourceUrl != vs.url
        }
        return false
    }

    fun publishQuality(info: JSONObject?) {
        try {
            val src = info ?: throw JSONException("invalid quality urls")
            val urls = JSONArray(src.optString("url"))
            if (urls.length() < 4 || urls.length() % 2 != 0) throw JSONException("invalid quality urls")
            qualityResult = JSONObject(src.toString())
            _quality.value = qualityResult
        } catch (th: Throwable) {
            qualityResult = null
            _quality.value = null
        }
    }

    fun quality(): JSONObject? = qualityResult

    fun getCastUrl(url: String?): String? {
        if (url == null || url.isEmpty()) return url
        if (isM3u8ProxyUrl(url) && !TextUtils.isEmpty(m3u8SourceUrl)) return m3u8SourceUrl
        val local = ControlManager.get().getAddress(true)
        val server = ControlManager.get().getAddress(false)
        if (!TextUtils.isEmpty(local) && !TextUtils.isEmpty(server) && url.startsWith(local)) {
            return server + url.substring(local.length)
        }
        return url
    }

    fun isM3u8ProxyUrl(url: String?): Boolean {
        return !TextUtils.isEmpty(m3u8ProxyUrl) && url == m3u8ProxyUrl
    }

    fun onM3u8ProxyResolved(proxyUrl: String?, sourceUrl: String?) {
        m3u8ProxyUrl = proxyUrl
        m3u8SourceUrl = sourceUrl
    }

    fun m3u8SourceUrl(): String? = m3u8SourceUrl

    fun clearM3u8ProxyUrl() {
        m3u8ProxyUrl = null
        m3u8SourceUrl = null
    }

    fun vod(): VodInfo? = vod

    fun playerCfg(): JSONObject? = playerCfg

    fun sourceBean(): SourceBean? = sourceBean

    fun sourceKey(): String = sourceKey

    fun progressKey(): String? = progress.progressKey()

    fun setProgressKey(progressKey: String?) {
        progress.setProgressKey(progressKey)
    }

    fun subtitleCacheKey(): String? = subtitleCacheKey

    fun setSubtitleCacheKey(subtitleCacheKey: String?) {
        this.subtitleCacheKey = subtitleCacheKey
    }

    fun playSubtitle(): String? = playSubtitle

    fun setPlaySubtitle(playSubtitle: String?) {
        this.playSubtitle = playSubtitle
    }

    fun playLyric(): String? = playLyric

    fun setPlayLyric(playLyric: String?) {
        this.playLyric = playLyric
    }

    fun lyricCacheKey(): String? = lyricCacheKey

    fun setLyricCacheKey(lyricCacheKey: String?) {
        this.lyricCacheKey = lyricCacheKey
    }

    @Volatile
    private var view: PlaybackViewBridge? = null

    fun attachViewBridge(bridge: PlaybackViewBridge) {
        this.view = bridge
    }

    private val timeouts: PlaybackTimeouts = PlaybackTimeouts(object : PlaybackTimeouts.Callback {
        override fun onResolvePlayUrlTimeout() {
            handleResolvePlayUrlTimeout()
        }

        override fun onSwitchLinePlayTimeout() {
            handleSwitchLinePlayTimeout()
        }

        override fun onPendingCompletionDrop() {
            music.handlePendingCompletionDrop()
        }
    })

    private val config: PlaybackConfigDelegate = PlaybackConfigDelegate(object : PlaybackConfigDelegate.Host {
        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun sourceBean(): SourceBean? = this@PlaybackController.sourceBean

        override fun playerCfg(): JSONObject? = this@PlaybackController.playerCfg

        override fun setPlayerCfg(cfg: JSONObject) {
            this@PlaybackController.playerCfg = cfg
        }

        override fun commitPlayerCfg(mutate: (JSONObject) -> Unit, persist: Boolean): Boolean {
            return this@PlaybackController.commitPlayerCfg(mutate, persist)
        }

        override fun attemptState(): PlaybackAttemptState = st

        override fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState) {
            this@PlaybackController.updateAttemptState(block)
        }
    })

    private val retry: PlaybackRetryDelegate = PlaybackRetryDelegate(object : PlaybackRetryDelegate.Host {
        override fun attemptState(): PlaybackAttemptState = st

        override fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState) {
            this@PlaybackController.updateAttemptState(block)
        }

        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun playerCfg(): JSONObject? = this@PlaybackController.playerCfg

        override fun commitPlayerCfg(mutate: (JSONObject) -> Unit, persist: Boolean): Boolean {
            return this@PlaybackController.commitPlayerCfg(mutate, persist)
        }

        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun updateSelection(flag: String?, index: Int) = this@PlaybackController.updateSelection(flag, index)

        override fun sourceBean(): SourceBean? = this@PlaybackController.sourceBean()

        override fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? =
            this@PlaybackController.currentSeries(flag, index)

        override fun progressKey(): String? = this@PlaybackController.progressKey()

        override suspend fun getSavedProgress(url: String): Long = this@PlaybackController.getSavedProgress(url)

        override fun inheritProgressFrom(key: String?, position: Long) {
            this@PlaybackController.inheritProgressFrom(key, position)
        }

        override fun videoCodecInfo(): VideoCodecInfo =
            this@PlaybackController.view?.videoCodecInfo() ?: VideoCodecInfo()

        override fun webPlayUrl(): String? = this@PlaybackController.webPlayUrl

        override fun webHeaderMap(): HashMap<String, String>? = this@PlaybackController.webHeaderMap

        override fun resolverHasFoundUrls(): Boolean = resolver.hasFoundUrls()

        override fun resolverConsumeFoundUrl() {
            resolver.consumeFoundUrl()
        }

        override fun play(reset: Boolean) {
            this@PlaybackController.play(reset)
        }

        override fun playUrl(url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(url, headers)
        }

        override fun stopParse() {
            this@PlaybackController.stopParse()
        }

        override fun initParseLoadFound() {
            this@PlaybackController.initParseLoadFound()
        }

        override fun cancelPlayRequest() {
            fetch.cancelPlayRequest()
        }

        override fun cancelPlayTimeout() {
            this@PlaybackController.cancelPlayTimeout()
        }

        override fun startSwitchLinePlayTimeout(isoSession: Boolean) {
            this@PlaybackController.startSwitchLinePlayTimeout(isoSession)
        }

        override fun extendSwitchLinePlayTimeout(graceMs: Long) {
            this@PlaybackController.extendSwitchLinePlayTimeout(graceMs)
        }

        override fun isPlaybackStarted(): Boolean = this@PlaybackController.isPlaybackStarted()

        override fun stopMusicSessionForFailedPlayback() {
            music.stopMusicSessionForFailedPlayback()
        }

        override fun closeCastPrepare() {
            this@PlaybackController.closeCastPrepare()
        }

        override fun isCrossContentReuseAllowed(): Boolean = this@PlaybackController.isCrossContentReuseAllowed()
    })

    private val resolver: PlayUrlResolver = PlayUrlResolver(object : PlayUrlResolver.Host {
        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun sourceBean(): SourceBean? = this@PlaybackController.sourceBean()

        override fun webHeaderMap(): HashMap<String, String>? = this@PlaybackController.webHeaderMap

        override fun setWebHeaderMap(headers: HashMap<String, String>?) {
            this@PlaybackController.webHeaderMap = headers
        }

        override fun webUserAgent(): String? = this@PlaybackController.webUserAgent

        override fun setWebUserAgent(userAgent: String?) {
            this@PlaybackController.webUserAgent = userAgent
        }

        override fun playUrl(url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(url, headers)
        }

        override fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(gen, url, headers)
        }

        override fun cancelPlayRequest() {
            fetch.cancelPlayRequest()
        }

        override fun cancelM3u8Purify() {
            M3u8PurifyUseCase.cancelActive()
        }
    })

    @Volatile
    private var st: PlaybackAttemptState = PlaybackAttemptState()

    fun attemptState(): PlaybackAttemptState = st

    fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState) {
        st = block(st)
    }

    fun beginNewPlay() {
        st = st.beginNewPlay()
        IsoSessionSignal.reset()
        timeouts.cancelPendingCompletionDrop()
        music.onNewPlayStarted()
    }

    private fun <T> probeKernelError(fallback: T, label: String, read: (ExoPlayer) -> T): T {
        try {
            val live = view?.mediaPlayer()
            if (live is ExoPlayer) return read(live)
        } catch (ignored: Throwable) {
            LOG.d("PlaybackController", label + " probe failed")
        }
        return fallback
    }

    fun isLastErrorExceedsDecodeCapability(): Boolean =
        probeKernelError(false, "exo decode capability") { it.lastErrorExceedsDecodeCapability() }

    fun isLastErrorTenBitFormat(): Boolean =
        probeKernelError(false, "exo bit depth") { it.lastErrorTenBitFormat() }

    fun lastErrorHttpStatus(): Int =
        probeKernelError(0, "exo http status") { it.lastErrorHttpStatus() }

    fun rememberErrorHttpStatus() {
        st = st.copy(lastHttpStatus = lastErrorHttpStatus())
    }

    fun errorHttpStatusForTip(): Int {
        val live = lastErrorHttpStatus()
        return if (live > 0) live else st.lastHttpStatus
    }

    fun beginCastPrepare() {
        st = st.copy(castPrepareOnly = true)
    }

    fun isCastPrepareOnly(): Boolean = st.castPrepareOnly

    fun closeCastPrepare() {
        st = st.abortCastSession()
        resolver.nextGen()
        LOG.i("echo-cast abort set: drop in-flight resolve results")
    }

    fun clearCastAbort() {
        val cleared = st.clearCastAbort()
        st = cleared.state
        if (cleared.value) LOG.i("echo-cast abort clear")
    }

    fun markStoppedForSourceSwitch() {
        st = st.stoppedForSourceSwitch()
    }

    fun beginSourceSwitch(position: Long) {
        cancelPlayTimeout()
        stopParse()
        markStoppedForSourceSwitch()
        stopMusicSessionForFailedPlayback()
        setPendingInherit(progressKey(), position)
        setWebPlayUrl(null)
        setWebHeaderMap(null)
        initParseLoadFound()
    }

    fun consumeReusePlayerOnSwitch(): Boolean {
        val consumed = st.consumeReuseIntent()
        st = consumed.state
        return consumed.value
    }

    fun setReusePlayerOnSwitch(reuse: Boolean) {
        st = st.setReuseIntent(reuse)
    }

    fun setReleasePlayerOnSwitch(release: Boolean) {
        st = st.setReleaseIntent(release)
    }

    fun clearTriedLines() {
        st = st.linesExhausted()
    }

    fun setUserPickedLine(picked: Boolean) {
        st = st.copy(userPickedLine = picked)
    }

    fun resetAutoRetryState() {
        st = st.userSelfRescue()
    }

    fun prepareUserReplay() {
        resetAutoRetryState()
        clearTriedLines()
        setPlaybackStarted(false)
    }

    fun setPlaybackStarted(started: Boolean) {
        st = st.copy(playbackStarted = started)
    }

    fun setPlayTimeoutBasePosition(position: Long) {
        st = st.copy(playTimeoutBasePosition = position)
    }

    fun playTimeoutBasePosition(): Long {
        return st.playTimeoutBasePosition
    }

    fun isStartedPlayState(state: PlayState): Boolean {
        return state == PlayState.PREPARED || state == PlayState.BUFFERED || state == PlayState.PLAYING
    }

    fun markPlaybackStarted() {
        st = st.copy(playbackStarted = true)
        SlowOpenSignal.disarm()
        WatchProgressStore.onPlayStart(progressKey())
        cancelPlayTimeout()
    }

    fun onKernelPlayStateChanged(playState: PlayState): Boolean {
        if (webPlayUrl() == null || !isStartedPlayState(playState)) return false
        markPlaybackStarted()
        return true
    }

    fun onKernelPlayError() {
        rememberErrorHttpStatus()
        if (isPlaybackStarted()) {
            cancelPlayTimeout()
            view?.hideTipOnUiThread()
            if (retryAfterStartedError()) return
            scope.launch {
                if (autoRetry()) return@launch
                stopMusicSessionForFailedPlayback()
                showTerminalError()
            }
            return
        }
        scope.launch {
            if (!autoRetry()) {
                cancelPlayTimeout()
                stopMusicSessionForFailedPlayback()
                showTerminalError()
            }
        }
    }

    private fun showTerminalError() {
        val bridge = view ?: return
        if (!bridge.isPageAlive()) return
        bridge.showTip(terminalErrorText(str(R.string.player_error_play), playbackError = true), false, true)
    }

    fun onPlaybackCompleted() {
        if (!st.skipEndPending) return
        st = st.consumeSkipEnd()
        val preProgressKey = progressKey()
        val preOwner = progressOwner()
        clearTriedLines()
        val currentVod = vod()
        val episodes = currentVod?.seriesMap?.get(currentVod.playFlag)
        if (currentVod == null || episodes == null || currentVod.playIndex + 1 >= episodes.size) {
            view?.toast(str(R.string.player_last_episode))
            return
        }
        updateSelection(currentVod.playFlag, currentVod.playIndex + 1)
        setUserPickedLine(false)
        setReusePlayerOnSwitch(true)
        play(false)
        if (preProgressKey != null) {
            WatchProgressStore.clear(preOwner ?: "", preProgressKey, discard = true)
        }
    }

    fun playAdjacentEpisode(forward: Boolean) {
        st = st.consumeSkipEnd()
        clearTriedLines()
        val boundaryText = if (forward) R.string.player_last_episode else R.string.player_first_episode
        val currentVod = vod()
        val episodes = currentVod?.seriesMap?.get(currentVod.playFlag)
        if (currentVod == null || episodes == null) {
            view?.toast(str(boundaryText))
            return
        }
        val nextIndex = if (forward) currentVod.playIndex + 1 else currentVod.playIndex - 1
        if (nextIndex < 0 || nextIndex >= episodes.size) {
            view?.toast(str(boundaryText))
            return
        }
        updateSelection(currentVod.playFlag, nextIndex)
        setUserPickedLine(false)
        setReusePlayerOnSwitch(true)
        play(false)
    }

    fun onNearEndProgress(positionMs: Long, durationMs: Long) {
        if (!st.skipEndPending) return
        val et = playerCfg?.optInt("et", 0) ?: 0
        if (!NearEndRules.shouldAdvance(positionMs, durationMs, et)) return
        onPlaybackCompleted()
    }

    fun terminalErrorText(err: String, playbackError: Boolean): String {
        val httpStatus = if (playbackError) errorHttpStatusForTip() else 0
        return when {
            isSourceStub() -> str(R.string.player_source_invalid)
            isIsoBandwidthStopped() -> str(R.string.player_iso_bandwidth_low)
            isLastErrorExceedsDecodeCapability() -> str(
                if (isLastErrorTenBitFormat()) R.string.player_decode_10bit_tip
                else R.string.player_decode_exceeds_capability,
            )
            httpStatus > 0 -> str(R.string.player_error_play_http, httpStatus)
            else -> err
        }
    }

    fun isIsoBandwidthStopped(): Boolean =
        (st.isoSession || IsoSessionSignal.escalated()) && st.isoBandwidthSwitchTried

    fun isSourceStub(): Boolean = IsoSessionSignal.stubBytes() > 0

    fun isIsoEscalated(): Boolean = IsoSessionSignal.escalated()

    fun isPlaybackStarted(): Boolean {
        if (st.playbackStarted) return true
        val bridge = view ?: return false
        return isStartedPlayState(bridge.playState()) || hasPlaybackProgress(bridge.currentPosition()) || bridge.isPlaying()
    }

    private fun hasPlaybackProgress(progress: Long): Boolean {
        return progress > Math.max(st.playTimeoutBasePosition, 0) + 1000
    }

    fun startResolvePlayUrlTimeout() {
        timeouts.startResolvePlayUrlTimeout(getResolvePlayUrlTimeoutMs())
    }

    private fun getResolvePlayUrlTimeoutMs(): Long {
        val bean = sourceBean() ?: return PlaybackTimeouts.RESOLVE_PLAY_URL_TIMEOUT_MS
        return Math.max(PlaybackTimeouts.RESOLVE_PLAY_URL_TIMEOUT_MS, (bean.getPlayTimeoutSeconds() + 1L) * 1000L)
    }

    fun startSwitchLinePlayTimeout(isoSession: Boolean) {
        if (!st.allowAutoSwitchLine) {
            cancelPlayTimeout()
            return
        }
        cancelPlayTimeout()
        val timeoutMs = PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession, st.localProxySession)
        LOG.i("echo-switchLinePlay start timeout:$timeoutMs localProxy=${st.localProxySession}")
        timeouts.startSwitchLinePlayTimeout(timeoutMs)
    }

    fun extendSwitchLinePlayTimeout(graceMs: Long) {
        if (!st.allowAutoSwitchLine) {
            cancelPlayTimeout()
            return
        }
        cancelPlayTimeout()
        LOG.i("echo-switchLinePlay extend grace:$graceMs")
        timeouts.startSwitchLinePlayTimeout(graceMs)
    }

    fun cancelSwitchLinePlayTimeout() {
        cancelPlayTimeout()
    }

    fun cancelPlayTimeout() {
        timeouts.cancelPlayTimeout()
    }

    fun cancelResolvePlayUrlTimeout() {
        timeouts.cancelResolvePlayUrlTimeout()
    }

    fun retryAfterStartedError(): Boolean {
        return retry.retryAfterStartedError()
    }

    suspend fun autoRetry(): Boolean {
        return retry.autoRetry()
    }

    suspend fun tryNextLineIfEnabled(): Boolean {
        return retry.tryNextLineIfEnabled()
    }

    suspend fun tryNextLine(): Boolean {
        return retry.tryNextLine()
    }

    fun handleResolvePlayUrlTimeout() {
        scope.launch { retry.handleResolvePlayUrlTimeout() }
    }

    fun handleResolvePlayUrlFailed(err: String) {
        scope.launch { retry.handleResolvePlayUrlFailed(err) }
    }

    fun handleSwitchLinePlayTimeout() {
        scope.launch { retry.handleSwitchLinePlayTimeout() }
    }

    private var webPlayUrl: String? = null
    private var webHeaderMap: HashMap<String, String>? = null
    private var webUserAgent: String? = null

    private val fetch: PlaybackFetch = PlaybackFetch(this)

    private var currentSession: PlaybackSession? = null

    fun currentSession(): PlaybackSession? = currentSession

    fun clearSession() {
        currentSession = null
    }

    fun startVideoPlayback(
        view: MyVideoView,
        url: String,
        headers: HashMap<String, String>?,
        forceExoPlayer: Boolean,
        releaseKernel: () -> Unit,
        trackMemoryKey: String,
        renderRebuildGuard: (() -> Boolean)? = null,
        startLineTimeoutIso: Boolean? = null,
    ): Boolean {
        if (renderRebuildGuard != null && view.mediaPlayer != null && renderRebuildGuard() &&
            view.needsRenderRebuild(view.factoryRenderType())
        ) {
            view.requireKernelRebuild()
            LOG.i("echo-render-changed: rebuild kernel on next start")
        }
        if (view.isKernelErrored()) {
            view.requireKernelRebuild()
            LOG.i("echo-kernel-error: rebuild errored kernel on start")
        }
        val rebuildKernel = view.consumeKernelRebuildRequired()
        val kernelPresent = view.mediaPlayer != null
        val reusePlayer =
            KernelReusePolicy.decide(kernelPresent, rebuildKernel, forceExoPlayer, true) == KernelDecision.REUSE
        val sameContent = reusePlayer && isSameStartedContent()
        if (!reusePlayer && kernelPresent) releaseKernel()
        if (sameContent) view.saveCurrentProgress()
        view.setProgressKey(progressKey())
        view.setTrackMemoryKey(trackMemoryKey)
        markContentStarted()
        st = st.copy(skipEndPending = true)
        if (headers != null) {
            view.setUrl(url, headers)
        } else {
            view.setUrl(url)
        }
        if (startLineTimeoutIso != null) startSwitchLinePlayTimeout(startLineTimeoutIso)
        if (reusePlayer) {
            val base = playTimeoutBasePosition()
            view.skipPositionWhenPlay((if (sameContent) view.resumePositionForReplay(base) else base).toInt())
            view.replay(false)
        } else {
            view.start()
        }
        return reusePlayer
    }

    fun markContentStarted() {
        progress.markContentStarted()
    }

    fun onSessionTakenOver(userPickedLine: Boolean) {
        markContentStarted()
        setUserPickedLine(userPickedLine)
    }

    fun onNewPlayArmed(userPickedLine: Boolean, reusePlayerOnSwitch: Boolean) {
        setUserPickedLine(userPickedLine)
        if (reusePlayerOnSwitch) setReusePlayerOnSwitch(true)
    }

    fun clearStartedContent() {
        progress.clearStartedContent()
    }

    fun startedPlaybackKey(): String? = progress.startedPlaybackKey()

    fun startedProgressKey(): String? = progress.startedProgressKey()

    fun isSameContentRestart(): Boolean {
        val refresh = st.consumeRefreshKeepsProgress()
        st = refresh.state
        return ProgressSampling.restartKeepsProgress(
            st.userPickedLine,
            progress.startedPlaybackKey(),
            currentSession?.playbackKey(),
            refresh.value,
        )
    }

    fun isSameStartedContent(): Boolean = progress.isSameStartedContent()

    fun initFetch() {
        fetch.init()
    }

    fun releaseFetch() {
        fetch.release()
    }

    fun viewBridge(): PlaybackViewBridge? = view

    fun setCurrentArtwork(artwork: String?) {
        music.setCurrentArtwork(artwork)
    }

    fun webPlayUrl(): String? = webPlayUrl

    fun setWebPlayUrl(webPlayUrl: String?) {
        this.webPlayUrl = webPlayUrl
    }

    fun webHeaderMap(): HashMap<String, String>? = webHeaderMap

    fun setWebHeaderMap(webHeaderMap: HashMap<String, String>?) {
        this.webHeaderMap = webHeaderMap
    }

    fun webUserAgent(): String? = webUserAgent

    fun setWebUserAgent(webUserAgent: String?) {
        this.webUserAgent = webUserAgent
    }

    fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String) {
        resolver.initParse(flag, useParse, playUrl, url)
    }

    fun requestParse(pb: ParseBean) {
        resetAutoRetryState()
        clearTriedLines()
        doParse(pb)
    }

    fun doParse(pb: ParseBean) {
        clearCastAbort()
        resolver.doParse(pb)
    }

    fun stopParse() {
        resolver.stopParse()
    }

    fun initParseLoadFound() {
        resolver.initParseLoadFound()
    }

    fun isParseResultCurrent(gen: Int): Boolean {
        return resolver.isParseResultCurrent(gen)
    }

    fun stopLoadWebView(destroy: Boolean) {
        resolver.stopLoadWebView(destroy)
    }

    fun isSwitchStopPending(): Boolean {
        return st.switchStopPending
    }

    fun setPendingInherit(key: String?, progress: Long) {
        this.progress.setPendingInherit(key, progress)
    }

    fun publishTitle() {
        if (view == null) return
        val currentVod = vod() ?: return
        val vs = currentSeries(currentVod.playFlag, currentVod.playIndex) ?: return
        view?.setTitle(currentVod.name + " " + vs.name)
    }

    private val starter: PlaybackStarter = PlaybackStarter(object : PlaybackStarter.Host {
        override fun attemptState(): PlaybackAttemptState = st

        override fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState) {
            this@PlaybackController.updateAttemptState(block)
        }

        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun playerCfg(): JSONObject? = this@PlaybackController.playerCfg

        override fun sourceKey(): String = this@PlaybackController.sourceKey()

        override fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? =
            this@PlaybackController.currentSeries(flag, index)

        override fun playSubtitle(): String? = this@PlaybackController.playSubtitle()

        override fun progressKey(): String? = this@PlaybackController.progressKey()

        override fun progressOwner(): String? = this@PlaybackController.progressOwner()

        override fun startedProgressKey(): String? = progress.startedProgressKey()

        override fun isSameContentRestart(): Boolean = this@PlaybackController.isSameContentRestart()

        override fun subtitleCacheKey(): String? = this@PlaybackController.subtitleCacheKey()

        override fun setProgressKey(key: String?) {
            this@PlaybackController.setProgressKey(key)
        }

        override fun setSubtitleCacheKey(key: String?) {
            this@PlaybackController.setSubtitleCacheKey(key)
        }

        override suspend fun getSavedProgress(url: String?): Long = this@PlaybackController.getSavedProgress(url)

        override fun inheritProgressFrom(key: String?, position: Long) {
            this@PlaybackController.inheritProgressFrom(key, position)
        }

        override fun inheritProgressIfNeeded() {
            this@PlaybackController.inheritProgressIfNeeded()
        }

        override fun migrateProgressOnLineSwitch() {
            this@PlaybackController.migrateProgressOnLineSwitch()
        }

        override fun clearInheritProgress() {
            progress.clearInheritProgress()
        }

        override fun handleResolvePlayUrlFailed(err: String) {
            this@PlaybackController.handleResolvePlayUrlFailed(err)
        }

        override fun publishTitle() {
            this@PlaybackController.publishTitle()
        }

        override fun beginNewPlay() {
            this@PlaybackController.beginNewPlay()
        }

        override fun consumeReusePlayerOnSwitch(): Boolean = this@PlaybackController.consumeReusePlayerOnSwitch()

        override fun stopParse() {
            this@PlaybackController.stopParse()
        }

        override fun initParseLoadFound() {
            this@PlaybackController.initParseLoadFound()
        }

        override fun setWebPlayUrl(url: String?) {
            this@PlaybackController.setWebPlayUrl(url)
        }

        override fun setWebHeaderMap(headers: HashMap<String, String>?) {
            this@PlaybackController.setWebHeaderMap(headers)
        }

        override fun startResolvePlayUrlTimeout() {
            this@PlaybackController.startResolvePlayUrlTimeout()
        }

        override fun startSwitchLinePlayTimeout(isoSession: Boolean) {
            this@PlaybackController.startSwitchLinePlayTimeout(isoSession)
        }

        override fun cancelPlayTimeout() {
            this@PlaybackController.cancelPlayTimeout()
        }

        override fun closeCastPrepare() {
            this@PlaybackController.closeCastPrepare()
        }

        override fun setPlayTimeoutBasePosition(position: Long) {
            this@PlaybackController.setPlayTimeoutBasePosition(position)
        }

        override fun invalidatePreload() {
            this@PlaybackController.invalidatePreload()
        }

        override fun syncDecodeFromGlobal() {
            config.syncDecodeFromGlobal()
        }

        override fun preloadConsumeResult(key: String?): Boolean = preload.consumeResult(key)

        override fun sourceViewModel(): SourceViewModel? = fetch.sourceViewModel()

        override fun resolverNextGen() {
            resolver.nextGen()
        }

        override fun resolverCurrentGen(): Int = resolver.currentGen()

        override fun resolverIsParseResultCurrent(gen: Int): Boolean = resolver.isParseResultCurrent(gen)

        override fun resolverCancelParseTimeout() {
            resolver.cancelParseTimeout()
        }
    })

    fun play(reset: Boolean) {
        clearCastAbort()
        starter.play(reset)
    }

    fun isCrossContentReuseAllowed(): Boolean = starter.isCrossContentReuseAllowed()

    private fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?) {
        starter.playUrl(gen, url, headers)
    }

    fun playUrl(url: String, headers: HashMap<String, String>?) {
        starter.playUrl(url, headers)
    }

    fun goPlayUrl(url: String, headers: HashMap<String, String>?) {
        st = st.copy(localProxySession = SourcePolicy.isLocalProxyUrl(url))
        SlowOpenSignal.reset()
        starter.goPlayUrl(url, headers)
    }

    private val preload: PlaybackPreload = PlaybackPreload(object : PlaybackPreload.Host {
        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun sourceViewModel(): SourceViewModel? = fetch.sourceViewModel()

        override fun ensureFetch() {
            initFetch()
        }

        override fun onPreloadedResult(info: JSONObject?) {
            st = st.copy(usedPreloadedResult = true)
            fetch.deliver(info)
        }
    })

    fun initPreload() {
        preload.init()
    }

    fun onPlayerStateForPreload(playState: PlayState) {
        preload.onPlayerState(playState)
    }

    fun invalidatePreload() {
        preload.invalidate()
    }

    fun destroyPreload() {
        preload.destroy()
    }

    private val music: MusicSessionDelegate = MusicSessionDelegate(object : MusicSessionDelegate.Host {
        override fun view(): PlaybackViewBridge? = this@PlaybackController.view

        override fun attemptState(): PlaybackAttemptState = st

        override fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState) {
            this@PlaybackController.updateAttemptState(block)
        }

        override fun timeouts(): PlaybackTimeouts = this@PlaybackController.timeouts

        override fun vod(): VodInfo? = this@PlaybackController.vod

        override fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries? =
            this@PlaybackController.currentSeries(flag, index)

        override fun quality(): JSONObject? = qualityResult

        override fun isStartedPlayState(state: PlayState): Boolean = this@PlaybackController.isStartedPlayState(state)

        override fun retryAfterStartedError(): Boolean = this@PlaybackController.retryAfterStartedError()

        override fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String) {
            this@PlaybackController.initParse(flag, useParse, playUrl, url)
        }

        override fun playUrl(url: String, headers: HashMap<String, String>?) {
            this@PlaybackController.playUrl(url, headers)
        }
    })

    fun beginSwitchPlayback() {
        music.beginSwitchPlayback()
    }

    fun playArtwork(): String? = music.playArtwork()

    fun currentArtwork(): String? = music.currentArtwork()

    fun playDanmu(): String? = music.playDanmu()

    fun onDanmuChecked(danmu: String?) {
        music.setPlayDanmu(danmu)
    }

    fun cancelInFlight() {
        cancelPlayTimeout()
        cancelSwitchLinePlayTimeout()
        cancelResolvePlayUrlTimeout()
        fetch.cancelPlayRequest()
        stopParse()
    }

    fun stopPlaybackForPageExit() {
        st = st.clearSessionFlags()
        music.resetAudioSession()
        timeouts.cancelPendingCompletionDrop()
        cancelInFlight()
        ApiConfig.get().setCurrentPlaySourceKey("")
        music.stopMusicSession()
    }

    fun stopMusicSessionForFailedPlayback() {
        music.stopMusicSessionForFailedPlayback()
    }

    fun stopMusicSession() {
        music.stopMusicSession()
    }

    fun onHostDestroy() {
        st = st.clearSessionFlags()
        music.resetAudioSession()
        timeouts.cancelPendingCompletionDrop()
        cancelPlayTimeout()
        cancelResolvePlayUrlTimeout()
        stopParse()
        music.stopMusicSession()
        destroyPreload()
    }

    fun isConfirmedAudioOnly(): Boolean {
        return music.isConfirmedAudioOnly()
    }

    fun isAudioOnlyContent(): Boolean {
        return music.isAudioOnlyContent()
    }

    fun setMusicAudioOnly(enabled: Boolean) {
        music.setMusicAudioOnly(enabled)
    }

    fun handlePlayStateForMusicSession(playState: PlayState): Boolean {
        return music.handlePlayStateForMusicSession(playState)
    }

    fun ensureAudioOnlyRender() {
        music.ensureAudioOnlyRender()
    }

    fun updateMusicSession() {
        music.updateMusicSession()
    }

    fun selectQuality(position: Int): Boolean {
        clearCastAbort()
        return music.selectQuality(position)
    }

    companion object {

        @JvmStatic
        fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        @JvmStatic
        fun extractHeaders(playResult: JSONObject?): HashMap<String, String>? {
            return SourceHelper.extractPlayHeaders(playResult)
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun putHeaders(target: JSONObject?, headers: HashMap<String, String>?) {
            if (target == null || headers == null) return
            for (key in headers.keys) {
                target.put(key, headers[key])
            }
        }

        @JvmStatic
        fun headerValue(headers: HashMap<String, String>?, name: String?): String? {
            if (headers == null || name == null) return null
            for (key in headers.keys) {
                if (name.equals(key, ignoreCase = true)) {
                    return headers[key]
                }
            }
            return null
        }

        @JvmStatic
        fun looksLikeAudioUrl(url: String?): Boolean {
            if (url == null || url.isEmpty()) return false
            var lower = url.lowercase(Locale.getDefault())
            val query = lower.indexOf('?')
            if (query >= 0) lower = lower.substring(0, query)
            val fragment = lower.indexOf('#')
            if (fragment >= 0) lower = lower.substring(0, fragment)
            return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".opus") || lower.endsWith(".wma")
        }
    }
}
