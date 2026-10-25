package com.github.avbox.osc.util.parser
import android.util.Base64
import com.github.avbox.osc.util.HeaderGuard
import com.github.avbox.osc.util.AppLog
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

object JsonParallel {

    @Volatile
    private var currentTask: Task? = null

    private class Task {
        val client = OkHttpClient()
        val executorService: ExecutorService = Executors.newFixedThreadPool(5)
        val futures: MutableList<Future<JSONObject?>> = ArrayList()

        fun cancel() {
            try {
                client.dispatcher.cancelAll()
            } catch (ignored: Throwable) {
                AppLog.d("JsonParallel", "cancel dispatcher calls failed")
            }
            for (future in futures) {
                try {
                    future.cancel(true)
                } catch (ignored: Throwable) {
                    AppLog.d("JsonParallel", "cancel in-flight future failed")
                }
            }
            futures.clear()
            executorService.shutdownNow()
        }
    }

    @JvmStatic
    fun parse(jx: LinkedHashMap<String, String>?, url: String): JSONObject {
        val task = Task()
        cancelTasks()
        currentTask = task
        try {
            if (jx != null && jx.size > 0) {
                val completionService: CompletionService<JSONObject?> = ExecutorCompletionService(task.executorService)

                for (jxName in jx.keys) {
                    val parseUrl = jx[jxName] ?: continue
                    task.futures.add(completionService.submit(Callable<JSONObject?> {
                        try {
                            val reqHeaders = JsonParallel.reqHeader(parseUrl)
                            val realUrl = reqHeaders["url"]
                            reqHeaders.remove("url")
                            val headers = Headers.Builder().apply { reqHeaders.forEach { (name, value) -> add(name, value) } }.build()
                            val request = Request.Builder()
                                .url(realUrl + url)
                                .headers(headers)
                                .tag("ParseTag")
                                .build()

                            val call = task.client.newCall(request)
                            val response = call.execute()
                            val json = response.body.string()

                            val taskResult = if (json.isEmpty()) null else WebViewJs.jsonParse(url, json)
                            taskResult?.put("jxFrom", jxName)
                            taskResult
                        } catch (th: Throwable) {
                            null
                        }
                    }))
                }

                var pTaskResult: JSONObject? = null
                for (i in 0 until task.futures.size) {
                    val completed = completionService.take()
                    try {
                        pTaskResult = completed.get()
                        if (pTaskResult != null) {
                            for (future in task.futures) {
                                try {
                                    future.cancel(true)
                                } catch (t: Throwable) {
                                    AppLog.d("JsonParallel", "" + t.message)
                                }
                            }
                            task.futures.clear()
                            break
                        }
                    } catch (th: Throwable) {
                        AppLog.d("JsonParallel", "" + th.message)
                    }
                }
                if (pTaskResult != null)
                    return pTaskResult
            }
        } catch (th: Throwable) {
            AppLog.d("JsonParallel", "" + th.message)
        } finally {
            task.cancel()
            if (currentTask === task) currentTask = null
        }
        return JSONObject()
    }

    @JvmStatic
    fun cancelTasks() {
        val task = currentTask
        if (task != null) {
            task.cancel()
        }
    }
    @JvmStatic
    fun reqHeader(url: String): HashMap<String, String> {
        val reqHeaders = HashMap<String, String>()
        reqHeaders["url"] = url
        if (url.contains("cat_ext")) {
            try {
                val start = url.indexOf("cat_ext=")
                var end = url.indexOf("&", start)
                if (end == -1) end = url.length
                var ext = url.substring(start + 8, end)
                ext = String(Base64.decode(ext, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charset.defaultCharset())
                var newUrl = url.substring(0, start)
                if (end < url.length) newUrl += url.substring(end + 1)
                if (newUrl.endsWith("&") || newUrl.endsWith("?")) {
                    newUrl = newUrl.substring(0, newUrl.length - 1)
                }
                val jsonObject = JSONObject(ext)
                if (jsonObject.has("header")) {
                    val headerJson = jsonObject.optJSONObject("header")
                    if (headerJson != null) {
                        val keys = headerJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = headerJson.optString(key, "")
                            if (!HeaderGuard.isSendable(key, value)) {
                                AppLog.d("JsonParallel", "drop illegal header: " + key)
                                continue
                            }
                            reqHeaders[key] = value
                        }
                    }
                }
                reqHeaders["url"] = newUrl
            } catch (th: Throwable) {
                AppLog.d("JsonParallel", "cat_ext param decode failed, ignore extended headers")
            }
        }
        return reqHeaders
    }
}
