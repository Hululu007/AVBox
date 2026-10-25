package com.github.avbox.osc.sourcedata

import android.text.TextUtils

import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.HeaderGuard
import com.github.avbox.osc.util.AppLog

import org.json.JSONObject

import java.net.URLDecoder
import java.util.HashMap

object PushUrlParser {

    @JvmField
    val PUSH_AGENT = "push_agent"

    @JvmField
    val PUSH_FALLBACK = "push_fallback"

    @JvmField
    val PUSH_HEADERS_MARKER = "@Headers="

    @JvmStatic
    fun isPushFallback(sourceKey: String?, sourceBean: SourceBean?): Boolean {
        return PUSH_FALLBACK == sourceKey || (sourceBean != null && PUSH_AGENT == sourceBean.key && sourceBean.type == -1)
    }

    @JvmStatic
    fun isCastPushUrl(url: String?): Boolean {
        return !url.isNullOrEmpty() && url.contains(PUSH_HEADERS_MARKER)
    }

    @JvmStatic
    fun createPushPlayResult(rawUrl: String?, pushUrl: PushUrl, progressKey: String?, subtitleKey: String?, playFlag: String?): JSONObject? {
        try {
            val result = JSONObject()
            result.put("key", rawUrl)
            result.put("proKey", progressKey)
            result.put("subtKey", subtitleKey)
            result.put("flag", playFlag)
            result.put("parse", 0)
            result.put("url", pushUrl.url)
            mergePushHeaders(result, pushUrl)
            return result
        } catch (th: Throwable) {
            AppLog.e("SourceViewModel", th)
            return null
        }
    }

    @JvmStatic
    fun mergePushHeaders(result: JSONObject?, pushUrl: PushUrl?) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return
        try {
            var header = result.optJSONObject("header")
            if (header == null) header = result.optJSONObject("headers")
            if (header == null) header = JSONObject()
            for (key in pushUrl.headers.keys) {
                header.put(key, pushUrl.headers[key])
            }
            result.put("header", header)
        } catch (th: Throwable) {
            AppLog.e("SourceViewModel", "merge push headers failed", th)
        }
    }

    @JvmStatic
    fun createPushUrl(rawUrl: String?): PushUrl {
        val pushUrl = PushUrl()
        pushUrl.url = rawUrl ?: ""
        return pushUrl
    }

    @JvmStatic
    fun parsePushUrl(rawUrl: String?): PushUrl {
        val pushUrl = createPushUrl(rawUrl)
        parseMarkedHeaders(pushUrl)
        return pushUrl
    }

    private fun parseMarkedHeaders(pushUrl: PushUrl): Boolean {
        val marker = PUSH_HEADERS_MARKER
        val start = pushUrl.url.indexOf(marker)
        if (start < 0) return false
        val valueStart = start + marker.length
        val end = pushUrl.url.indexOf('@', valueStart)
        if (end < 0) return false
        try {
            val text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8")
            val json = JSONObject(text)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (TextUtils.isEmpty(key)) continue
                if (!HeaderGuard.isSendable(key, value)) {
                    AppLog.i("echo-push-header-skip:$key")
                    continue
                }
                pushUrl.headers[key] = value
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1)
            return true
        } catch (ignored: Throwable) {
            return false
        }
    }

    class PushUrl {
        @JvmField
        var url: String = ""

        @JvmField
        var headers: HashMap<String, String> = HashMap()
    }
}
