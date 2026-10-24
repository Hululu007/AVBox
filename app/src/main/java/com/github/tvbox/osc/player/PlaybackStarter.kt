package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.PlaybackProgress
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.player.engine.MediaSources
import com.github.tvbox.osc.player.engine.SourcePolicy
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.ImgUtil
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.thunder.Thunder
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.HashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlaybackStarter(private val host: Host) {

    interface Host {
        fun attemptState(): PlaybackAttemptState

        fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState)

        fun view(): PlaybackViewBridge?

        fun vod(): VodInfo?

        fun playerCfg(): JSONObject?

        fun sourceKey(): String

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun playSubtitle(): String?

        fun progressKey(): String?

        fun progressOwner(): String?

        fun startedProgressKey(): String?

        fun isSameContentRestart(): Boolean

        fun subtitleCacheKey(): String?

        fun setProgressKey(key: String?)

        fun setSubtitleCacheKey(key: String?)

        suspend fun getSavedProgress(url: String?): Long

        fun inheritProgressFrom(key: String?, position: Long)

        fun inheritProgressIfNeeded()

        fun migrateProgressOnLineSwitch()

        fun clearInheritProgress()

        fun handleResolvePlayUrlFailed(err: String)

        fun publishTitle()

        fun beginNewPlay()

        fun consumeReusePlayerOnSwitch(): Boolean

        fun stopParse()

        fun initParseLoadFound()

        fun setWebPlayUrl(url: String?)

        fun setWebHeaderMap(headers: HashMap<String, String>?)

        fun startResolvePlayUrlTimeout()

        fun startSwitchLinePlayTimeout(isoSession: Boolean)

        fun cancelPlayTimeout()

        fun closeCastPrepare()

        fun setPlayTimeoutBasePosition(position: Long)

        fun invalidatePreload()

        fun syncDecodeFromGlobal()

        fun preloadConsumeResult(key: String?): Boolean

        fun sourceViewModel(): SourceViewModel?

        fun resolverNextGen()

        fun resolverCurrentGen(): Int

        fun resolverIsParseResultCurrent(gen: Int): Boolean

        fun resolverCancelParseTimeout()
    }

    private var playUrlGeneration: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun play(reset: Boolean) {
        val prepareOnly = host.attemptState().castPrepareOnly
        if (prepareOnly) LOG.i("echo-cast prepare: resolve only, no playback side effects")
        host.updateAttemptState { it.copy(switchStopPending = false) }
        host.resolverNextGen()
        host.invalidatePreload()
        host.view()?.hidePreloadReadyTip()
        val vod = host.vod() ?: return
        val kernelPresent = host.view()?.mediaPlayer() != null
        val reusePlayer = if (prepareOnly) {
            false
        } else {
            val idleKernelReused = isIdleKernelReusable(kernelPresent)
            val crossContentReuseAllowed = isCrossContentReuseAllowed()
            val reuseAllowed = host.consumeReusePlayerOnSwitch() || idleKernelReused || crossContentReuseAllowed
            KernelReusePolicy.decide(kernelPresent, false, false, reuseAllowed) == KernelDecision.REUSE
        }
        if (!prepareOnly) {
            host.updateAttemptState { it.copy(switchingPlayback = true) }
            val sameContentRestart = !reset && host.isSameContentRestart()
            host.view()?.onNewPlayStarted(sameContentRestart)
            host.view()?.clearArtwork()
        }
        val vs = host.currentSeries(vod.playFlag, vod.playIndex)
        if (vs == null) {
            host.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_get_info_error))
            return
        }
        if (!prepareOnly) {
            val startedKey = host.startedProgressKey()
            val sameContentReuse = startedKey != null
                && !KernelReusePolicy.isCrossContentSwitch(startedKey, host.progressKey())
            if (sameContentReuse) {
                host.view()?.showTip("", true, false)
            } else {
                host.view()?.showTip(PlaybackController.str(R.string.player_getting_info), true, false)
            }
            host.publishTitle()
        }

        host.stopParse()
        host.beginNewPlay()
        host.syncDecodeFromGlobal()
        host.setWebPlayUrl(null)
        host.setWebHeaderMap(null)
        host.initParseLoadFound()

        if (!prepareOnly) {
            host.view()?.stopOtherPlayers()
            host.view()?.resetDanmu()
            host.view()?.clearLyric()
            if (reusePlayer) {
                savePreviousContentProgress()
                host.view()?.clearVideoFrame()
            } else if (kernelPresent) {
                host.view()?.releasePlayer()
            }
            ImgUtil.clearMemoryCache()
        }
        host.setSubtitleCacheKey(
            vod.sourceKey + "-" + vod.id + "-" + vod.playFlag + "-"
                + vod.playIndex + "-" + vs.name + "-subt"
        )
        host.setProgressKey(vod.sourceKey + vod.id + vod.playFlag + vod.playIndex + vs.name)
        if (!prepareOnly) {
            PlaybackProgress.onEpisodeStart(host.vod())
        }
        host.startResolvePlayUrlTimeout()
        val inherit = host.attemptState()
        if (inherit.pendingInheritProgress > 0 && !TextUtils.isEmpty(inherit.pendingInheritKey)) {
            if (!prepareOnly) host.inheritProgressFrom(inherit.pendingInheritKey, inherit.pendingInheritProgress)
            LOG.i("echo-switchSource inherit progress " + inherit.pendingInheritProgress + "ms from " + inherit.pendingInheritKey)
        }
        host.updateAttemptState { it.copy(pendingInheritKey = null, pendingInheritProgress = 0) }
        if (reset && !prepareOnly) {
            host.clearInheritProgress()
            WatchProgressStore.clear(host.progressOwner(), host.progressKey())
            val subtitleCacheKey = MD5.string2MD5(host.subtitleCacheKey())
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                AppGraph.cacheRepository.delete(subtitleCacheKey, 0)
            }
        } else if (!prepareOnly) {
            host.migrateProgressOnLineSwitch()
            host.inheritProgressIfNeeded()
            host.view()?.setSubtitleViewVisible(false)
        }

        val seriesUrl = requireNotNull(vs.url)
        val thunderGen = host.resolverCurrentGen()
        val magnetPending = Thunder.isMagnetUrl(seriesUrl)
        if (Thunder.play(seriesUrl, object : Thunder.ThunderCallback {
                override fun status(code: Int, info: String) {
                    host.view()?.showTip(info, code >= 0, code < 0)
                }

                override fun list(urlMap: MutableMap<Int, String>) {
                }

                override fun play(url: String) {
                    playUrl(thunderGen, url, null)
                }
            })
        ) {
            host.view()?.showParse(false)
            if (magnetPending && !prepareOnly) {
                host.cancelPlayTimeout()
                host.view()?.showTip(PlaybackController.str(R.string.thunder_parsing), true, false)
            }
            return
        }

        if (host.preloadConsumeResult(host.progressKey())) return
        val svm = host.sourceViewModel()
        if (svm != null) {
            svm.getPlay(host.sourceKey(), vod.playFlag, host.progressKey(), vs.url, host.subtitleCacheKey())
        }
    }

    private fun isIdleKernelReusable(kernelPresent: Boolean): Boolean {
        if (!kernelPresent) return false
        val bridge = host.view() ?: return false
        return bridge.playState() == PlayState.IDLE
    }

    fun isCrossContentReuseAllowed(): Boolean {
        val bridge = host.view() ?: return false
        if (bridge.mediaPlayer() == null) return false
        return !bridge.isKernelErrored()
    }

    private fun savePreviousContentProgress() {
        val bridge = host.view() ?: return
        if (TextUtils.isEmpty(host.progressKey())) return
        val position = bridge.currentPosition()
        if (position <= 0) return
        WatchProgressStore.save(host.progressOwner(), host.progressKey(), position, bridge.duration())
    }

    fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?) {
        if (!host.resolverIsParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale parse result")
            return
        }
        playUrlGeneration = gen
        playUrl(url, headers)
    }

    fun playUrl(url: String, headers: HashMap<String, String>?) {
        host.syncDecodeFromGlobal()
        val target = attachProxySiteKey(url)
        host.updateAttemptState { it.copy(localProxySession = SourcePolicy.isLocalProxyUrl(target)) }
        SlowOpenSignal.reset()
        host.startSwitchLinePlayTimeout(MediaSources.isoHeaders(headers))
        if (!KV.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(target, headers)
            return
        }
        if (target.startsWith("http://127.0.0.1") || !target.contains(".m3u8")) {
            goPlayUrl(target, headers)
            return
        }
        val vod = host.vod()
        if (vod != null && DefaultConfig.noAd(vod.playFlag)) {
            goPlayUrl(target, headers)
            return
        }
        LOG.i("echo-playM3u8:" + target)
        host.view()?.playM3u8(target, headers, playUrlGeneration)
        host.setWebPlayUrl(target)
    }

    fun goPlayUrl(url: String, headers: HashMap<String, String>?) {
        host.updateAttemptState { it.copy(isoSession = MediaSources.isoHeaders(headers)) }
        if (host.attemptState().castAborted) {
            LOG.i("echo-cast abort: drop late play url")
            host.cancelPlayTimeout()
            return
        }
        LOG.i("echo-goPlayUrl:" + url)
        if (TextUtils.isEmpty(url)) {
            host.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_play_url_empty))
            return
        }
        val bridge = host.view()
        if (bridge == null || !bridge.isPageAlive()) return
        playUrlGeneration = host.resolverCurrentGen()
        val finalUrl = url
        scope.launch {
            val live = host.view()
            if (live == null || !live.isPageAlive()) return@launch
            if (host.attemptState().switchStopPending) {
                LOG.i("echo-ignore goPlayUrl while source switching")
                return@launch
            }
            if (host.attemptState().castAborted) {
                LOG.i("echo-cast abort: drop late play url")
                host.cancelPlayTimeout()
                return@launch
            }
            if (playUrlGeneration != host.resolverCurrentGen()) {
                LOG.i("echo-ignore goPlayUrl of stale parse result")
                host.resolverCancelParseTimeout()
                return@launch
            }
            host.setWebPlayUrl(finalUrl)
            host.stopParse()
            if (host.attemptState().castPrepareOnly) {
                host.closeCastPrepare()
                host.cancelPlayTimeout()
                LOG.i("echo-cast prepare: url resolved, keep playback off")
                return@launch
            }
            if (host.view() == null) return@launch
            var targetUrl = finalUrl
            try {
                val playerType = requireNotNull(host.playerCfg()).getInt("pl")
                if (playerType >= 10 && PlayerHelper.getPlayerExist(playerType)) {
                    host.view()?.releasePlayer()
                    val vod = host.vod()
                    val seriesMap = vod?.seriesMap
                    val series = if (vod == null || seriesMap == null) {
                        null
                    } else {
                        seriesMap[vod.playFlag]
                    }
                    val vs = if (vod == null || series == null || vod.playIndex < 0 || vod.playIndex >= series.size) {
                        null
                    } else {
                        series[vod.playIndex]
                    }
                    val playTitle = requireNotNull(vod).name + if (vs == null) "" else " " + vs.name
                    host.view()?.showTip(PlaybackController.str(R.string.player_call_external_play, PlayerHelper.getPlayerName(playerType)), true, false)
                    val progress = host.getSavedProgress(host.progressKey())
                    val callResult = host.view()?.playExternalPlayer(
                        playerType, targetUrl, playTitle, host.playSubtitle(), headers, progress
                    ) ?: false
                    host.view()?.showTip(
                        PlaybackController.str(
                            R.string.player_call_external_result,
                            PlayerHelper.getPlayerName(playerType),
                            if (callResult) PlaybackController.str(R.string.common_success) else PlaybackController.str(R.string.common_failed)
                        ),
                        callResult,
                        !callResult
                    )
                    return@launch
                }
            } catch (e: JSONException) {
                LOG.e("PlaybackController", e)
            }
            host.setPlayTimeoutBasePosition(host.getSavedProgress(host.progressKey()))
            val forceExoPlayer = targetUrl.startsWith("data:application/dash+xml;base64,")
                || targetUrl.contains(".mpd") || targetUrl.contains("type=mpd")
            if (targetUrl.startsWith("data:application/dash+xml;base64,")) {
                host.view()?.applyPlayerConfigToView(2)
                requireNotNull(App.getInstance()) { "app instance not ready" }.setDashData(targetUrl.split("base64,")[1])
                targetUrl = ControlManager.get().getAddress(true) + "dash/proxy.mpd"
            } else if (targetUrl.contains(".mpd") || targetUrl.contains("type=mpd")) {
                host.view()?.applyPlayerConfigToView(2)
            } else {
                host.view()?.applyPlayerConfigToView(0)
            }
            if (PlaybackController.looksLikeAudioUrl(targetUrl)) {
                host.view()?.useTextureRenderForAudio()
            }
            host.view()?.startVideoPlayback(targetUrl, headers, forceExoPlayer)
        }
    }

    private fun attachProxySiteKey(url: String): String {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(host.sourceKey())) return url
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url
        if (url.contains("siteKey=")) return url
        return try {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + URLEncoder.encode(host.sourceKey(), "UTF-8")
        } catch (th: Throwable) {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + host.sourceKey()
        }
    }
}
