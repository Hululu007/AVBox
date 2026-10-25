package com.github.avbox.osc.player

import android.net.Uri
import android.text.TextUtils
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.EpisodeMatcher
import com.github.avbox.osc.player.core.DecodeDecision
import com.github.avbox.osc.player.core.RetryPolicy
import com.github.avbox.osc.player.core.SlowOpenSignal
import com.github.avbox.osc.player.core.VideoCodecInfo
import com.github.avbox.osc.player.engine.iso.IsoAudioFallback
import com.github.avbox.osc.player.engine.iso.IsoBandwidthGuard
import com.github.avbox.osc.player.engine.iso.IsoSessionSignal
import com.github.avbox.osc.player.engine.iso.IsoThroughput
import com.github.avbox.osc.player.engine.iso.stopsIsoBandwidthRetry
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import org.json.JSONObject
import java.util.HashMap

class PlaybackRetryDelegate(private val host: Host) {

    interface Host {
        fun attemptState(): PlaybackAttemptState

        fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState)

        fun view(): PlaybackViewBridge?

        fun playerCfg(): JSONObject?

        fun commitPlayerCfg(mutate: (JSONObject) -> Unit, persist: Boolean): Boolean

        fun vod(): VodInfo?

        fun updateSelection(flag: String?, index: Int)

        fun sourceBean(): SourceBean?

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun progressKey(): String?

        suspend fun getSavedProgress(url: String): Long

        fun inheritProgressFrom(key: String?, position: Long)

        fun videoCodecInfo(): VideoCodecInfo

        fun webPlayUrl(): String?

        fun webHeaderMap(): HashMap<String, String>?

        fun resolverHasFoundUrls(): Boolean

        fun resolverConsumeFoundUrl()

        fun play(reset: Boolean)

        fun playUrl(url: String, headers: HashMap<String, String>?)

        fun stopParse()

        fun initParseLoadFound()

        fun cancelPlayRequest()

        fun cancelPlayTimeout()

        fun startSwitchLinePlayTimeout(isoSession: Boolean)

        fun extendSwitchLinePlayTimeout(graceMs: Long)

        fun isPlaybackStarted(): Boolean

        fun isCrossContentReuseAllowed(): Boolean

        fun stopMusicSessionForFailedPlayback()

        fun closeCastPrepare()
    }

    private fun restoreAutoSwitchedPlayer() {
        val st = host.attemptState()
        if (st.autoSwitchedPlayerType < 0) return
        host.updateAttemptState { it.setReleaseIntent(true) }
        try {
            val cfg = requireNotNull(host.playerCfg())
            AppLog.i("echo-autoRetry restore player: " + cfg.optInt("pl", -1) + " -> " + st.autoSwitchedPlayerType)
            host.commitPlayerCfg({ it.put("pl", st.autoSwitchedPlayerType) }, false)
        } catch (th: Throwable) {
            AppLog.e("PlaybackController", th)
        } finally {
            host.updateAttemptState { it.copy(autoSwitchedPlayerType = -1) }
        }
    }

    private fun restoreAutoSwitchedDecode() {
        val st = host.attemptState()
        if (st.autoSwitchedDecodeOld == null) return
        host.updateAttemptState { it.copy(hasAutoSwitchedDecode = false) }
        try {
            val cfg = host.playerCfg()
            if (cfg != null) {
                AppLog.i("echo-autoRetry restore decode: " + cfg.optString(st.autoSwitchedDecodeKey, "") + " -> " + st.autoSwitchedDecodeOld)
                host.commitPlayerCfg({ it.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld) }, false)
            }
        } catch (th: Throwable) {
            AppLog.e("PlaybackController", th)
        } finally {
            host.updateAttemptState { it.copy(autoSwitchedDecodeOld = null) }
        }
    }

    private fun exoLastErrorKind(): Int {
        try {
            val live = host.view()?.mediaPlayer()
            if (live is ExoPlayer) return live.lastErrorKind()
        } catch (ignored: Throwable) {
            AppLog.d("PlaybackController", "exo error kind probe failed")
        }
        return ExoPlayer.ERROR_KIND_UNKNOWN
    }

    private fun exoLastErrorTrackKind(): Int {
        try {
            val live = host.view()?.mediaPlayer()
            if (live is ExoPlayer) return live.lastErrorTrackKind()
        } catch (ignored: Throwable) {
            AppLog.d("PlaybackController", "exo error track kind probe failed")
        }
        return ExoPlayer.ERROR_TRACK_UNKNOWN
    }

    private fun exoLastErrorFormatId(): String? {
        try {
            val live = host.view()?.mediaPlayer()
            if (live is ExoPlayer) return live.lastErrorFormatId()
        } catch (ignored: Throwable) {
            AppLog.d("PlaybackController", "exo error format id probe failed")
        }
        return null
    }

    private fun tryAudioTrackFallback(): Boolean {
        val st = host.attemptState()
        if (st.hasAutoSwitchedAudioTrack) return false
        val webUrl = host.webPlayUrl() ?: return false
        if (TextUtils.isEmpty(webUrl)) return false
        val pid = IsoAudioFallback.pidOfFormatId(exoLastErrorFormatId())
        if (pid == null) {
            AppLog.i("echo-autoRetry audio track fallback: no pid")
            return false
        }
        host.updateAttemptState { it.copy(hasAutoSwitchedAudioTrack = true) }
        IsoAudioFallback.suppress(Uri.parse(webUrl).toString(), pid)
        AppLog.i("echo-autoRetry audio track fallback:$pid")
        val view = host.view()
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_audio_track_fallback_tip)) }
        }
        host.stopParse()
        host.initParseLoadFound()
        host.updateAttemptState { it.copy(playbackStarted = false) }
        if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
        if (view != null) host.playUrl(webUrl, host.webHeaderMap())
        host.updateAttemptState { it.copy(hasAutoSwitchedAudioTrack = true) }
        return true
    }

    private fun softwareDecodeCandidateName(): String? {
        val exo = host.view()?.mediaPlayer() as? ExoPlayer ?: return null
        val mime = exo.selectedVideoFormat?.sampleMimeType ?: return null
        return runCatching {
            MediaCodecSelector.PREFER_SOFTWARE.getDecoderInfos(mime, false, false)
        }.getOrNull()?.firstOrNull()?.name
    }

    private fun hardDecodeCandidateName(): String? {
        val exo = host.view()?.mediaPlayer() as? ExoPlayer ?: return null
        val mime = exo.selectedVideoFormat?.sampleMimeType ?: return null
        return runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
        }.getOrNull()?.firstOrNull()?.name
    }

    private fun tryDecodeFallback(): Boolean {
        if (exoLastErrorTrackKind() == ExoPlayer.ERROR_TRACK_AUDIO) return false
        val st = host.attemptState()
        val cfg = host.playerCfg() ?: return false
        if (st.hasAutoSwitchedDecode) return false
        if (cfg.optInt("pl", 2) >= 10) return false
        val decodeKey = "exo"
        val toSoftware = when (cfg.optString(decodeKey, "")) {
            "硬解码" -> true // i18n: keep
            "软解码" -> false // i18n: keep
            else -> return false
        }
        val codecInfo = host.videoCodecInfo()
        val currentCodec = codecInfo.decoderName
        if (toSoftware) {
            if (DecodeDecision.shouldSkipSoftDecodeFallback(
                    softwareDecodeCandidateName(),
                    currentCodec,
                    codecInfo.kind,
                )
            ) {
                AppLog.i("echo-autoRetry skip soft decode: software target already in use ($currentCodec)")
                return false
            }
        } else {
            val hardCandidate = hardDecodeCandidateName()
            if (DecodeDecision.shouldSkipHardDecodeFallback(
                    hardCandidate,
                    currentCodec,
                    codecInfo.kind,
                )
            ) {
                AppLog.i(
                    "echo-autoRetry skip hard decode: kind=${codecInfo.kind}" +
                        " current=$currentCodec candidate=$hardCandidate",
                )
                return false
            }
        }
        val webUrl = host.webPlayUrl() ?: return false
        if (TextUtils.isEmpty(webUrl)) return false
        val oldDecode = cfg.optString(decodeKey, "")
        if (!host.commitPlayerCfg({ it.put(decodeKey, if (toSoftware) "软解码" else "硬解码") }, false)) { // i18n: keep
            return false
        }
        AppLog.i(
            (if (toSoftware) "echo-autoRetry hard->soft decode: " else "echo-autoRetry soft->hard decode: ") + webUrl,
        )
        host.updateAttemptState {
            it.copy(autoSwitchedDecodeOld = oldDecode, autoSwitchedDecodeKey = decodeKey, hasAutoSwitchedDecode = true)
        }
        host.stopParse()
        host.initParseLoadFound()
        val view = host.view()
        if (view != null && view.isPageAlive()) {
            val tip = if (toSoftware) {
                R.string.player_decode_fallback_tip
            } else {
                R.string.player_decode_fallback_hard_tip
            }
            view.runOnUi { view.toast(PlaybackController.str(tip)) }
        }
        view?.releasePlayer()
        if (view != null) host.playUrl(webUrl, host.webHeaderMap())
        return true
    }

    private fun dropRetryIfCastAborted(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.castAborted) return false
        AppLog.i("echo-cast abort: drop retry ($reason)")
        host.cancelPlayRequest()
        host.stopParse()
        host.cancelPlayTimeout()
        return true
    }

    fun retryAfterStartedError(): Boolean {
        if (dropRetryIfCastAborted("retryAfterStartedError")) return false
        val st = host.attemptState()
        if (exoLastErrorKind() == ExoPlayer.ERROR_KIND_DECODE
            && exoLastErrorTrackKind() == ExoPlayer.ERROR_TRACK_AUDIO
        ) {
            return tryAudioTrackFallback()
        }
        if (isoSessionLike(st) && exoLastErrorKind() != ExoPlayer.ERROR_KIND_DECODE) {
            val view = host.view()
            val durationMs = view?.mediaPlayer()?.duration?.takeIf { it > 0L } ?: (view?.duration() ?: 0L)
            val verdict = IsoBandwidthGuard.evaluate(durationMs)
            if (verdict != null) {
                AppLog.i("echo-iso-bandwidth-mid:${verdict.measuredBytesPerSecond}:${verdict.requiredBytesPerSecond}")
                view?.showTip(PlaybackController.str(R.string.player_iso_bandwidth_low), false, true)
                host.updateAttemptState { it.copy(isoBandwidthSwitchTried = true, playbackStarted = false) }
                return false
            }
        }
        if (st.hasRetriedAfterStart) return false
        val webUrl = host.webPlayUrl() ?: return false
        if (TextUtils.isEmpty(webUrl)) return false
        host.updateAttemptState { it.copy(hasRetriedAfterStart = true, hasRetriedSameUrlOnBoot = true) }
        AppLog.i("echo-autoRetry retry after started error: " + host.webPlayUrl())
        val view = host.view()
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_play_error_retry)) }
        }
        host.stopParse()
        host.initParseLoadFound()
        host.updateAttemptState { it.copy(playbackStarted = false) }
        if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
        if (view != null) host.playUrl(webUrl, host.webHeaderMap())
        return true
    }

    private fun retryWithFreshResolve(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.usedPreloadedResult) return false
        host.updateAttemptState { it.copy(usedPreloadedResult = false, playbackStarted = false) }
        AppLog.i("echo-preload-stale: re-resolve current episode ($reason)")
        host.stopParse()
        host.initParseLoadFound()
        if (!host.isCrossContentReuseAllowed()) host.view()?.releasePlayer()
        host.play(false)
        return true
    }

    private fun exoLastErrorCodeName(): String? {
        try {
            val live = host.view()?.mediaPlayer()
            if (live is ExoPlayer) return live.lastErrorCodeName()
        } catch (ignored: Throwable) {
            AppLog.d("PlaybackController", "exo error code probe failed")
        }
        return null
    }

    private fun retryWithFreshResolveOnFormatError(): Boolean {
        if (!RetryPolicy.shouldRefreshResolve(exoLastErrorCodeName())) return false
        if (TextUtils.isEmpty(host.webPlayUrl())) return false
        val st = host.attemptState()
        if (st.hasRefreshedOnFormatError) return false
        host.updateAttemptState { it.copy(hasRefreshedOnFormatError = true, refreshKeepsProgress = true, playbackStarted = false) }
        AppLog.i("echo-autoRetry refresh resolve on malformed manifest: " + host.webPlayUrl())
        host.stopParse()
        host.initParseLoadFound()
        if (!host.isCrossContentReuseAllowed()) host.view()?.releasePlayer()
        host.play(false)
        return true
    }

    private suspend fun retryIsoHttpError(): Boolean {
        val st = host.attemptState()
        if (!isoSessionLike(st)) return false
        if (!RetryPolicy.isHttpStatusError(exoLastErrorCodeName())) return false
        if (st.hasRefreshedOnIsoHttpError) {
            AppLog.i("echo-iso-refresh-resolve-exhausted, try next line: " + host.webPlayUrl())
            return tryNextLineIfEnabled()
        }
        host.updateAttemptState { it.copy(hasRefreshedOnIsoHttpError = true, refreshKeepsProgress = true, playbackStarted = false) }
        AppLog.i("echo-iso-refresh-resolve: " + host.webPlayUrl())
        host.stopParse()
        host.initParseLoadFound()
        if (!host.isCrossContentReuseAllowed()) host.view()?.releasePlayer()
        host.play(false)
        return true
    }

    suspend fun autoRetry(): Boolean {
        if (dropRetryIfCastAborted("autoRetry")) return false
        val st = host.attemptState()
        if (retryWithFreshResolve("autoRetry")) return true
        if (retryIsoHttpError()) return true
        if (stopsIsoBandwidthRetry(isoSessionLike(st), st.isoBandwidthSwitchTried)) {
            AppLog.i("echo-iso-bandwidth-exhausted")
            return false
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - st.lastRetryTime > 60_000) {
            AppLog.i("echo-reset-autoRetryCount")
            host.updateAttemptState { it.resetAutoRetryLadder() }
        }
        host.updateAttemptState { it.copy(lastRetryTime = currentTime) }
        if (host.resolverHasFoundUrls()) {
            host.resolverConsumeFoundUrl()
            return true
        }
        val exoErrorKind = exoLastErrorKind()
        if (retryWithFreshResolveOnFormatError()) return true
        val webUrl = host.webPlayUrl()
        if (exoErrorKind != ExoPlayer.ERROR_KIND_DECODE
            && !host.attemptState().hasRetriedSameUrlOnBoot && !TextUtils.isEmpty(webUrl)
        ) {
            host.updateAttemptState { it.copy(hasRetriedSameUrlOnBoot = true) }
            AppLog.i("echo-autoRetry replay same url before decode fallback: " + webUrl)
            host.stopParse()
            host.initParseLoadFound()
            val view = host.view()
            if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
            if (view != null) host.playUrl(requireNotNull(webUrl), host.webHeaderMap())
            return true
        }
        if (exoErrorKind == ExoPlayer.ERROR_KIND_DECODE
            && exoLastErrorTrackKind() == ExoPlayer.ERROR_TRACK_AUDIO
        ) {
            return tryAudioTrackFallback()
        }
        if (exoErrorKind != ExoPlayer.ERROR_KIND_NETWORK && tryDecodeFallback()) return true
        if (webUrl != null) {
            val beforeSwitch = host.attemptState()
            if (beforeSwitch.allowSwitchPlayer && !beforeSwitch.hasAutoSwitchedPlayer) {
                AppLog.i("echo-autoRetry switch player and replay current url")
                val playerType = requireNotNull(host.playerCfg()).optInt("pl", -1)
                val view = host.view()
                val switchSkipped = view != null && view.switchPlayerKernel()
                host.updateAttemptState { it.copy(hasAutoSwitchedPlayer = true, allowSwitchPlayer = false) }
                if (!switchSkipped) {
                    host.updateAttemptState { it.copy(autoSwitchedPlayerType = playerType) }
                    host.stopParse()
                    host.initParseLoadFound()
                    if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
                    if (view != null) host.playUrl(webUrl, host.webHeaderMap())
                    return true
                }
            }
            AppLog.i("echo-autoRetry current url failed after player switch, try next line")
            return tryNextLineIfEnabled()
        }
        return tryNextLineIfEnabled()
    }

    suspend fun tryNextLineIfEnabled(): Boolean {
        restoreAutoSwitchedPlayer()
        restoreAutoSwitchedDecode()
        if (host.attemptState().allowAutoSwitchLine && (KV.get(KVKeys.AUTO_SWITCH_LINE, KVKeys.AUTO_SWITCH_LINE_DEFAULT) as Boolean)) {
            return tryNextLine()
        }
        AppLog.i("echo-autoRetry line switching disabled")
        host.updateAttemptState { it.resetAutoRetryLadder() }
        return false
    }

    suspend fun tryNextLine(): Boolean {
        val vod = host.vod()
        val seriesMap = vod?.seriesMap
        if (vod == null || seriesMap == null || seriesMap.isEmpty()) {
            host.updateAttemptState { it.linesExhausted() }
            return false
        }
        val currentFlag = vod.playFlag
        val currentIndex = Math.max(vod.playIndex, 0)
        val currentSeries = host.currentSeries(currentFlag, currentIndex)
        var tried = host.attemptState().triedLineFlags
        if (!currentFlag.isNullOrEmpty()) {
            host.updateAttemptState { it.withTriedLine(currentFlag) }
            tried = tried + currentFlag
        }
        val lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod)
        val currentLineIndex = EpisodeMatcher.lineFlagIndex(lineFlags, currentFlag)
        val startLineIndex = if (currentLineIndex >= 0) currentLineIndex + 1 else 0
        var nextFlag: String? = null
        var nextIndex = 0
        for (i in startLineIndex until lineFlags.size) {
            val flag = lineFlags[i]
            val seriesList = seriesMap[flag]
            if (!tried.contains(flag) && seriesList != null && seriesList.isNotEmpty()) {
                nextFlag = flag
                nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, seriesList, currentIndex)
                break
            }
        }
        val view = host.view()
        if (nextFlag == null) {
            AppLog.i("echo-autoRetry all lines exhausted")
            host.updateAttemptState { it.linesExhausted() }
            return view != null && view.onLinesExhausted()
        }
        val flagToSwitch = nextFlag
        val preProgressKey = host.progressKey()
        val savedProgress = if (preProgressKey.isNullOrEmpty()) 0L else host.getSavedProgress(preProgressKey)
        val preProgress = Math.max(savedProgress, if (view == null) 0L else view.currentPosition())
        AppLog.i("echo-autoRetry switch line: " + vod.playFlag + " -> " + flagToSwitch)
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_switch_line, flagToSwitch)) }
        }
        host.updateSelection(flagToSwitch, nextIndex)
        host.updateAttemptState { it.onLineSwitched() }
        IsoSessionSignal.reset()
        host.inheritProgressFrom(preProgressKey, preProgress)
        host.play(false)
        return true
    }

    private fun abortIfCastPrepare(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.castPrepareOnly) return false
        AppLog.i("echo-cast prepare aborted: $reason")
        host.cancelPlayRequest()
        host.stopParse()
        host.cancelPlayTimeout()
        host.closeCastPrepare()
        return true
    }

    suspend fun handleResolvePlayUrlTimeout() {
        if (dropRetryIfCastAborted("resolveTimeout")) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout))
            return
        }
        val st = host.attemptState()
        if (abortIfCastPrepare("resolveTimeout")) return
        if (retryWithFreshResolve("resolveTimeout")) return
        AppLog.i("echo-resolvePlayUrl timeout, try next line")
        host.cancelPlayRequest()
        host.stopParse()
        if (st.userPickedLine) {
            host.updateAttemptState { it.copy(userPickedLine = false) }
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout))
            return
        }
        if (!tryNextLineIfEnabled()) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout))
        }
    }

    suspend fun handleResolvePlayUrlFailed(err: String) {
        if (dropRetryIfCastAborted("resolveFailed")) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(resolveFailedTip(err))
            return
        }
        val st = host.attemptState()
        AppLog.i("echo-resolvePlayUrl failed, try next line: $err")
        if (abortIfCastPrepare(err)) return
        host.cancelPlayRequest()
        host.stopParse()
        if (st.userPickedLine) {
            host.updateAttemptState { it.copy(userPickedLine = false) }
            host.cancelPlayTimeout()
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(resolveFailedTip(err))
            return
        }
        if (tryNextLineIfEnabled()) return
        host.cancelPlayTimeout()
        host.stopMusicSessionForFailedPlayback()
        showErrorTip(resolveFailedTip(err))
    }

    private fun resolveFailedTip(err: String): String {
        val site = host.sourceBean()?.name?.trim().orEmpty()
        if (site.isEmpty()) return err
        val text = PlaybackController.str(R.string.player_site_failed, site, err)
        if (text.isEmpty()) return err
        AppLog.i("echo-resolve-failed tip: $text")
        return text
    }

    suspend fun handleSwitchLinePlayTimeout() {
        if (dropRetryIfCastAborted("switchLineTimeout")) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_play_timeout))
            return
        }
        val st = host.attemptState()
        val view = host.view()
        val state = view?.playState()
        AppLog.i("echo-switchLinePlay timeout state: $state, started: " + st.playbackStarted)
        if (abortIfCastPrepare("switchLineTimeout")) return
        val slowOpenExtend = st.localProxySession && !isoSessionLike(st) &&
            SlowOpenSignal.shouldExtend(host.isPlaybackStarted(), st.slowOpenExtended)
        if (slowOpenExtend) {
            host.updateAttemptState { it.copy(slowOpenExtended = true) }
            AppLog.i("echo-slow-open extend bytes=${SlowOpenSignal.bytes()} localProxy=${st.localProxySession}")
            host.extendSwitchLinePlayTimeout(PlaybackTimeouts.SLOW_OPEN_GRACE_MS)
            return
        }
        if (host.isPlaybackStarted()) {
            host.cancelPlayTimeout()
            view?.hideTipOnUiThread()
            return
        }
        if (IsoSessionSignal.consumeWindowExtension()) {
            AppLog.i("echo-iso-bdav-timeout-extend")
            host.updateAttemptState { it.copy(isoSession = true) }
            host.startSwitchLinePlayTimeout(true)
            return
        }
        if (host.attemptState().isoSession) {
            val durationMs = view?.mediaPlayer()?.duration?.takeIf { it > 0L } ?: (view?.duration() ?: 0L)
            AppLog.i(
                "echo-iso-bandwidth-probe:dur=$durationMs" +
                    ":bps=${IsoThroughput.bytesPerSecond()}:target=${IsoThroughput.targetLength()}",
            )
            val verdict = IsoBandwidthGuard.evaluate(durationMs)
            if (verdict != null) {
                AppLog.i("echo-iso-bandwidth:${verdict.measuredBytesPerSecond}:${verdict.requiredBytesPerSecond}")
                view?.showTip(PlaybackController.str(R.string.player_iso_bandwidth_low), false, true)
                host.stopParse()
                if (st.isoBandwidthSwitchTried) {
                    host.stopMusicSessionForFailedPlayback()
                    host.cancelPlayTimeout()
                    return
                }
                host.updateAttemptState { it.copy(isoBandwidthSwitchTried = true) }
                if (!tryNextLineIfEnabled()) {
                    host.stopMusicSessionForFailedPlayback()
                    host.cancelPlayTimeout()
                }
                return
            }
        }
        AppLog.i("echo-switchLinePlay timeout, try next line")
        host.stopParse()
        if (st.hasAutoSwitchedPlayer) {
            if (!tryNextLineIfEnabled()) {
                host.stopMusicSessionForFailedPlayback()
                host.cancelPlayTimeout()
                showErrorTip(PlaybackController.str(R.string.player_play_timeout))
            }
            return
        }
        if (!autoRetry()) {
            host.stopMusicSessionForFailedPlayback()
            host.cancelPlayTimeout()
            showErrorTip(PlaybackController.str(R.string.player_play_timeout))
        }
    }

    private fun showErrorTip(err: String) {
        host.view()?.showTip(err, false, true)
    }

    private fun isoSessionLike(st: PlaybackAttemptState): Boolean =
        st.isoSession || IsoSessionSignal.escalated()
}
