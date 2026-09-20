package com.github.tvbox.osc.ui.activity

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.live.TxtSubscribe
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 代理直播源加载:配置里只有一条 `http://127.0.0.1:9978/proxy?...&ext=<base64>` 合成地址时,
 * 解开 ext 再取真正的直播列表 —— py/js 源走 Spider(带超时,超时作废),其余走 OkGo。
 * 频道列表与页面态留在宿主,这里只回调结果。
 */
internal class LiveProxyLoader(private val host: Host) {

    internal interface Host {
        /** 是否处于"刷新已有列表"流程(此时不改页面态,避免闪 loading) */
        fun isRefreshing(): Boolean

        fun onLoading()

        fun onEmpty()

        fun onGroupsLoaded(groups: List<LiveChannelGroup>)
    }

    companion object {
        /** 代理源地址白名单;不得改用 TextUtils.isEmpty(单测 returnDefaultValues 会静默返 false) */
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

    private val mHandler = Handler(Looper.getMainLooper())

    /** 页面销毁时清掉已入队的回调(晚到的网络响应由宿主自行兜底) */
    fun cancelAll() {
        mHandler.removeCallbacksAndMessages(null)
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
                host.onEmpty()
                return
            }
        }
        if (!isValidProxyUrl(realUrl)) {
            host.onEmpty()
            return
        }
        if (!host.isRefreshing()) {
            host.onLoading()
        }
        LOG.i("echo-live-url:$realUrl")
        if (realUrl.contains(".py") || realUrl.contains(".js")) {
            val finalUrl = realUrl
            val waitResponse = Runnable {
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit(Callable<String> {
                    val sp = ApiConfig.get().getLiveCSP(finalUrl)
                    sp.liveContent(finalUrl)
                })
                var sortJson: String? = null
                try {
                    sortJson = future.get(ApiConfig.get().liveConnectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    e.printStackTrace()
                    future.cancel(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    if (sortJson.isNullOrEmpty()) {
                        mHandler.post { host.onEmpty() }
                        return@Runnable
                    }
                    val livesArray = TxtSubscribe.parseToJsonArray(sortJson)
                    mHandler.post {
                        ApiConfig.get().loadLives(livesArray)
                        val list = ApiConfig.get().channelGroupList
                        if (list.isEmpty()) {
                            host.onEmpty()
                        } else {
                            host.onGroupsLoaded(ArrayList(list))
                        }
                    }
                    try {
                        executor.shutdown()
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }
            }
            Executors.newSingleThreadExecutor().execute(waitResponse)
        } else {
            OkGo.get<String>(realUrl).execute(object : AbsCallback<String>() {
                override fun convertResponse(response: okhttp3.Response): String {
                    return response.body.string()
                }

                override fun onSuccess(response: Response<String>) {
                    val livesArray = TxtSubscribe.parseToJsonArray(response.body())
                    ApiConfig.get().loadLives(livesArray)
                    val list = ApiConfig.get().channelGroupList
                    if (list.isEmpty()) {
                        mHandler.post { host.onEmpty() }
                        return
                    }
                    val loadedGroups = ArrayList(list)
                    mHandler.post { host.onGroupsLoaded(loadedGroups) }
                }

                override fun onError(response: Response<String>) {
                    mHandler.post { host.onEmpty() }
                }
            })
        }
    }
}
