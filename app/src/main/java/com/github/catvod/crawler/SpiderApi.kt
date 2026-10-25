package com.github.catvod.crawler

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Base64
import android.view.Surface
import android.view.WindowManager

import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class SpiderApi {

    fun getAddress(local: Boolean): String {
        try {
            return ControlManager.get().getAddress(local)
        } catch (th: Throwable) {
            return ""
        }
    }

    fun getPort(): String {
        try {
            val address = ControlManager.get().getAddress(true)
            val idx = address.lastIndexOf(":")
            return if (idx >= 0) address.substring(idx + 1).replace("/", "") else ""
        } catch (th: Throwable) {
            return ""
        }
    }

    fun log(msg: String?) {
        try {
            SpiderDebug.log(msg)
        } catch (ignored: Throwable) {
        }
    }

    fun getScreenOrientation(): Int {
        try {
            val activity = ActivityStack.getInstance().currentActivity()
            val context: Context = if (activity == null) requireNotNull(AppContextHolder.context()) { "app context not ready" } else activity
            val orientation = context.resources.configuration.orientation
            var rotation = Surface.ROTATION_0
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            if (windowManager != null && windowManager.defaultDisplay != null) {
                rotation = windowManager.defaultDisplay.rotation
            }
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return if (rotation == Surface.ROTATION_90) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (th: Throwable) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun multiReq(array: JsonArray?): String {
        var executor: ExecutorService? = null
        try {
            if (array == null || array.size() == 0) return ""
            executor = Executors.newFixedThreadPool(Math.min(array.size(), 6))
            val futures = ArrayList<Future<String>>()
            for (element in array) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                futures.add(executor.submit(Callable { request(obj) }))
            }
            val result = JsonArray()
            for (future in futures) result.add(toResult(future.get()))
            return result.toString()
        } catch (th: Throwable) {
            return ""
        } finally {
            executor?.shutdown()
        }
    }

    fun webParse(url: String?, flag: String?): String {
        try {
            if (url == null || url.isEmpty()) return ""
            val encoded = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
            return "proxy://go=SuperParse&flag=" + (if (flag == null) "" else flag) + "&url=" + encoded
        } catch (th: Throwable) {
            return ""
        }
    }

    companion object {

        @JvmStatic
        private fun request(obj: JsonObject): String {
            try {
                val url = string(obj, "url")
                if (url.isEmpty()) return ""
                val method = string(obj, "method")
                val headers = headers(obj.get("headers"))
                val builder = Request.Builder().url(url).headers(headers)
                if ("POST".equals(method, ignoreCase = true)) builder.post(body(obj))
                val client: OkHttpClient = com.github.catvod.net.OkHttp.client()
                client.newCall(builder.build()).execute().use { response ->
                    return response.body.string()
                }
            } catch (th: Throwable) {
                return ""
            }
        }

        @JvmStatic
        private fun toResult(text: String?): JsonElement {
            if (text == null) return JsonPrimitive("")
            try {
                val trim = text.trim { it <= ' ' }
                if (trim.startsWith("{") || trim.startsWith("[")) {
                    return JsonParser.parseString(trim)
                }
            } catch (ignored: Throwable) {
                AppLog.d("SpiderApi", "result json invalid, keep as string")
            }
            return JsonPrimitive(text)
        }

        @JvmStatic
        private fun body(obj: JsonObject): RequestBody {
            val data = obj.get("data")
            if (data == null || data.isJsonNull) return "".toRequestBody(null)
            val postType = string(obj, "postType")
            if ("form".equals(postType, ignoreCase = true) && data.isJsonObject) {
                val builder = FormBody.Builder()
                for ((key, value) in data.asJsonObject.entrySet()) {
                    builder.add(key, value.asString)
                }
                return builder.build()
            }
            return (if (data.isJsonPrimitive) data.asString else data.toString()).toRequestBody(null)
        }

        @JvmStatic
        private fun headers(element: JsonElement?): Headers {
            try {
                if (element == null || element.isJsonNull || !element.isJsonObject) return Headers.Builder().build()
                val map = HashMap<String, String>()
                for ((key, value) in element.asJsonObject.entrySet()) {
                    map[key] = value.asString
                }
                return map.toHeaders()
            } catch (th: Throwable) {
                return Headers.Builder().build()
            }
        }

        @JvmStatic
        private fun string(obj: JsonObject, key: String): String {
            val element = obj.get(key)
            return if (element == null || element.isJsonNull) "" else element.asString
        }
    }
}
