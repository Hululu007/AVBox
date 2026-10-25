package com.github.avbox.osc.sourcedata

import android.text.TextUtils

import com.github.catvod.net.OkHttp
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.net.Http
import com.github.avbox.osc.util.net.HttpRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.json.JSONObject

import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SourceHelper {

    @JvmField
    val SPIDER_POOL: ExecutorService = Executors.newFixedThreadPool(3)

    @JvmField
    val PREPARE_POOL: ExecutorService = Executors.newFixedThreadPool(3)

    suspend fun siteGet(sourceBean: SourceBean, init: HttpRequest.() -> Unit = {}): String {
        return Http.get(requireNotNull(sourceBean.api) { "source api missing" }) {
            for ((key, value) in sourceBean.header.orEmpty()) {
                headers(key, value)
            }
            init()
        }
    }

    @JvmStatic
    fun isDoubanSource(sourceBean: SourceBean?): Boolean {
        if (sourceBean == null) return false
        return containsDouban(sourceBean.key) ||
            containsDouban(sourceBean.name) ||
            containsDouban(sourceBean.api) ||
            containsDouban(sourceBean.ext)
    }

    private fun containsDouban(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val lower = value.lowercase(Locale.getDefault())
        return lower.contains("douban") || value.contains("豆瓣")
    }

    @JvmStatic
    fun isHomeSource(sourceKey: String?): Boolean {
        return !TextUtils.isEmpty(sourceKey) && sourceKey == ApiConfig.get().homeSourceBean().key
    }

    @JvmStatic
    fun absXml(data: AbsXml, sourceKey: String?) {
        absXml(data, sourceKey, "")
    }

    @JvmStatic
    fun absXml(data: AbsXml, sourceKey: String?, searchToken: String?) {
        data.sourceKey = sourceKey
        data.searchToken = searchToken
        val videoList = data.movie?.videoList
        if (videoList != null) {
            for (video in videoList) {
                val infoList = video.urlBean?.infoList
                if (infoList != null) {
                    for (urlInfo in infoList) {
                        val urls = urlInfo.urls ?: continue
                        val str: Array<String> = if (urls.contains("#")) RegexUtils.getPattern("#").split(urls) else arrayOf(urls)
                        val infoBeanList = ArrayList<Movie.Video.UrlBean.UrlInfo.InfoBean>()
                        for (s in str) {
                            val ss = s.split(Regex("\\$"), 2)
                            if (ss.isNotEmpty()) {
                                if (ss.size >= 2) {
                                    infoBeanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]))
                                } else {
                                    infoBeanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size + 1).toString(), ss[0]))
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList
                    }
                }
                video.sourceKey = sourceKey
            }
        }
    }

    @JvmStatic
    fun getFixUrl(extendCache: ConcurrentHashMap<String, String>, gson: Gson, extend: String?, timeoutSeconds: Long): String? {
        if (extend.isNullOrEmpty()) return ""
        val url = extend
        if (!url.startsWith("http")) return url
        val key = requireNotNull(MD5.string2MD5(url)) { "md5 of extend url failed" }
        if (extendCache.containsKey(key)) {
            AppLog.i("echo-getFixUrl Cache")
            return extendCache[key]
        }
        val future: Future<String> = SPIDER_POOL.submit(Callable<String> {
            var result: String? = url
            if (url.startsWith("http://127.0.0.1")) {
                var path = url.replace(Regex("^http.+/file/"), FileUtils.rootPath() + "/")
                path = path.replace(Regex("localhost/"), "/")
                result = FileUtils.readFileToString(path, "UTF-8")
                val raw = requireNotNull(result) { "extend content read failed" }
                result = tryMinifyJson(gson, raw)
                extendCache.putIfAbsent(key, result)
            } else if (url.startsWith("http")) {
                result = OkHttp.string(url, null)
                if (!result.isNullOrEmpty()) {
                    result = tryMinifyJson(gson, result)
                    if (result.length > 2500) result = url
                    extendCache.putIfAbsent(key, result)
                }
            }
            result
        })

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (te: TimeoutException) {
            AppLog.e("SourceViewModel", te)
            future.cancel(true)
            return extend
        } catch (e: Exception) {
            AppLog.e("SourceViewModel", e)
            return extend
        }
    }

    @JvmStatic
    fun fixUrlDirect(extendCache: ConcurrentHashMap<String, String>, gson: Gson, extend: String?): String? {
        if (extend.isNullOrEmpty()) return ""
        val url = extend
        if (!url.startsWith("http")) return url
        val key = requireNotNull(MD5.string2MD5(url)) { "md5 of extend url failed" }
        if (extendCache.containsKey(key)) {
            return extendCache[key]
        }
        var result: String? = url
        try {
            if (url.startsWith("http://127.0.0.1")) {
                var path = url.replace(Regex("^http.+/file/"), FileUtils.rootPath() + "/")
                path = path.replace(Regex("localhost/"), "/")
                result = FileUtils.readFileToString(path, "UTF-8")
                val raw = requireNotNull(result) { "extend content read failed" }
                result = tryMinifyJson(gson, raw)
                extendCache.putIfAbsent(key, result)
            } else {
                result = OkHttp.string(url, null)
                if (!result.isNullOrEmpty()) {
                    val raw = requireNotNull(result) { "extend content read failed" }
                    result = tryMinifyJson(gson, raw)
                    if (result.length > 2500) result = url
                    extendCache.putIfAbsent(key, result)
                }
            }
        } catch (th: Throwable) {
            AppLog.e("SourceViewModel", th)
            return extend
        }
        return result
    }

    @JvmStatic
    fun extractPlayHeaders(playResult: JSONObject?): HashMap<String, String>? {
        if (playResult == null) return null
        val headers = HashMap<String, String>()
        appendJsonHeaders(headers, playResult.opt("header"))
        appendJsonHeaders(headers, playResult.opt("headers"))
        return if (headers.isEmpty()) null else headers
    }

    @JvmStatic
    fun appendJsonHeaders(headers: HashMap<String, String>?, rawHeaders: Any?) {
        if (headers == null || rawHeaders == null || rawHeaders === JSONObject.NULL) return
        try {
            var json: JSONObject? = null
            if (rawHeaders is JSONObject) {
                json = rawHeaders
            } else if (rawHeaders is String) {
                val text = (rawHeaders as String).trim { it <= ' ' }
                if (!TextUtils.isEmpty(text)) {
                    json = JSONObject(text)
                }
            }
            if (json == null) return
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!TextUtils.isEmpty(key)) {
                    headers[key] = json.optString(key, "")
                }
            }
        } catch (th: Throwable) {
            AppLog.e("SourceHelper", "play headers parse failed", th)
        }
    }

    private fun tryMinifyJson(gson: Gson, raw: String): String {
        var text = raw
        try {
            text = text.trim { it <= ' ' }
            val jsonElement = JsonParser.parseString(text)
            return gson.toJson(jsonElement)
        } catch (e: Exception) {
            return text
        }
    }
}
