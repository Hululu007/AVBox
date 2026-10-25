package com.github.catvod.crawler.js

import android.webkit.CookieManager

import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

object WebViewCookies {

    private val jar = WebViewCookieJar()

    @Volatile
    private var redirectClient: OkHttpClient? = null

    @Volatile
    private var noRedirectClient: OkHttpClient? = null

    fun getCookie(url: String): String {
        return try {
            CookieManager.getInstance().getCookie(url).orEmpty()
        } catch (th: Throwable) {
            AppLog.e("WebViewCookies", th)
            ""
        }
    }

    fun setCookie(url: String, cookie: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        if (Cookie.parse(httpUrl, cookie) == null) return false
        return try {
            CookieManager.getInstance().setCookie(url, cookie)
            true
        } catch (th: Throwable) {
            AppLog.e("WebViewCookies", th)
            false
        }
    }

    fun client(redirect: Boolean): OkHttpClient {
        val cached = if (redirect) redirectClient else noRedirectClient
        if (cached != null) return cached
        return synchronized(this) {
            val again = if (redirect) redirectClient else noRedirectClient
            if (again != null) return@synchronized again
            val base = if (redirect) HttpClients.defaultClient else HttpClients.noRedirectClient
            val built = requireNotNull(base) { "okhttp client not ready" }.newBuilder().cookieJar(jar).build()
            if (redirect) redirectClient = built else noRedirectClient = built
            built
        }
    }
}

private class WebViewCookieJar : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        try {
            val manager = CookieManager.getInstance()
            for (cookie in cookies) manager.setCookie(url.toString(), cookie.toString())
        } catch (th: Throwable) {
            AppLog.e("WebViewCookies", th)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = try {
            CookieManager.getInstance().getCookie(url.toString())
        } catch (th: Throwable) {
            AppLog.e("WebViewCookies", th)
            null
        } ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').mapNotNull { part -> Cookie.parse(url, part.trim()) }
    }
}
