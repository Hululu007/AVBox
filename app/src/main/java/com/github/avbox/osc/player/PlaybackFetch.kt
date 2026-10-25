package com.github.avbox.osc.player

import android.text.TextUtils
import android.util.Base64
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.api.DanmakuApi
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.engine.SourcePolicy
import com.github.avbox.osc.sourcedata.SourceViewModel
import com.github.avbox.osc.util.AppLog
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PlaybackFetch(private val controller: PlaybackController) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sourceViewModel: SourceViewModel? = null
    private var collectJob: Job? = null
    private var danmuJob: Job? = null

    fun init() {
        val vm = SourceViewModel()
        sourceViewModel = vm
        collectJob = scope.launch {
            vm.playResult.flow.collect { info -> handlePlayResult(info) }
        }
    }

    fun release() {
        collectJob?.cancel()
        collectJob = null
        danmuJob?.cancel()
        danmuJob = null
    }

    fun deliver(info: JSONObject?) {
        if (collectJob?.isActive != true) return
        handlePlayResult(info)
    }

    fun sourceViewModel(): SourceViewModel? = sourceViewModel

    fun cancelPlayRequest() {
        sourceViewModel?.cancelPlayRequest()
    }

    private fun handlePlayResult(info: JSONObject?) {
        val prepareOnly = controller.isCastPrepareOnly()
        if (info == null) {
            if (!prepareOnly) controller.publishQuality(null)
        }
        if (info != null) {
            try {
                if (controller.isStalePlayResult(info)) {
                    AppLog.i("echo-ignore stale play result")
                    return
                }
                val view = controller.viewBridge()
                if (view != null && controller.isSwitchStopPending()) {
                    AppLog.i("echo-ignore play result while source switching")
                    return
                }
                controller.cancelResolvePlayUrlTimeout()
                if (!prepareOnly) controller.publishQuality(info)
                controller.setWebPlayUrl(null)
                controller.setProgressKey(info.optString("proKey", null))
                val parse = info.optString("parse", "1") == "1"
                val jx = info.optString("jx", "0") == "1"
                controller.setPlaySubtitle(info.optString("subt", ""))
                controller.setPlayLyric(info.optString("lyric", ""))
                controller.setLyricCacheKey(info.optString("lyricKey", null))
                if (TextUtils.isEmpty(controller.lyricCacheKey()) && !TextUtils.isEmpty(controller.progressKey())) {
                    controller.setLyricCacheKey(controller.progressKey() + "-lyric")
                }
                val lyrics = info.optJSONArray("lyrics")
                if (lyrics != null && lyrics.length() > 0) {
                    controller.setPlayLyric(getSubtitleUrl(lyrics.optJSONObject(0)))
                }
                val subtitles = info.optJSONArray("subs")
                if (subtitles != null) {
                    for (i in 0 until subtitles.length()) {
                        val obj = subtitles.optJSONObject(i) ?: continue
                        val url = getSubtitleUrl(obj)
                        val name = obj.optString("name", "")
                        if (isLyricSubtitle(name)) {
                            if (TextUtils.isEmpty(controller.playLyric())) controller.setPlayLyric(url)
                        } else if (TextUtils.isEmpty(controller.playSubtitle())) {
                            controller.setPlaySubtitle(url)
                        }
                    }
                }
                controller.setSubtitleCacheKey(info.optString("subtKey", null))
                val lyricPick = controller.playLyric()
                AppLog.i(
                    "echo-lyric pick: " + if (lyricPick.isNullOrEmpty()) {
                        "none"
                    } else if (lyricPick.startsWith("data:")) {
                        "inline len=" + lyricPick.length
                    } else {
                        lyricPick
                    }
                )
                val playUrl = info.optString("playUrl", "")
                val flag = info.optString("flag")
                val rawUrl = info.opt("url")
                var url = if (rawUrl is JSONArray) rawUrl.toString() else rawUrl?.toString() ?: "null"
                if (url.startsWith("[") && view != null) {
                    url = view.firstUrlByArray(url)
                }
                var artwork = info.optString("artwork", "")
                if (TextUtils.isEmpty(artwork)) artwork = info.optString("cover", "")
                val vod = controller.vod()
                if (TextUtils.isEmpty(artwork) && !TextUtils.isEmpty(controller.playLyric()) && vod != null) {
                    artwork = vod.pic ?: ""
                }
                controller.setCurrentArtwork(artwork)
                if (view != null) view.setArtwork(artwork)
                val msg = info.optString("msg", "")
                if (!TextUtils.isEmpty(msg)) {
                    controller.handleResolvePlayUrlFailed(msg)
                    return
                }
                val iso = MediaSources.isIsoFormat(info.optString("format", "")) || MediaSources.isIsoUri(url)
                if (iso) AppLog.i("echo-iso-play: format=" + info.optString("format", ""))
                controller.setUserPickedLine(false)
                val danmaku = info.optString("danmaku", "").trim { it <= ' ' }
                val danmuProgressKey = controller.progressKey()
                controller.setWebUserAgent(null)
                controller.setWebHeaderMap(null)
                val extracted = PlaybackController.extractHeaders(info)
                val headers = if (iso) MediaSources.withIsoHeader(extracted) else extracted
                if (headers != null) {
                    controller.setWebHeaderMap(headers)
                    val ua = PlaybackController.headerValue(headers, "user-agent")
                    controller.setWebUserAgent(if (ua == null) null else ua.trim { it <= ' ' })
                }
                if (parse || jx) {
                    val userJxList = (playUrl.isEmpty() && (ApiConfig.get().vipParseFlags ?: mutableListOf()).contains(flag)) || jx
                    controller.initParse(flag, userJxList, playUrl, url)
                } else {
                    if (view != null) view.showParse(false)
                    if (view != null) controller.playUrl(playUrl + url, headers)
                }
                if (TextUtils.isEmpty(danmaku)) {
                    checkDanmu("", null)
                    searchDanmu("")
                } else {
                    checkDanmu(danmaku) {
                        if (TextUtils.equals(danmuProgressKey, controller.progressKey())) {
                            searchDanmu("")
                        }
                    }
                }
            } catch (th: Throwable) {
                controller.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_get_info_error))
            }
        } else {
            controller.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_get_info_error))
        }
    }

    private fun getSubtitleUrl(obj: JSONObject?): String {
        if (obj == null) return ""
        val format = obj.optString("format", "")
        val name = obj.optString("name", PlaybackController.str(R.string.player_menu_subtitle))
        var ext = ".srt"
        if ("text/x-ssa" == format) {
            ext = ".ass"
        } else if ("text/vtt" == format) {
            ext = ".vtt"
        } else if ("text/lrc" == format) {
            ext = ".lrc"
        }
        val filename = name + if (name.lowercase(Locale.ROOT).endsWith(ext)) "" else ext
        var url = obj.optString("url", "")
        val data = obj.optString("data", "")
        if (!TextUtils.isEmpty(data) && (TextUtils.isEmpty(url) || SourcePolicy.isLocalProxyUrl(url))) {
            url = "data:text/plain;base64," + Base64.encodeToString(data.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            val view = controller.viewBridge()
            return if (view == null) url else "$url#" + view.encodeUrl(filename)
        }
        if (TextUtils.isEmpty(url) || FileUtils.hasExtension(url)) return url
        val view = controller.viewBridge()
        return if (view == null) url else "$url#" + view.encodeUrl(filename)
    }

    private fun isLyricSubtitle(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val value = name.lowercase(Locale.ROOT)
        return value.contains("lyric") || value.contains("lrc") || name.contains("歌词") // i18n: keep
    }

    private fun searchDanmu(danmaku: String) {
        if (!TextUtils.isEmpty(danmaku) || !DanmakuApi.canSearch(controller.sourceBean())) return
        val vod = controller.vod() ?: return
        val series = controller.currentSeries(vod.playFlag, vod.playIndex)
        val key = controller.progressKey()
        danmuJob?.cancel()
        danmuJob = scope.launch {
            val url = DanmakuApi.search(vod.name, if (series == null) "" else series.name)
            if (!TextUtils.equals(key, controller.progressKey())) return@launch
            checkDanmu(url ?: "", null)
        }
    }

    private fun checkDanmu(danmaku: String, onFailed: Runnable?) {
        controller.viewBridge()?.checkDanmu(danmaku, onFailed)
    }
}
