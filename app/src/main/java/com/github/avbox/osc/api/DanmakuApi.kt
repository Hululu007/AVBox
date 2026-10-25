package com.github.avbox.osc.api

import android.text.TextUtils

import androidx.collection.ArrayMap

import com.github.catvod.crawler.js.Trans
import com.github.catvod.net.OkHttp
import com.github.avbox.osc.R
import com.github.avbox.osc.base.App
import com.github.avbox.osc.bean.DanmuSearchResult
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.DanmuSettingsState
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.RegexUtils

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

class DanmakuApi {

    class DanmakuException(message: String) : Exception(message)

    private class EpisodeList(@JvmField val episodes: JSONArray?, @JvmField val isMovie: Boolean)

    private class EpisodeMatch(@JvmField val id: String, @JvmField val title: String, @JvmField val number: Int)

    companion object {

        private fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        private val TAG = DanmakuApi::class.java.simpleName
        private const val BUILTIN_API = "https://logvardanmu.konfan.cn/87654321"
        private val BUILTIN_TIMEOUT = TimeUnit.SECONDS.toMillis(20)
        private const val BUILTIN_MAX_RETRY = 2
        private const val EPISODE_QUERY_NUMBER = 0
        private const val EPISODE_QUERY_EMPTY = 1
        private val searchSeq = AtomicInteger()

        @JvmStatic
        fun canSearch(sourceBean: SourceBean?): Boolean {
            if (sourceBean != null && !sourceBean.isDanmakuEnabled()) return false
            return DanmuSettingsState.config.open && !TextUtils.isEmpty(getApiUrl())
        }

        @JvmStatic
        fun isUseDefault(): Boolean {
            return KV.get(KVKeys.DANMU_API_USE_DEFAULT, false)
        }

        @JvmStatic
        suspend fun search(name: String?, episode: String?): String? {
            val apiUrl = getApiUrl()
            if (TextUtils.isEmpty(apiUrl)) return null
            val seq = nextSearch()
            return withContext(Dispatchers.IO) {
                try {
                    if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                        searchBuiltin(apiUrl, name, episode, seq)
                    } else {
                        searchRemote(apiUrl, name, episode, seq)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (th: Throwable) {
                    ensureCurrent(seq)
                    AppLog.e("echo-danmaku search start error: " + th.message)
                    null
                }
            }
        }

        @JvmStatic
        suspend fun searchList(name: String?, episode: String?): List<DanmuSearchResult> {
            val seq = nextSearch()
            return withContext(Dispatchers.IO) {
                val apiUrl = getApiUrl()
                if (TextUtils.isEmpty(apiUrl)) throw DanmakuException(str(R.string.danmu_search_api_empty))
                try {
                    if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                        searchBuiltinList(apiUrl, name, seq)
                    } else {
                        parseSearchList(requestText(newCall(apiUrl, name, episode), seq, true))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (th: Throwable) {
                    ensureCurrent(seq)
                    throw DanmakuException(errorMessage(th))
                }
            }
        }

        @JvmStatic
        suspend fun loadSearchResult(result: DanmuSearchResult?): String {
            val seq = nextSearch()
            if (result == null || TextUtils.isEmpty(result.url)) throw DanmakuException(str(R.string.danmu_url_empty))
            if (!result.isBuiltIn) return result.url
            return withContext(Dispatchers.IO) {
                val body = try {
                    requestText(OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), result.url, TAG), seq, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (th: Throwable) {
                    ensureCurrent(seq)
                    throw DanmakuException(errorMessage(th))
                }
                val danmu = try {
                    commentJsonToXml(body)
                } catch (th: Throwable) {
                    ensureCurrent(seq)
                    throw DanmakuException(errorMessage(th))
                }
                if (TextUtils.isEmpty(danmu)) throw DanmakuException(str(R.string.danmu_content_empty))
                danmu
            }
        }

        private suspend fun searchRemote(apiUrl: String, name: String?, episode: String?, seq: Int): String? {
            val call = newCall(apiUrl, name, episode)
            val body = try {
                requestText(call, seq)
            } catch (e: IOException) {
                ensureCurrent(seq)
                AppLog.e("echo-danmaku search error: " + e.message)
                return null
            }
            return try {
                parseUrl(body).ifEmpty { null }
            } catch (th: Throwable) {
                ensureCurrent(seq)
                AppLog.e("echo-danmaku search parse error: " + th.message)
                null
            }
        }

        @JvmStatic
        fun cancel() {
            searchSeq.incrementAndGet()
            OkHttp.cancel(TAG)
        }

        private suspend fun searchBuiltinList(apiUrl: String, name: String?, seq: Int): List<DanmuSearchResult> {
            val baseUrl = normalizeBaseUrl(apiUrl)
            val searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(Trans.t2s(name ?: ""))
            val body = requestText(OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG), seq, true)
            return parseBuiltinSearchList(body, baseUrl)
        }

        private suspend fun searchBuiltin(apiUrl: String, name: String?, episode: String?, seq: Int): String? {
            return searchBuiltin(apiUrl, name, episode, seq, EPISODE_QUERY_NUMBER)
        }

        private suspend fun searchBuiltin(apiUrl: String, name: String?, episode: String?, seq: Int, queryMode: Int): String? {
            val baseUrl = normalizeBaseUrl(apiUrl)
            val simpleName = Trans.t2s(name ?: "") ?: ""
            val simpleEpisode = Trans.t2s(episode ?: "") ?: ""
            val episodeQuery = getEpisodeQuery(simpleEpisode, queryMode)
            val searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(simpleName) +
                    (if (TextUtils.isEmpty(episodeQuery)) "" else "&episode=" + encode(episodeQuery))
            val body = try {
                builtinSearchText(searchUrl, seq)
            } catch (e: IOException) {
                AppLog.e("echo-danmaku builtin search error: " + e.message)
                return searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, seq)
            }
            val episodeMatch = try {
                findEpisodeFromSearchEpisodes(body, simpleEpisode)
            } catch (th: Throwable) {
                AppLog.e("echo-danmaku builtin episode parse error: " + th.message)
                val nextMode = nextEpisodeQueryMode(simpleEpisode, queryMode)
                return if (nextMode >= 0) searchBuiltin(apiUrl, name, episode, seq, nextMode)
                else searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, seq)
            }
            if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                return loadBuiltinComment(baseUrl, simpleName, simpleEpisode, episodeMatch, seq)
            }
            if (isSearchEpisodesMovieResult(body)) {
                AppLog.i("echo-danmaku builtin movie result not matched, skip anime fallback")
                return null
            }
            val nextMode = nextEpisodeQueryMode(simpleEpisode, queryMode)
            if (nextMode >= 0) return searchBuiltin(apiUrl, name, episode, seq, nextMode)
            AppLog.i("echo-danmaku builtin episode not matched, title: " + safeLog(simpleName) + ", episode: " + safeLog(simpleEpisode))
            return searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, seq)
        }

        private suspend fun builtinSearchText(url: String, seq: Int): String {
            var attempt = 0
            while (true) {
                try {
                    return requestText(OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), url, TAG), seq)
                } catch (e: IOException) {
                    ensureCurrent(seq)
                    if (attempt >= BUILTIN_MAX_RETRY) throw e
                    AppLog.e("echo-danmaku builtin search error: " + e.message + ", retry later")
                    delay(1500L * (attempt + 1))
                    attempt += 1
                }
            }
        }

        private suspend fun searchBuiltinAnime(baseUrl: String, name: String, episode: String, seq: Int): String? {
            val searchUrl = baseUrl + "/api/v2/search/anime?keyword=" + encode(name)
            AppLog.i("echo-danmaku builtin search anime: " + searchUrl)
            val body = try {
                requestText(OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG), seq)
            } catch (e: IOException) {
                AppLog.e("echo-danmaku builtin anime error: " + e.message)
                return null
            }
            val animeId = try {
                findAnimeId(body)
            } catch (th: Throwable) {
                AppLog.e("echo-danmaku builtin anime parse error: " + th.message)
                return null
            }
            if (TextUtils.isEmpty(animeId)) return null
            return loadBuiltinBangumi(baseUrl, animeId, episode, seq)
        }

        private suspend fun loadBuiltinBangumi(baseUrl: String, animeId: String, episode: String, seq: Int): String? {
            val bangumiUrl = baseUrl + "/api/v2/bangumi/" + animeId
            val body = try {
                requestText(OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), bangumiUrl, TAG), seq)
            } catch (e: IOException) {
                AppLog.e("echo-danmaku builtin bangumi error: " + e.message)
                return null
            }
            val episodeMatch = try {
                findEpisode(body, episode)
            } catch (th: Throwable) {
                AppLog.e("echo-danmaku builtin bangumi parse error: " + th.message)
                return null
            }
            if (episodeMatch == null || TextUtils.isEmpty(episodeMatch.id)) return null
            return loadBuiltinComment(baseUrl, "", episode, episodeMatch, seq)
        }

        private suspend fun loadBuiltinComment(baseUrl: String, title: String, episode: String, episodeMatch: EpisodeMatch, seq: Int): String? {
            val commentUrl = baseUrl + "/api/v2/comment/" + episodeMatch.id + "?format=json"
            AppLog.i("echo-danmaku builtin load title: " + safeLog(title) +
                    ", request episode: " + safeLog(episode) +
                    ", matched episode: " + safeLog(episodeMatch.title) +
                    ", matched number: " + episodeMatch.number +
                    ", episodeId: " + episodeMatch.id)
            AppLog.i("echo-danmaku builtin comment: " + commentUrl)
            val body = try {
                requestText(OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), commentUrl, TAG), seq)
            } catch (e: IOException) {
                AppLog.e("echo-danmaku builtin comment error: " + e.message)
                return null
            }
            val xml = try {
                commentJsonToXml(body)
            } catch (th: Throwable) {
                AppLog.e("echo-danmaku builtin comment parse error: " + th.message)
                return null
            }
            return xml.ifEmpty { null }
        }

        private fun nextSearch(): Int {
            val seq = searchSeq.incrementAndGet()
            OkHttp.cancel(TAG)
            return seq
        }

        private fun ensureCurrent(seq: Int) {
            if (seq != searchSeq.get()) throw CancellationException("danmaku search superseded")
        }

        private suspend fun requestText(call: Call, seq: Int, checkStatus: Boolean = false): String {
            val response = awaitResponse(call, seq)
            response.use {
                ensureCurrent(seq)
                if (checkStatus && !it.isSuccessful) throw IOException("HTTP " + it.code)
                return it.body.string()
            }
        }

        private suspend fun awaitResponse(call: Call, seq: Int): Response {
            ensureCurrent(seq)
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!cont.isActive) {
                            response.close()
                            return
                        }
                        cont.resume(response)
                    }
                })
            }
        }

        private fun newCall(apiUrl: String, name: String?, episode: String?): Call {
            var name = Trans.t2s(name ?: "") ?: ""
            var episode = Trans.t2s(episode ?: "") ?: ""
            if (hasPlaceholder(apiUrl)) {
                return OkHttp.newCall(fillPlaceholders(apiUrl, name, episode), TAG)
            }
            val params = ArrayMap<String, String>()
            params["name"] = name
            params["episode"] = episode
            return OkHttp.newCall(apiUrl, OkHttp.toBody(params), TAG)
        }

        @JvmStatic
        fun fillPlaceholders(apiUrl: String, name: String, episode: String): String {
            return apiUrl.replace("{name}", encodePlaceholder(name)).replace("{episode}", encodePlaceholder(episode))
        }

        private fun encodePlaceholder(text: String): String {
            return encode(text).replace("+", "%20")
        }

        private fun getApiUrl(): String {
            if (isUseDefault()) return BUILTIN_API
            val custom = KV.get(KVKeys.DANMU_API, "")
            if (!TextUtils.isEmpty(custom)) return custom.trim { it <= ' ' }
            val config = ApiConfig.get().getDanmaku().trim { it <= ' ' }
            if (!TextUtils.isEmpty(config)) return config
            return BUILTIN_API
        }

        private fun hasPlaceholder(apiUrl: String?): Boolean {
            val text = apiUrl ?: return false
            return text.contains("{name}") || text.contains("{episode}")
        }

        private fun isDanmakuSearchApi(apiUrl: String?): Boolean {
            var url = apiUrl?.trim { it <= ' ' } ?: ""
            while (url.endsWith("/")) url = url.substring(0, url.length - 1)
            return url.endsWith("/danmaku")
        }

        private fun normalizeBaseUrl(apiUrl: String?): String {
            var url = apiUrl?.trim { it <= ' ' } ?: ""
            if (url.endsWith("/")) url = url.substring(0, url.length - 1)
            if (url.endsWith("/87654321")) url = url.substring(0, url.length - "/87654321".length)
            return url
        }

        private fun encode(text: String?): String {
            try {
                return URLEncoder.encode(text ?: "", "UTF-8")
            } catch (th: Throwable) {
                return text ?: ""
            }
        }

        private fun getEpisodeQuery(episode: String, queryMode: Int): String {
            if (queryMode == EPISODE_QUERY_EMPTY) return ""
            val number = extractNumber(episode)
            return if (number > 0) number.toString() else ""
        }

        private fun nextEpisodeQueryMode(episode: String, queryMode: Int): Int {
            if (queryMode == EPISODE_QUERY_NUMBER && !TextUtils.isEmpty(getEpisodeQuery(episode, EPISODE_QUERY_NUMBER))) {
                return EPISODE_QUERY_EMPTY
            }
            return -1
        }

        private fun findAnimeId(body: String): String {
            val obj = JSONObject(body)
            var array = obj.optJSONArray("animes")
            if (array == null) array = obj.optJSONArray("anime")
            if (array == null) array = obj.optJSONArray("data")
            if (array == null || array.length() <= 0) return ""
            val item = array.optJSONObject(0) ?: return ""
            var id = item.optString("animeId", "")
            if (TextUtils.isEmpty(id)) id = item.optString("id", "")
            return id
        }

        private fun findEpisode(body: String, episode: String?): EpisodeMatch? {
            return findEpisode(body, episode, true)
        }

        private fun findEpisode(body: String, episode: String?, allowMovieFallback: Boolean): EpisodeMatch? {
            val obj = JSONObject(body)
            val episodeList = findEpisodeList(obj) ?: return null
            val episodes = episodeList.episodes
            if (episodes == null || episodes.length() <= 0) return null
            val targetNumber = extractNumber(episode)
            var first: EpisodeMatch? = null
            var firstMandarin: EpisodeMatch? = null
            for (i in 0 until episodes.length()) {
                val item = episodes.optJSONObject(i) ?: continue
                val id = firstString(item, "episodeId", "id")
                if (TextUtils.isEmpty(id)) continue
                val title = firstString(item, "episodeTitle", "title", "name")
                val number = parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort"))
                val match = EpisodeMatch(id, title, number)
                if (first == null) first = match
                if (firstMandarin == null && isMandarinTitle(title)) firstMandarin = match
                if (!episode.isNullOrEmpty() && !title.isNullOrEmpty() && title.contains(episode)) {
                    return match
                }
                if (targetNumber > 0 && number == targetNumber) return match
                if (targetNumber > 0 && extractNumber(title) == targetNumber) return match
            }
            if (TextUtils.isEmpty(episode)) return first
            if (allowMovieFallback && episodeList.isMovie) return firstMandarin ?: first
            return null
        }

        private fun findEpisodeFromSearchEpisodes(body: String, episode: String?): EpisodeMatch? {
            val match = findEpisode(body, episode, false)
            if (match != null) return match
            val movieFallback = findMovieFallback(JSONObject(body), episode)
            if (movieFallback != null) {
                AppLog.i("echo-danmaku episodes movie fallback episode: " + safeLog(movieFallback.title) + ", episodeId: " + movieFallback.id)
            }
            return movieFallback
        }

        private fun findEpisodeList(obj: JSONObject): EpisodeList? {
            var array = obj.optJSONArray("episodes")
            if (array != null) return EpisodeList(array, isMovieType(obj))
            val bangumi = obj.optJSONObject("bangumi")
            if (bangumi != null) {
                array = bangumi.optJSONArray("episodes")
                if (array != null) return EpisodeList(array, isMovieType(bangumi))
            }
            var animes = obj.optJSONArray("animes")
            if (animes == null) animes = obj.optJSONArray("anime")
            if (animes == null) animes = obj.optJSONArray("data")
            if (animes != null) {
                for (i in 0 until animes.length()) {
                    val item = animes.optJSONObject(i) ?: continue
                    array = item.optJSONArray("episodes")
                    if (array != null && array.length() > 0) return EpisodeList(array, isMovieType(item))
                }
            }
            return null
        }

        private fun isMovieType(obj: JSONObject?): Boolean {
            if (obj == null) return false
            if (isMovieTypeText(obj.optString("type", ""))) return true
            if (isMovieTypeText(obj.optString("typeDescription", ""))) return true
            val bangumi = obj.optJSONObject("bangumi")
            if (bangumi != null && isMovieTypeText(bangumi.optString("type", ""))) return true
            var animes = obj.optJSONArray("animes")
            if (animes == null) animes = obj.optJSONArray("anime")
            if (animes == null) animes = obj.optJSONArray("data")
            if (animes == null) return false
            for (i in 0 until animes.length()) {
                val item = animes.optJSONObject(i)
                if (item != null && isMovieTypeText(item.optString("type", ""))) return true
            }
            return false
        }

        private fun isMovieTypeText(type: String?): Boolean {
            return "\u7535\u5f71" == type
        }

        private fun isMandarinTitle(title: String?): Boolean {
            return !title.isNullOrEmpty() && (title.contains("\u56fd\u8bed") || title.contains("\u666e\u901a\u8bdd"))
        }

        private fun isCantoneseTitle(title: String?): Boolean {
            return !title.isNullOrEmpty() && title.contains("\u7ca4\u8bed")
        }

        private fun prefersCantonese(episode: String?): Boolean {
            return isCantoneseTitle(episode)
        }

        private fun isPreferredLanguageTitle(title: String?, preferCantonese: Boolean): Boolean {
            return if (preferCantonese) isCantoneseTitle(title) else isMandarinTitle(title)
        }

        private fun isSearchEpisodesMovieResult(body: String): Boolean {
            try {
                val obj = JSONObject(body)
                var animes = obj.optJSONArray("animes")
                if (animes == null) animes = obj.optJSONArray("anime")
                if (animes == null) animes = obj.optJSONArray("data")
                if (animes == null) return false
                for (i in 0 until animes.length()) {
                    val anime = animes.optJSONObject(i)
                    if (anime != null && isMovieType(anime) && anime.optJSONArray("episodes") != null) return true
                }
            } catch (ignored: Throwable) {
                AppLog.d("DanmakuApi", "search body parse failed, treat as not movie")
            }
            return false
        }

        private fun findMovieFallback(obj: JSONObject, episode: String?): EpisodeMatch? {
            var animes = obj.optJSONArray("animes")
            if (animes == null) animes = obj.optJSONArray("anime")
            if (animes == null) animes = obj.optJSONArray("data")
            if (animes == null) return null
            val preferCantonese = prefersCantonese(episode)
            var firstMovie: EpisodeMatch? = null
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i)
                if (anime == null || !isMovieType(anime)) continue
                val episodes = anime.optJSONArray("episodes") ?: continue
                for (j in 0 until episodes.length()) {
                    val item = episodes.optJSONObject(j) ?: continue
                    val id = firstString(item, "episodeId", "id")
                    if (TextUtils.isEmpty(id)) continue
                    val title = firstString(item, "episodeTitle", "title", "name")
                    val match = EpisodeMatch(id, title, parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort")))
                    if (firstMovie == null) firstMovie = match
                    if (isPreferredLanguageTitle(title, preferCantonese)) return match
                }
            }
            return firstMovie
        }

        private fun parseEpisodeNumber(value: String?): Int {
            try {
                if (value.isNullOrEmpty()) return -1
                return value.toFloat().toInt()
            } catch (th: Throwable) {
                return extractNumber(value)
            }
        }

        private fun extractNumber(text: String?): Int {
            if (text.isNullOrEmpty()) return -1
            val builder = StringBuilder()
            for (i in 0 until text.length) {
                val ch = text[i]
                if (ch.isDigit()) builder.append(ch)
            }
            if (builder.length == 0) return -1
            try {
                return builder.toString().toInt()
            } catch (th: Throwable) {
                return -1
            }
        }

        private fun commentJsonToXml(body: String): String {
            val obj = JSONObject(body)
            var comments = obj.optJSONArray("comments")
            if (comments == null) comments = obj.optJSONArray("data")
            if (comments == null || comments.length() <= 0) return ""
            val builder = StringBuilder()
            builder.append("<i>")
            for (i in 0 until comments.length()) {
                val item = comments.optJSONObject(i) ?: continue
                val param = item.optString("p", "")
                var text = item.optString("m", "")
                if (TextUtils.isEmpty(text)) text = item.optString("text", "")
                if (TextUtils.isEmpty(param) || TextUtils.isEmpty(text)) continue
                builder.append("<d p=\"").append(escapeXml(normalizeDanmakuParam(param))).append("\">")
                        .append(escapeXml(text)).append("</d>")
            }
            builder.append("</i>")
            val xml = builder.toString()
            AppLog.i("echo-danmaku builtin xml length: " + xml.length)
            return xml
        }

        private fun normalizeDanmakuParam(param: String): String {
            val values = RegexUtils.getPattern(",").split(param)
            if (values.size < 4) return param
            val time = values[0]
            val type = values[1]
            var size = values[2]
            var color = values[3]
            if (isColorValue(size)) {
                color = size
                size = "25"
            } else if (!isColorValue(color)) {
                color = "16777215"
            }
            return time + "," + type + "," + size + "," + normalizeColor(color)
        }

        private fun isColorValue(value: String?): Boolean {
            try {
                if (value.isNullOrEmpty()) return false
                val text = value.trim { it <= ' ' }
                if (text.startsWith("#")) return true
                if (text.startsWith("0x") || text.startsWith("0X")) return true
                val color = text.toLong()
                return color >= 0 && color <= 0x00ffffffL
            } catch (th: Throwable) {
                return false
            }
        }

        private fun normalizeColor(color: String?): String {
            if (color.isNullOrEmpty()) return "16777215"
            val text = color.trim { it <= ' ' }
            try {
                if (text.startsWith("#")) return text.substring(1).toLong(16).toString()
                if (text.startsWith("0x") || text.startsWith("0X")) return text.substring(2).toLong(16).toString()
            } catch (ignored: Throwable) {
                AppLog.d("DanmakuApi", "color '" + text + "' parse failed, keep raw")
            }
            return text
        }

        private fun firstString(obj: JSONObject, vararg keys: String): String {
            for (key in keys) {
                val value = obj.optString(key, "")
                if (!TextUtils.isEmpty(value)) return value
            }
            return ""
        }

        private fun safeLog(text: String?): String? {
            return if (TextUtils.isEmpty(text)) "" else text
        }

        private fun parseSearchList(body: String?): List<DanmuSearchResult> {
            val results: MutableList<DanmuSearchResult> = ArrayList()
            if (body.isNullOrEmpty()) return results
            val text = body.trim { it <= ' ' }
            if (text.startsWith("[")) {
                appendSearchResults(results, JSONArray(text))
                return results
            }
            if (!text.startsWith("{")) return results
            val obj = JSONObject(text)
            var items = obj.optJSONArray("data")
            if (items == null) items = obj.optJSONArray("list")
            if (items == null) items = obj.optJSONArray("results")
            if (items != null) {
                appendSearchResults(results, items)
                return results
            }
            val url = obj.optString("url", "").trim { it <= ' ' }
            if (!TextUtils.isEmpty(url)) {
                results.add(DanmuSearchResult(firstString(obj, "name", "title"), url, false))
            }
            return results
        }

        private fun appendSearchResults(results: MutableList<DanmuSearchResult>, items: JSONArray) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val url = item.optString("url", "").trim { it <= ' ' }
                if (TextUtils.isEmpty(url)) continue
                val name = firstString(item, "name", "title", "episode", "vod_name")
                results.add(DanmuSearchResult(name, url, false))
            }
        }

        private fun parseBuiltinSearchList(body: String, baseUrl: String): List<DanmuSearchResult> {
            val results: MutableList<DanmuSearchResult> = ArrayList()
            val obj = JSONObject(body)
            appendBuiltinResults(results, baseUrl, obj, "")
            val bangumi = obj.optJSONObject("bangumi")
            if (bangumi != null) appendBuiltinResults(results, baseUrl, bangumi, firstString(bangumi, "animeTitle", "title", "name"))
            appendBuiltinAnimeResults(results, baseUrl, obj.optJSONArray("animes"))
            appendBuiltinAnimeResults(results, baseUrl, obj.optJSONArray("anime"))
            appendBuiltinAnimeResults(results, baseUrl, obj.optJSONArray("data"))
            return results
        }

        private fun appendBuiltinAnimeResults(results: MutableList<DanmuSearchResult>, baseUrl: String, animes: JSONArray?) {
            if (animes == null) return
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                appendBuiltinResults(results, baseUrl, anime, firstString(anime, "animeTitle", "title", "name"))
                val bangumi = anime.optJSONObject("bangumi")
                if (bangumi != null) {
                    appendBuiltinResults(results, baseUrl, bangumi, firstString(anime, "animeTitle", "title", "name"))
                }
            }
        }

        private fun appendBuiltinResults(results: MutableList<DanmuSearchResult>, baseUrl: String, obj: JSONObject, animeName: String) {
            val episodes = obj.optJSONArray("episodes") ?: return
            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue
                val id = firstString(episode, "episodeId", "id")
                if (TextUtils.isEmpty(id)) continue
                val episodeName = firstString(episode, "episodeTitle", "title", "name")
                var name = if (TextUtils.isEmpty(animeName)) episodeName else animeName + " " + episodeName
                if (TextUtils.isEmpty(name)) name = id
                val url = baseUrl + "/api/v2/comment/" + id + "?format=json"
                results.add(DanmuSearchResult(name, url, true))
            }
        }

        private fun errorMessage(th: Throwable?): String {
            val message = if (th == null) "" else th.message
            return if (TextUtils.isEmpty(message)) str(R.string.danmu_search_failed) else message ?: ""
        }

        private fun escapeXml(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            return text.replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;")
                    .replace(">", "&gt;")
                    .replace("<", "&lt;")
        }

        private fun parseUrl(body: String?): String {
            if (body.isNullOrEmpty()) return ""
            val text = body.trim { it <= ' ' }
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url", "").trim { it <= ' ' }
                    if (!TextUtils.isEmpty(url)) return url
                }
            } else if (text.startsWith("{")) {
                return JSONObject(text).optString("url", "").trim { it <= ' ' }
            } else if (text.startsWith("http") || text.startsWith("file")) {
                return text
            }
            return ""
        }
    }
}
