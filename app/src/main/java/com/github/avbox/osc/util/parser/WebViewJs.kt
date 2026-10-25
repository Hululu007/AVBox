package com.github.avbox.osc.util.parser

import android.annotation.TargetApi
import android.os.Build
import android.webkit.ValueCallback
import android.webkit.WebView

import org.json.JSONException
import org.json.JSONObject
import java.util.Arrays
import java.util.regex.Pattern

@TargetApi(Build.VERSION_CODES.KITKAT)
object WebViewJs {

    @JvmField
    val RULE: Pattern = Pattern.compile("http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3)\\?.*|http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3)|http((?!http).)*?video/tos*")
    const val UaWinChrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
    const val UaMobile = "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.1 Mobile/15E148 Safari/604.1"

    @JvmStatic
    fun isVip(url: String): Boolean {
        val hosts: List<String> = Arrays.asList("iqiyi.com", "v.qq.com", "youku.com", "le.com", "tudou.com", "mgtv.com", "sohu.com", "acfun.cn", "bilibili.com", "baofeng.com", "pptv.com")
        for (host in hosts) if (url.contains(host)) return true
        return false
    }

    @JvmStatic
    fun isVideoFormat(url: String): Boolean {
        if (url.contains("url=http") || url.contains(".js") || url.contains(".css") || url.contains(".html")) return false
        return RULE.matcher(url).find()
    }

    @JvmStatic
    fun substring(text: String?): String? {
        return substring(text, 1)
    }

    @JvmStatic
    fun substring(text: String?, num: Int): String? {
        if (text != null && text.length > num) {
            return text.substring(0, text.length - num)
        } else {
            return text
        }
    }

    @JvmStatic
    fun loadUrl(webView: WebView, script: String) {
        loadUrl(webView, script, null)
    }

    @JvmStatic
    fun loadUrl(webView: WebView, script: String, callback: ValueCallback<String>?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) webView.evaluateJavascript(script, callback)
        else webView.loadUrl(script)
    }



    @JvmStatic
    fun isBlackVodUrl(input: String, url: String): Boolean {
        if (url.contains("973973.xyz") || url.contains(".fit:"))
            return true
        return false
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun fixJsonVodHeader(headers: JSONObject?, input: String, url: String): JSONObject {
        val result = headers ?: JSONObject()
        if (input.contains("www.mgtv.com")) {
            result.put("Referer", " ")
            result.put("User-Agent", " Mozilla/5.0")
        } else if (url.contains("titan.mgtv")) {
            result.put("Referer", " ")
            result.put("User-Agent", " Mozilla/5.0")
        } else if (input.contains("bilibili")) {
            result.put("Referer", " https://www.bilibili.com/")
            result.put("User-Agent", " " + UaWinChrome)
        }
        return result
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun jsonParse(input: String, json: String): JSONObject? {
        val jsonPlayData = JSONObject(json)
        var url: String
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url")
        } else {
            url = jsonPlayData.getString("url")
        }
        if (url.startsWith("//")) {
            url = "https:" + url
        }
        if (!url.startsWith("http")) {
            return null
        }
        if (url == input) {
            if (isVip(url) || !isVideoFormat(url)) {
                return null
            }
        }
        if (WebViewJs.isBlackVodUrl(input, url)) {
            return null
        }
        var headers = JSONObject()
        val ua = jsonPlayData.optString("user-agent", "")
        if (ua.trim { it <= ' ' }.length > 0) {
            headers.put("User-Agent", " " + ua)
        }
        val referer = jsonPlayData.optString("referer", "")
        if (referer.trim { it <= ' ' }.length > 0) {
            headers.put("Referer", " " + referer)
        }

        headers = WebViewJs.fixJsonVodHeader(headers, input, url)
        val taskResult = JSONObject()
        taskResult.put("header", headers)
        taskResult.put("url", url)
        return taskResult
    }
}
