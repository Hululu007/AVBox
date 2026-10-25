package com.github.avbox.osc.net

import com.github.avbox.osc.net.SuperParse
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LocalAddress

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object StreamProxy {
    private val URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"")

    @JvmStatic
    fun proxy(params: Map<String, String>): Array<Any?>? {
        try {
            val what = params["go"]
            assert(what != null)
            if (what == "live") {
                return itv(params)
            } else if (what == "bom") {
                return removeBOMFromM3U8(params)
            } else if (what == "ad") {
                return null
            } else if (what == "SuperParse") {
                return SuperParse.loadHtml(params["flag"], params["url"])
            }

        } catch (th: Throwable) {
            AppLog.e("Proxy", "proxy request failed", th)
        }
        return null
    }

    @JvmStatic
    @Throws(Exception::class)
    fun itv(params: Map<String, String>): Array<Any?>? {
        try {
            val result = arrayOfNulls<Any>(4)
            var url = params["url"]
            val type = params["type"]
            url = URLDecoder.decode(url, "UTF-8")

            val client = requireNotNull(HttpClients.itvClient()) { "itv client not initialized" }
            assert(type != null)
            if (type == "m3u8") {
                val request = buildRequest(url, params)
                executeRequest(client, request).use { response ->
                    if (response.isSuccessful) {
                        assert(response.body != null)
                        val respContent = response.body.string()
                        val finalUrl = response.request.url.toString()
                        val m3u8Content = processM3u8Content(respContent, finalUrl, params)
                        result[0] = 200
                        result[1] = "application/vnd.apple.mpegurl"
                        result[2] = ByteArrayInputStream(m3u8Content.toByteArray(Charsets.UTF_8))
                    } else {
                        throw IOException("M3U8 Request failed with code: " + response.code)
                    }
                }
            } else if (type == "ts" || type == "media" || type == "key") {
                val request = buildRequest(url, params)
                val response = executeRequest(client, request)
                if (response.isSuccessful) {
                    assert(response.body != null)
                    result[0] = response.code
                    result[1] = getMime(type, url, response)
                    result[2] = ResponseInputStream(response)
                    result[3] = responseHeaders(response)
                } else {
                    val code = response.code
                    response.close()
                    throw IOException("Media Request failed with code: " + code)
                }
            } else {
                throw IllegalArgumentException("Invalid type: " + type)
            }
            return result
        } catch (e: Exception) {
            AppLog.e("echo-proxy error: itv request failed | type=" + params["type"] + " | " + e.message)
            return null
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun removeBOMFromM3U8(params: Map<String, String>): Array<Any?>? {
        try {
            val result = arrayOfNulls<Any>(3)
            var url = params["url"]
            url = URLDecoder.decode(url, "UTF-8")

            val client = requireNotNull(HttpClients.itvClient()) { "itv client not initialized" }
            val redirectUrl = redirectedUrl(url)

            val request = Request.Builder().url(redirectUrl).build()
            executeRequest(client, request).use { response ->
                if (response.isSuccessful) {
                    assert(response.body != null)
                    var m3u8Content = response.body.string()
                    if (m3u8Content.startsWith("\ufeff")) {
                        m3u8Content = m3u8Content.substring(1)
                    }
                    result[0] = 200
                    result[1] = "application/vnd.apple.mpegurl"
                    result[2] = ByteArrayInputStream(m3u8Content.toByteArray(Charset.defaultCharset()))
                } else {
                    throw IOException("M3U8 Request failed with code: " + response.code)
                }
            }
            return result
        } catch (e: Exception) {
            AppLog.d("Proxy", "" + e.message)
            return null
        }
    }

    private fun buildRequest(url: String, params: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        copyHeader(builder, params, "ua", "User-Agent")
        copyHeader(builder, params, "user-agent", "User-Agent")
        copyHeader(builder, params, "User-Agent", "User-Agent")
        copyHeader(builder, params, "referer", "Referer")
        copyHeader(builder, params, "Referer", "Referer")
        copyHeader(builder, params, "origin", "Origin")
        copyHeader(builder, params, "Origin", "Origin")
        copyHeader(builder, params, "cookie", "Cookie")
        copyHeader(builder, params, "Cookie", "Cookie")
        copyHeader(builder, params, "range", "Range")
        copyHeader(builder, params, "Range", "Range")
        copyHeader(builder, params, "accept", "Accept")
        copyHeader(builder, params, "Accept", "Accept")
        copyHeader(builder, params, "accept-language", "Accept-Language")
        copyHeader(builder, params, "Accept-Language", "Accept-Language")
        return builder.build()
    }

    private fun copyHeader(builder: Request.Builder, params: Map<String, String>?, paramKey: String, headerKey: String) {
        if (params == null) return
        val value = params[paramKey]
        if (value == null || value.length == 0) return
        builder.header(headerKey, value)
    }

    @Throws(IOException::class)
    private fun executeRequest(client: OkHttpClient, request: Request): Response {
        try {
            return client.newCall(request).execute()
        } catch (e: IOException) {
            System.err.println("网络请求异常：" + e.message) // i18n: keep(异常消息,只进日志)
            throw e
        }
    }

    private fun processM3u8Content(m3u8Content: String?, m3u8Url: String, params: Map<String, String>): String {
        if (m3u8Content == null) return ""
        var content = m3u8Content
        if (content.startsWith("\ufeff")) content = content.substring(1)
        val m3u8Lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val processedM3u8 = StringBuilder()
        var nextIsVariant = false

        for (line in m3u8Lines) {
            val item = line.trim { it <= ' ' }
            if (item.length == 0) {
                processedM3u8.append(line).append("\n")
            } else if (item.startsWith("#")) {
                processedM3u8.append(rewriteUriAttributes(m3u8Url, line, params)).append("\n")
                nextIsVariant = item.startsWith("#EXT-X-STREAM-INF")
            } else {
                val type = if (nextIsVariant || isM3u8Url(item)) "m3u8" else "media"
                processedM3u8.append(joinUrl(m3u8Url, line, type, params)).append("\n")
                nextIsVariant = false
            }
        }
        return processedM3u8.toString().replace("\\n\\n", "\n")
    }

    private fun rewriteUriAttributes(base: String, line: String, params: Map<String, String>): String {
        val matcher = URI_ATTR.matcher(line)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val uri = matcher.group(1)
            val type = proxyTypeForUriAttr(line, uri)
            val replacement = "URI=\"" + joinUrl(base, uri, type, params) + "\""
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    private fun proxyTypeForUriAttr(line: String?, uri: String?): String {
        val upper = if (line == null) "" else line.uppercase()
        if (upper.startsWith("#EXT-X-KEY") || upper.startsWith("#EXT-X-SESSION-KEY")) return "key"
        if (isM3u8Url(uri) || upper.startsWith("#EXT-X-I-FRAME-STREAM-INF")) return "m3u8"
        return "media"
    }

    private fun isM3u8Url(url: String?): Boolean {
        if (url == null) return false
        var lower = url.lowercase()
        val query = lower.indexOf('?')
        if (query >= 0) lower = lower.substring(0, query)
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u")
    }

    @JvmStatic
    fun joinUrl(base: String?, url: String?, type: String, params: Map<String, String>?): String {
        var b = base
        var u = url
        if (b == null) b = ""
        if (u == null) u = ""
        try {
            u = u.trim { it <= ' ' }
            if (u.startsWith("data:") || u.startsWith("blob:")) return u
            val baseUri = URI(b.trim { it <= ' ' })
            val urlUri = URI(u)
            val proxyUrl = LocalAddress.get() + "proxy?go=live&type=" + type + headerQuery(params) + "&url="
            if (u.startsWith("http://") || u.startsWith("https://")) {
                return proxyUrl + URLEncoder.encode(urlUri.toString(), "UTF-8")
            } else if (u.startsWith("://")) {
                return proxyUrl + URLEncoder.encode(URI(baseUri.scheme + u).toString(), "UTF-8")
            } else if (u.startsWith("//")) {
                return proxyUrl + URLEncoder.encode(URI(baseUri.scheme + ":" + u).toString(), "UTF-8")
            } else {
                val resolvedUri = baseUri.resolve(u)
                return proxyUrl + URLEncoder.encode(resolvedUri.toString(), "UTF-8")
            }
        } catch (e: Exception) {
            AppLog.e("Proxy", e)
            return fallbackUrl(u, type, params)
        }
    }

    private fun fallbackUrl(url: String, type: String, params: Map<String, String>?): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        try {
            return LocalAddress.get() + "proxy?go=live&type=" + type + headerQuery(params) + "&url=" + URLEncoder.encode(url, "UTF-8")
        } catch (e: Exception) {
            AppLog.d("Proxy", "" + e.message)
            return url
        }
    }

    @Throws(Exception::class)
    private fun headerQuery(params: Map<String, String>?): String {
        val sb = StringBuilder()
        appendQueryHeader(sb, params, "User-Agent", "ua")
        appendQueryHeader(sb, params, "user-agent", "ua")
        appendQueryHeader(sb, params, "Referer", "referer")
        appendQueryHeader(sb, params, "referer", "referer")
        appendQueryHeader(sb, params, "Origin", "origin")
        appendQueryHeader(sb, params, "origin", "origin")
        appendQueryHeader(sb, params, "Cookie", "cookie")
        appendQueryHeader(sb, params, "cookie", "cookie")
        return sb.toString()
    }

    @Throws(Exception::class)
    private fun appendQueryHeader(sb: StringBuilder, params: Map<String, String>?, from: String, to: String) {
        if (params == null) return
        if (sb.indexOf("&" + to + "=") >= 0) return
        val value = params[from]
        if (value == null || value.length == 0) return
        sb.append("&").append(to).append("=").append(URLEncoder.encode(value, "UTF-8"))
    }

    private fun getMime(type: String?, url: String?, response: Response): String {
        val contentType = response.header("Content-Type")
        if (contentType != null && contentType.length > 0) return contentType
        if ("key" == type) return "application/octet-stream"
        var lower = if (url == null) "" else url.lowercase()
        val query = lower.indexOf('?')
        if (query >= 0) lower = lower.substring(0, query)
        if (lower.endsWith(".m4s") || lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4"
        if (lower.endsWith(".aac")) return "audio/aac"
        if (lower.endsWith(".vtt")) return "text/vtt"
        return "video/mp2t"
    }

    private fun responseHeaders(response: Response): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        copyResponseHeader(response, headers, "Content-Range")
        copyResponseHeader(response, headers, "Accept-Ranges")
        copyResponseHeader(response, headers, "Content-Length")
        copyResponseHeader(response, headers, "Cache-Control")
        return headers
    }

    private fun copyResponseHeader(response: Response, headers: MutableMap<String, String>, key: String) {
        val value = response.header(key)
        if (value != null && value.length > 0) headers[key] = value
    }

    private class ResponseInputStream(response: Response) : FilterInputStream(response.body.byteStream()) {
        private val response: Response = response

        @Throws(IOException::class)
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun redirectedUrl(url: String): String {
        val base = HttpClients.defaultClient
        val client = (if (base != null) base.newBuilder() else OkHttpClient.Builder().proxySelector(HttpClients.proxySelector()).proxyAuthenticator(HttpClients.proxyAuthenticator()))
                .followRedirects(false)
                .build()

        val request = Request.Builder()
                .url(url)
                .build()

        client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val resolved = resolveRedirectLocation(response.request.url, response.header("Location"))
                if (resolved != null) return resolved
            }
            return url
        }
    }

    @JvmStatic
    fun resolveRedirectLocation(requestUrl: HttpUrl?, location: String?): String? {
        if (requestUrl == null || location == null || location.length == 0) return null
        try {
            val resolved = requestUrl.resolve(location)
            return resolved?.toString()
        } catch (e: Exception) {
            AppLog.d("Proxy", "" + e.message)
            return null
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getM3U8Content(url: String): String {
        val request = Request.Builder()
                .url(url)
                .build()

        val client = requireNotNull(HttpClients.itvClient()) { "itv client not initialized" }
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body.string()
            } else {
                throw IOException("请求失败，HTTP 状态码: " + response.code) // i18n: keep(异常消息,只进日志)
            }
        }
    }

}
