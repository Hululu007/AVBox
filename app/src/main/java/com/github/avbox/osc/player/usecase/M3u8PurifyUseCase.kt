package com.github.avbox.osc.player.usecase

import android.content.Context
import android.widget.Toast
import com.github.avbox.osc.R
import com.github.avbox.osc.base.App
import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.server.RemoteServer
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.player.usecase.M3u8
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.URL
import java.util.HashMap
import kotlin.coroutines.coroutineContext

class M3u8PurifyUseCase(context: Context, private val callback: Callback) {

    interface Callback {
        fun startPlayUrl(url: String?, headers: HashMap<String, String>?)

        fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?)
    }

    private fun str(resId: Int, vararg args: Any?): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    private val context: Context = context.applicationContext

    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    fun playM3u8(url: String, headers: HashMap<String, String>?) {
        if (url.contains("url=")) {
            callback.startPlayUrl(url, headers)
            return
        }
        cancelActive()
        val round = scope.launch {
            val content = try {
                request(url, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("echo-m3u8请求错误1: " + e)
                deliver { callback.startPlayUrl(url, headers) }
                return@launch
            }
            if (!content.startsWith("#EXTM3U")) {
                deliver { callback.startPlayUrl(url, headers) }
                return@launch
            }
            val forwardUrl = extractForwardUrl(url, content)
            if (forwardUrl.isEmpty()) {
                AppLog.i("echo-m3u81-to-play")
                processM3u8Content(url, content, headers)
            } else {
                fetchAndProcessForwardUrl(forwardUrl, headers, url)
            }
        }
        activeJob = round
    }

    private suspend fun deliver(block: () -> Unit) {
        if (activeJob === coroutineContext[Job]) activeJob = null
        block()
    }

    private suspend fun request(url: String, headers: HashMap<String, String>?): String {
        return Http.get(url) {
            if (headers != null) {
                for ((key, value) in headers) {
                    this.headers(key, value)
                }
            }
        }
    }

    private fun extractForwardUrl(baseUrl: String, content: String): String {
        val lines = RegexUtils.getPattern("\\r?\\n").split(content, -1)
        for (i in lines.indices) {
            val line = lines[i].trim { it <= ' ' }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until lines.size) {
                    val targetLine = lines[j].trim { it <= ' ' }
                    if (targetLine.isEmpty()) continue
                    if (isValidM3u8Line(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine)
                    }
                }
            }
        }
        return ""
    }

    private fun isValidM3u8Line(line: String): Boolean {
        return !line.startsWith("#") && (line.endsWith(".m3u8") || line.contains(".m3u8?"))
    }

    private suspend fun processM3u8Content(url: String, content: String, headers: HashMap<String, String>?) {
        val basePath = getBasePath(url)
        val (purified, adCount) = purifyLock.withLock {
            withContext(Dispatchers.IO) {
                val result = M3u8.purify(basePath, content)
                result to M3u8.currentAdCount
            }
        }
        if (purified == null || adCount == 0) {
            AppLog.i("echo-m3u8内容解析：未检测到广告")
            deliver { callback.startPlayUrl(url, headers) }
        } else {
            val key = RemoteServer.putM3u8Content(purified)
            val proxyUrl = ControlManager.get().getAddress(true) + "proxyM3u8?k=" + key
            deliver {
                callback.onM3u8ProxyUrl(proxyUrl, url)
                callback.startPlayUrl(proxyUrl, headers)
            }
            Toast.makeText(context, str(R.string.toast_ads_removed, adCount), Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fetchAndProcessForwardUrl(
        forwardUrl: String,
        headers: HashMap<String, String>?,
        fallbackUrl: String,
    ) {
        val content = try {
            request(forwardUrl, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("echo-重定向 m3u8 请求错误: " + e)
            deliver { callback.startPlayUrl(fallbackUrl, headers) }
            return
        }
        AppLog.i("echo-m3u82-to-play")
        processM3u8Content(forwardUrl, content, headers)
    }

    private fun getBasePath(url: String): String {
        val parts = RegexUtils.getPattern("/").split(url)
        return parts[0] + "/"
    }

    private fun resolveForwardUrl(baseUrl: String, line: String): String {
        return try {
            val base = URL(baseUrl)
            val resolved = URL(base, line)
            resolved.toString()
        } catch (e: MalformedURLException) {
            AppLog.e("echo-resolveForwardUrl异常: " + e.message)
            line
        }
    }

    companion object {

        private val purifyLock = Mutex()

        @Volatile
        private var activeJob: Job? = null

        fun cancelActive() {
            activeJob?.cancel()
        }
    }
}
