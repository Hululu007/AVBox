package com.github.avbox.osc.api

import android.text.TextUtils

import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.net.StreamProxy
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog

import java.net.URLDecoder
import java.util.HashMap

class ProxyEntry(private val owner: ApiConfig, private val spiderLoader: SpiderLoader) {
    private var currentPlaySourceKey = ""

    fun proxyLocal(param: MutableMap<String, String>): Array<Any?>? {
        val source = currentProxySource(param)
        val api = source.api ?: return null

        val siteKey = param["siteKey"]
        val action = param["do"]

        val isJs = "js" == action
        val isPy = "py" == action
        val isLive = KV.get(KVKeys.PLAYER_IS_LIVE, false)
        val isApiJs = api.contains(".js")
        val isApiPy = api.contains(".py")

        val canUseType3 = !TextUtils.isEmpty(siteKey)
                && source.type == 3
                && !isJs
                && !isPy
                && !isLive
                && !isApiJs
                && !isApiPy

        if (canUseType3) {
            try {
                val spider = owner.getCSP(source)

                var result: Array<Any?>? = null
                try {
                    result = SpiderReaper.track(spider) { spider.proxy(param) }
                } catch (th: Throwable) {
                    AppLog.e("echo-proxy-route: spider.proxy error, fallback | " + th)
                }
                if (result != null) {
                    AppLog.i("echo-proxy-route: spider.proxy ok | " + describeResult(result))
                    return result
                }
                AppLog.e("echo-proxy-route: spider.proxy null, try proxyInvokeJar")

                result = spiderLoader.proxyInvokeJar(param)
                if (result != null) {
                    AppLog.i("echo-proxy-route: proxyInvokeJar ok | " + describeResult(result))
                    return result
                }
                AppLog.e("echo-proxy-route: proxyInvokeJar null, try proxyDirect")

                result = proxyDirect(param)
                if (result != null) {
                    AppLog.i("echo-proxy-route: proxyDirect ok | " + describeResult(result))
                    return result
                }
                AppLog.e("echo-proxy-route: proxyDirect null, give up")

                return null
            } catch (th: Throwable) {
                AppLog.e("echo-proxy-route: type3 route error | " + th + " | msg=" + th.message)
                return null
            }
        }

        if (isJs) {
            return spiderLoader.proxyInvokeJs(param)
        }

        if (isLive) {
            val liveApi = spiderLoader.currentLiveSpider ?: ""

            if (liveApi.contains(".py")) {
                return spiderLoader.proxyInvokePy(param, spiderLoader.currentLivePyKey)
            }
            if (liveApi.contains(".js")) {
                return spiderLoader.proxyInvokeJs(param)
            }
            return spiderLoader.proxyInvokeJar(param)
        }

        if (isPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey())
        }

        if (isApiPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey())
        }

        return spiderLoader.proxyInvokeJar(param)
    }

    private fun proxyDirect(param: MutableMap<String, String>): Array<Any?>? {
        try {
            var url = param["url"]
            if (url.isNullOrEmpty()) return null
            url = URLDecoder.decode(url, "UTF-8")
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            if (!DefaultConfig.isVideoFormat(url)) return null
            if (url.contains(".m3u8")) {
                param["url"] = url
                param["go"] = "live"
                param["type"] = "m3u8"
                return StreamProxy.itv(param)
            }
            return null
        } catch (th: Throwable) {
            AppLog.e("echo-proxy direct fallback error: " + th.message)
            return null
        }
    }

    private fun describeResult(result: Array<Any?>): String {
        if (result.isEmpty()) return "empty"
        val status = result[0]
        if (status !is Int) return "body=" + (status?.javaClass?.simpleName ?: "null")
        return "status=" + status + " mime=" + result.getOrNull(1) +
            " stream=" + (result.getOrNull(2)?.javaClass?.simpleName ?: "null")
    }

    private fun currentProxySource(param: MutableMap<String, String>): SourceBean {
        var siteKey = param["siteKey"]
        if (TextUtils.isEmpty(siteKey)) {
            siteKey = currentPlaySourceKey
            if (!TextUtils.isEmpty(siteKey)) param["siteKey"] = siteKey
        }
        val sourceBean = if (TextUtils.isEmpty(siteKey)) null else owner.getSource(siteKey)
        return sourceBean ?: owner.homeSourceBean()
    }

    fun setCurrentPlaySourceKey(sourceKey: String?) {
        currentPlaySourceKey = sourceKey ?: ""
    }

    private fun getCurrentPyKey(): String? {
        val sourceBean = currentProxySource(HashMap<String, String>())
        if (sourceBean.api?.contains(".py") == true) {
            if (sourceBean.key != spiderLoader.currentPyKey) {
                spiderLoader.currentPyKey = sourceBean.key
                spiderLoader.pySpider(sourceBean.key, sourceBean.api, sourceBean.ext)
            }
            return spiderLoader.currentPyKey
        }
        return spiderLoader.currentPyKey
    }
}
