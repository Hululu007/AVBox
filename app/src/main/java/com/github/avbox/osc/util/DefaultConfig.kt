package com.github.avbox.osc.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.text.TextUtils

import com.google.gson.JsonObject

import java.util.ArrayList
import java.util.regex.Pattern

object DefaultConfig {

    @JvmStatic
    fun appVersionCode(hostContext: Context): Int {
        val pm = hostContext.packageManager
        try {
            val packageInfo = pm.getPackageInfo(hostContext.packageName, 0)
            return packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            AppLog.e("DefaultConfig", e)
        }
        return -1
    }

    @JvmStatic
    fun appVersionName(hostContext: Context): String? {
        val pm = hostContext.packageManager
        try {
            val packageInfo = pm.getPackageInfo(hostContext.packageName, 0)
            return packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            AppLog.e("DefaultConfig", e)
        }
        return ""
    }

    @JvmStatic
    fun fileSuffix(name: String?): String {
        if (TextUtils.isEmpty(name)) {
            return ""
        }
        val value = name ?: return ""
        val endP = value.lastIndexOf(".")
        return if (endP > -1) value.substring(endP) else ""
    }

    @JvmStatic
    fun filePrefixName(fileName: String?): String {
        if (TextUtils.isEmpty(fileName)) {
            return ""
        }
        val value = fileName ?: return ""
        val start = value.lastIndexOf(".")
        return if (start > -1) value.substring(0, start) else value
    }

    private val snifferMatch = Pattern.compile(
        "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)\\?.*|" +
                "http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)|" +
                "http((?!http).)*?video/tos*|" +
                "http((?!http).){20,}?/m3u8\\?pt=m3u8.*|" +
                "http((?!http).)*?default\\.ixigua\\.com/.*|" +
                "http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*|" +
                "http.*?/player/m3u8play\\.php\\?url=.*|" +
                "http.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
                "http.*?/playlist/m3u8/\\?vid=.*|" +
                "http.*?\\.php\\?type=m3u8&.*|" +
                "http.*?/download.aspx\\?.*|" +
                "http.*?/api/up_api.php\\?.*|" +
                "https.*?\\.66yk\\.cn.*|" +
                "http((?!http).)*?netease\\.com/file/.*"
    )
    @JvmStatic
    fun isVideoFormat(url: String): Boolean {
        val uri = Uri.parse(url)
        val path = uri.path
        if (TextUtils.isEmpty(path)) {
            return false
        }
        if (snifferMatch.matcher(url).find()) return true
        return false
    }

    @JvmStatic
    fun safeJsonString(obj: JsonObject, key: String, defaultVal: String): String {
        try {
            if (obj.has(key)) {
                return if (obj.get(key).isJsonObject || obj.get(key).isJsonArray)
                    obj.get(key).toString().trim { it <= ' ' }
                else
                    obj.getAsJsonPrimitive(key).asString.trim { it <= ' ' }
            } else
                return defaultVal
        } catch (th: Throwable) {
            AppLog.d("DefaultConfig", "json key '" + key + "' not a plain string, use default")
        }
        return defaultVal
    }

    @JvmStatic
    fun safeJsonInt(obj: JsonObject, key: String, defaultVal: Int): Int {
        try {
            if (obj.has(key))
                return obj.getAsJsonPrimitive(key).asInt
            else
                return defaultVal
        } catch (th: Throwable) {
            AppLog.d("DefaultConfig", "json key '" + key + "' not a number, use default")
        }
        return defaultVal
    }

    @JvmStatic
    fun safeJsonStringList(obj: JsonObject, key: String): ArrayList<String> {
        val result = ArrayList<String>()
        try {
            if (obj.has(key)) {
                if (obj.get(key).isJsonObject) {
                    result.add(obj.get(key).asString)
                } else {
                    for (opt in obj.getAsJsonArray(key)) {
                        result.add(opt.asString)
                    }
                }
            }
        } catch (th: Throwable) {
            AppLog.d("DefaultConfig", "json key '" + key + "' not a string list, use empty")
        }
        return result
    }

    @JvmStatic
    fun checkReplaceProxy(urlOri: String): String {
        if (urlOri.startsWith("proxy://"))
            return urlOri.replace("proxy://", LocalAddress.get() + "proxy?")
        return urlOri
    }

    private val NO_AD_KEYWORDS = listOf(
        "tx", "youku", "qq", "qiyi", "letv", "leshi", "sohu", "mgtv", "bilibili", "imgo", "优酷", "芒果", "腾讯", "奇艺" // i18n: keep(默认配置数据)
    )

    @JvmStatic
    fun noAd(flag: String?): Boolean {
        if (flag == null || flag.isEmpty()) return false
        for (keyword in NO_AD_KEYWORDS) {
            if (flag == keyword || flag.contains(keyword)) {
                return true
            }
        }
        return false
    }
}
