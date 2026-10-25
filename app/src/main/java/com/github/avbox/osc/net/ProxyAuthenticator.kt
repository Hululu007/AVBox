package com.github.avbox.osc.net

import androidx.annotation.NonNull

import com.github.avbox.osc.bean.ProxyRule
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

import java.net.InetSocketAddress

class ProxyAuthenticator(private val selector: OkProxySelector?) : okhttp3.Authenticator {

    override fun authenticate(route: Route?, @NonNull response: Response): Request? {
        if (route == null || response.request.header("Proxy-Authorization") != null) return null
        if (route.proxy.address() !is InetSocketAddress) return null
        val proxyAddress = route.proxy.address() as InetSocketAddress
        val userInfo = findUserInfo(response.request.url.host, proxyAddress.hostName)
        if (userInfo == null || !userInfo.contains(":")) return null
        val index = userInfo.indexOf(':')
        if (index <= 0 || index >= userInfo.length - 1) return null
        return response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(userInfo.substring(0, index), userInfo.substring(index + 1))).build()
    }

    private fun findUserInfo(requestHost: String?, proxyHost: String?): String? {
        if (selector == null || requestHost == null || proxyHost == null) return null
        for (item in selector.proxy) {
            if (matchesHost(item, requestHost)) {
                val userInfo = item.getUserInfo(proxyHost)
                if (userInfo != null) return userInfo
            }
        }
        return null
    }

    private fun matchesHost(item: ProxyRule, requestHost: String): Boolean {
        for (host in item.getHosts()) {
            val hostItem: String? = host
            if (hostItem != null && containOrMatch(requestHost, hostItem)) return true
        }
        return false
    }

    private fun containOrMatch(text: String, rule: String): Boolean {
        return try {
            text.contains(rule) || text.matches(Regex(rule))
        } catch (th: Throwable) {
            false
        }
    }
}
