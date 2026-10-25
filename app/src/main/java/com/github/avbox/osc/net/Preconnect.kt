package com.github.avbox.osc.net

import com.github.avbox.osc.util.AppLog
import java.io.IOException
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

object Preconnect {
    private const val MAX_TRACKED_HOSTS = 64

    private val warmedHosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val warmedCount = AtomicInteger()

    @JvmStatic
    fun warm(url: String?, headers: Map<String, String>?) {
        val link = url?.takeIf { it.isNotBlank() } ?: return
        val host = hostOf(link) ?: return
        if (!warmedHosts.add(host)) return
        if (warmedCount.incrementAndGet() > MAX_TRACKED_HOSTS) {
            warmedHosts.clear()
            warmedCount.set(1)
            warmedHosts.add(host)
        }
        val client = HttpClients.itvClient() ?: return
        try {
            val builder = Request.Builder().url(link).head()
            headers?.forEach { (name, value) ->
                if (!name.isNullOrBlank() && !value.isNullOrBlank()) builder.header(name, value)
            }
            client.newCall(builder.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
            AppLog.i("echo-preconnect: $host")
        } catch (t: Throwable) {
            AppLog.e("echo-preconnect failed: " + t.message)
        }
    }

    private fun hostOf(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val host = URI(url).host ?: return null
            if (host == "127.0.0.1" || host == "localhost" || host == "::1") null else host
        } catch (t: Throwable) {
            null
        }
    }
}
