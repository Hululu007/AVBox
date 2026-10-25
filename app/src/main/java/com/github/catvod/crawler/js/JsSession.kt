package com.github.catvod.crawler.js

import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class JsSession private constructor(val id: Int) {

    val tag: String = "js_session_" + id

    private val jar = MemoryCookieJar()

    private val closed = AtomicBoolean(false)

    @Volatile
    private var redirectClient: OkHttpClient? = null

    @Volatile
    private var noRedirectClient: OkHttpClient? = null

    fun client(redirect: Boolean): OkHttpClient {
        check(!closed.get()) { "session closed" }
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

    fun getCookie(url: String): String {
        check(!closed.get()) { "session closed" }
        return jar.getCookie(url)
    }

    fun setCookie(url: String, cookie: String): Boolean {
        check(!closed.get()) { "session closed" }
        return jar.setCookie(url, cookie)
    }

    fun cancelCalls() {
        Connect.cancelCalls(redirectClient, tag)
        Connect.cancelCalls(noRedirectClient, tag)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            cancelCalls()
        } catch (th: Throwable) {
            AppLog.d("JsSession", "cancel calls failed")
        }
        jar.clear()
    }

    companion object {

        private val nextId = AtomicInteger()

        private val sessions = ConcurrentHashMap<Int, JsSession>()

        @JvmStatic
        fun create(): Int {
            val id = nextId.incrementAndGet()
            sessions[id] = JsSession(id)
            return id
        }

        @JvmStatic
        fun find(id: Int): JsSession? {
            return sessions[id]
        }

        @JvmStatic
        fun close(id: Int) {
            sessions.remove(id)?.close()
        }
    }
}

internal class MemoryCookieJar : CookieJar {

    private val lock = Any()

    private val cookies = LinkedHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                val key = key(cookie)
                if (cookie.expiresAt <= now) this.cookies.remove(key) else this.cookies[key] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val iterator = cookies.values.iterator()
            while (iterator.hasNext()) if (iterator.next().expiresAt <= now) iterator.remove()
            return cookies.values.filter { it.matches(url) }
        }
    }

    fun getCookie(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(httpUrl).joinToString("; ") { it.name + "=" + it.value }
    }

    fun setCookie(url: String, cookie: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        val parsed = Cookie.parse(httpUrl, cookie) ?: return false
        saveFromResponse(httpUrl, listOf(parsed))
        return true
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
        }
    }

    private fun key(cookie: Cookie): String {
        return cookie.domain + "|" + cookie.path + "|" + cookie.name
    }
}
