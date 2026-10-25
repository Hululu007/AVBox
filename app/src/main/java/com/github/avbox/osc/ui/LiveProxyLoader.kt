package com.github.avbox.osc.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.util.BoundedCall
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.live.TxtSubscribe
import com.github.avbox.osc.util.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal class LiveProxyLoader(private val host: Host) {

    internal interface Host {
        fun isRefreshing(): Boolean

        fun onLoading()

        fun onEmpty()

        fun onGroupsLoaded(groups: List<LiveChannelGroup>)
    }

    companion object {
        fun isValidProxyUrl(url: String?): Boolean {
            if (url == null || url.isEmpty()) return false
            val lowerUrl = url.trim { it <= ' ' }.lowercase(Locale.US)
            return lowerUrl.startsWith("http://") ||
                    lowerUrl.startsWith("https://") ||
                    lowerUrl.startsWith("rtsp://") ||
                    lowerUrl.startsWith("rtmp://") ||
                    lowerUrl.startsWith("rtp://")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val loadScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
        loadScope.coroutineContext.cancelChildren()
    }

    fun load(url: String) {
        var realUrl = url
        try {
            val parsedUrl = Uri.parse(realUrl)
            realUrl = String(
                Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                charset("UTF-8"),
            )
        } catch (th: Throwable) {
            if (!realUrl.startsWith("http://127.0.0.1")) {
                AppLog.e("echo-live-load-fail url=$realUrl err=$th")
                host.onEmpty()
                return
            }
        }
        if (!isValidProxyUrl(realUrl)) {
            AppLog.e("echo-live-load-fail url=$realUrl err=invalid-url")
            host.onEmpty()
            return
        }
        if (!host.isRefreshing()) {
            host.onLoading()
        }
        AppLog.i("echo-live-url:$realUrl")
        if (realUrl.contains(".py") || realUrl.contains(".js")) {
            val finalUrl = realUrl
            val waitResponse = Runnable {
                val sortJson = BoundedCall.call(Callable {
                    val sp = ApiConfig.get().getLiveCSP(finalUrl)
                    SpiderReaper.track(sp) { sp.liveContent(finalUrl) }
                }, ApiConfig.get().liveConnectTimeoutSeconds * 1000L, "echo-live-proxy")
                if (sortJson.isNullOrEmpty()) {
                    AppLog.e("echo-live-load-fail url=$finalUrl err=empty-content")
                    handler.post { host.onEmpty() }
                    return@Runnable
                }
                try {
                    val livesArray = TxtSubscribe.parseToJsonArray(sortJson)
                    handler.post {
                        ApiConfig.get().loadLives(livesArray)
                        val list = ApiConfig.get().channelGroupList
                        if (list.isEmpty()) {
                            AppLog.e("echo-live-load-fail url=$finalUrl err=empty-groups")
                            host.onEmpty()
                        } else {
                            host.onGroupsLoaded(ArrayList(list))
                        }
                    }
                } catch (th: Throwable) {
                    AppLog.e("echo-live-load-fail url=$finalUrl err=$th")
                }
            }
            Executors.newSingleThreadExecutor().also {
                it.execute(waitResponse)
                it.shutdown()
            }
        } else {
            loadScope.launch {
                try {
                    val body = Http.get(realUrl)
                    val livesArray = withContext(Dispatchers.IO) { TxtSubscribe.parseToJsonArray(body) }
                    ApiConfig.get().loadLives(livesArray)
                    val list = ApiConfig.get().channelGroupList
                    if (list.isEmpty()) {
                        AppLog.e("echo-live-load-fail url=$realUrl err=empty-groups")
                        handler.post { host.onEmpty() }
                        return@launch
                    }
                    val loadedGroups = ArrayList(list)
                    handler.post { host.onGroupsLoaded(loadedGroups) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e("echo-live-load-fail url=$realUrl err=$e")
                    handler.post { host.onEmpty() }
                }
            }
        }
    }
}
