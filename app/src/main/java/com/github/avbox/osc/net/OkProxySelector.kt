package com.github.avbox.osc.net

import com.github.avbox.osc.bean.ProxyRule

import java.io.IOException
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class OkProxySelector : ProxySelector() {

    val proxy: MutableList<ProxyRule> = CopyOnWriteArrayList()
    private val system: ProxySelector? = ProxySelector.getDefault()

    @Synchronized
    fun addAll(items: List<ProxyRule>?) {
        if (items == null || items.isEmpty()) return
        for (item in items) {
            if (item != null) item.init()
        }
        proxy.addAll(items)
        Collections.sort(proxy)
    }

    @Synchronized
    fun clear() {
        proxy.clear()
    }

    private fun fallback(uri: URI?): List<java.net.Proxy> {
        if (system != null && uri != null) return system.select(uri)
        val result: MutableList<java.net.Proxy> = ArrayList()
        result.add(java.net.Proxy.NO_PROXY)
        return result
    }

    override fun select(uri: URI?): List<java.net.Proxy> {
        if (proxy.isEmpty() || uri == null || uri.host == null) return fallback(uri)
        val host = uri.host
        if ("127.0.0.1" == host || "localhost".equals(host, ignoreCase = true)) return fallback(uri)
        for (item in proxy) {
            for (rule in item.getHosts()) {
                if (rule == null) continue
                if (containOrMatch(host, rule)) {
                    val proxies = item.getProxies()
                    return if (proxies.isEmpty()) fallback(uri) else proxies
                }
            }
        }
        return fallback(uri)
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException) {
        if (system != null) system.connectFailed(uri, socketAddress, e)
    }

    private fun containOrMatch(text: String, rule: String): Boolean {
        return try {
            text.contains(rule) || text.matches(Regex(rule))
        } catch (th: Throwable) {
            false
        }
    }
}
