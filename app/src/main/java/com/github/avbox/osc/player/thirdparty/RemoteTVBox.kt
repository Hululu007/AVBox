package com.github.avbox.osc.player.thirdparty

import android.app.Activity
import android.text.TextUtils

import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LocalIPAddress

import java.io.IOException
import java.net.URLEncoder
import java.util.HashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object RemoteTVBox {

    @JvmStatic
    fun run(activity: Activity, url: String, title: String, subtitle: String, headers: HashMap<String, String>?): Boolean {
        val actionUrl = availableActionUrl()
        if (TextUtils.isEmpty(actionUrl)) {
            return false
        }
        try {
            var url = url
            if (headers != null && headers.size > 0) {
                url = url + "|"
                var idx = 0
                for (hk in headers.keys) {
                    url += URLEncoder.encode(hk, "UTF-8") + "=" + URLEncoder.encode(headers[hk], "UTF-8")
                    if (idx < headers.keys.size - 1) {
                        url += "&"
                    }
                    idx++
                }
            }
            val params = HashMap<String, String>()
            params["do"] = "push"
            params["url"] = url
            post(actionUrl, params, object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.e("RemoteTVBox", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val pushResult = response.body.string()
                    if (pushResult == "ok") {

                    }
                }
            })
        } catch (e: Exception) {
            AppLog.e("RemoteTVBox", e)
        }

        return true
    }

    @JvmStatic
    fun searchAvailable(): Flow<String> = callbackFlow {
        launch(Dispatchers.IO) {
            val localIp = AppContextHolder.context()?.let { LocalIPAddress.getLocalIPAddress(it) }.orEmpty()
            val divisionIp = if (TextUtils.isEmpty(localIp)) -1 else localIp.lastIndexOf(".")
            if (divisionIp <= 0) {
                close()
                return@launch
            }
            val prefix = localIp.substring(0, divisionIp + 1)
            val port = 9978
            val finishedNum = AtomicInteger(0)
            val total = 254
            for (i in 1..255) {
                val ip = prefix + i
                if (ip == localIp) {
                    continue
                }
                val actionUrl = "http://" + ip + ":" + port + "/action"
                val viewHost = ip + ":" + port
                try {
                    post(actionUrl, null, object : okhttp3.Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (finishedNum.incrementAndGet() == total) close()
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = if (response.body == null) "" else response.body.string()
                                if ("ok".equals(result, ignoreCase = true)) trySend(viewHost)
                            } finally {
                                response.close()
                            }
                            if (finishedNum.incrementAndGet() == total) close()
                        }
                    })
                } catch (e: Exception) {
                    if (finishedNum.incrementAndGet() == total) close()
                }
            }
        }
        awaitClose { }
    }

    @JvmStatic
    var avalible: String?
        get() = KV.get<String>(KVKeys.REMOTE_TVBOX, null)
        set(value) {
            KV.put(KVKeys.REMOTE_TVBOX, value)
        }

    @JvmStatic
    fun availableActionUrl(): String {
        if (avalible == null) {
            return ""
        }
        return "http://" + avalible + "/action"
    }

    @JvmStatic
    fun post(url: String?, params: Map<String, String>?, callback: okhttp3.Callback) {
        post(url, params, null, callback)
    }

    @JvmStatic
    fun post(url: String?, params: Map<String, String>?, headers: Map<String, String>?, callback: okhttp3.Callback) {
        val base = HttpClients.defaultClient
        val builder = if (base != null) base.newBuilder() else OkHttpClient.Builder().proxySelector(HttpClients.proxySelector()).proxyAuthenticator(HttpClients.proxyAuthenticator())
        builder.readTimeout(1000, TimeUnit.MILLISECONDS)
        builder.writeTimeout(1000, TimeUnit.MILLISECONDS)
        builder.connectTimeout(1000, TimeUnit.MILLISECONDS)
        val client = builder.build()
        val formBodyBuilder = FormBody.Builder()
        if (params != null && params.size > 0) {
            for ((k, v) in params) {
                formBodyBuilder.add(k, v)
            }
        }
        val formBody = formBodyBuilder.build()
        val requestBuilder = Request.Builder().url(requireNotNull(url))
        if (headers != null) {
            for ((key, value) in headers) {
                if (isBodyManagedHeader(key)) {
                    AppLog.i("echo-site-header-skip-body:" + key)
                    continue
                }
                requestBuilder.header(key, value)
            }
        }
        client.newCall(requestBuilder.post(formBody).build()).enqueue(callback)
    }

    private fun isBodyManagedHeader(name: String): Boolean {
        return "content-type".equals(name, ignoreCase = true)
                || "content-length".equals(name, ignoreCase = true)
                || "host".equals(name, ignoreCase = true)
    }

}
