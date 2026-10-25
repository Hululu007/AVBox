package com.github.catvod.crawler.js

import android.os.SystemClock
import android.webkit.WebSettings

import androidx.annotation.Keep

import com.github.catvod.Proxy
import com.github.catvod.crawler.js.rsa.RSAEncrypt
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.UserAgentPool
import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext

import java.io.IOException
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

class Global {

    private var runtime: QuickJSContext? = null

    @Volatile
    private var siteKey: String = ""

    @JvmField
    var executor: ExecutorService

    private val timer: Timer

    private val httpTag: String

    private val streams: MutableSet<OpenStream> = ConcurrentHashMap.newKeySet()

    private val proxyStreams: ConcurrentHashMap<Int, StreamEntry> = ConcurrentHashMap()

    private val sessions: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private val streamTokens: AtomicInteger = AtomicInteger()

    private val httpGate: HttpGate

    constructor(executor: ExecutorService) : this(executor, "default")

    constructor(executor: ExecutorService, key: String) {
        this.executor = executor
        this.httpTag = "js_okhttp_tag_" + key
        this.timer = Timer("js-spider-timer", true)
        this.httpGate = HttpGate(executor)
    }

    fun getHttpTag(): String {
        return httpTag
    }

    @Keep
    @Function
    fun getProxy(local: Boolean): String {
        return Proxy.getUrl(local) + "?do=js"
    }

    @Keep
    @Function
    fun js2Proxy(dynamic: Boolean?, siteType: Int?, siteKey: String?, url: String?, headers: JSObject): String {
        val local = dynamic == null || !dynamic
        return getProxy(local) + "&from=catvod" + "&siteType=" + siteType + "&siteKey=" + siteKey + "&header=" + URLEncoder.encode(headers.stringify()) + "&url=" + URLEncoder.encode(url)
    }

    @Keep
    @Function(name = "_getUserAgent")
    fun getUserAgent(): String {
        return resolveUserAgent()
    }

    @Keep
    @Function(name = "_getCookie")
    fun getCookie(url: String): String {
        return WebViewCookies.getCookie(url)
    }

    @Keep
    @Function(name = "_setCookie")
    fun setCookie(url: String, cookie: String): Boolean {
        return WebViewCookies.setCookie(url, cookie)
    }

    @Keep
    @Function(name = "_session")
    fun createSession(): Int {
        val id = JsSession.create()
        sessions.add(id)
        return id
    }

    @Keep
    @Function(name = "_sessionClose")
    fun closeSession(token: Int): Any? {
        if (!sessions.remove(token)) return null
        JsSession.close(token)
        closeSessionStreams(token)
        return null
    }

    @Keep
    @Function(name = "_sessionGetCookie")
    fun sessionGetCookie(token: Int, url: String): String {
        if (!sessions.contains(token)) return ""
        return JsSession.find(token)?.getCookie(url).orEmpty()
    }

    @Keep
    @Function(name = "_sessionSetCookie")
    fun sessionSetCookie(token: Int, url: String, cookie: String): Boolean {
        if (!sessions.contains(token)) return false
        return JsSession.find(token)?.setCookie(url, cookie) ?: false
    }

    @Keep
    @Function
    fun getProxyUrl(options: JSObject?): String {
        val url = JSUtils.toJsonObject(options).optString("url")
        if (url.isEmpty()) return ""
        val builder = StringBuilder(getProxy(true))
        if (siteKey.isNotEmpty()) builder.append("&siteKey=").append(URLEncoder.encode(siteKey, "UTF-8"))
        builder.append("&url=").append(URLEncoder.encode(url, "UTF-8"))
        return builder.toString()
    }

    fun setSiteKey(value: String?) {
        siteKey = value.orEmpty()
    }

    @Keep
    @Function
    fun joinUrl(parent: String?, child: String): String {
        return HtmlParser.joinUrl(parent, child)
    }

    @Keep
    @Function
    fun pd(html: String, rule: String, add_url: String): String {
        return HtmlParser.parseDomForUrl(html, rule, add_url)
    }

    @Keep
    @Function
    fun pdfh(html: String, rule: String): String {
        return HtmlParser.parseDomForUrl(html, rule, "")
    }

    @Keep
    @Function
    fun pdfa(html: String, rule: String): JSArray {

        return JSUtils<String>().toArray(requireNotNull(runtime) { "js runtime not ready" }, HtmlParser.parseDomForArray(html, rule))
    }

    @Keep
    @Function
    fun pdfla(html: String, p1: String, list_text: String, list_url: String, add_url: String): JSArray {
        return JSUtils<String>().toArray(requireNotNull(runtime) { "js runtime not ready" }, HtmlParser.parseDomForList(html, p1, list_text, list_url, add_url))
    }

    @Keep
    @Function
    fun s2t(text: String?): String? {
        try {
            return Trans.s2t(false, text)
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun t2s(text: String?): String? {
        try {
            return Trans.t2s(false, text)
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun aesX(mode: String?, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, iv: String?, outBase64: Boolean): String {
        val result = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64)
        return result
    }

    @Keep
    @Function
    fun rsaX(mode: String?, pub: Boolean, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, outBase64: Boolean): String {
        val result = Crypto.rsa(pub, encrypt, input, inBase64, key, outBase64)
        return result
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?): String? {
        return rsaEncrypt(data, key, null)
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?, options: JSObject?): String? {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
        }
        try {
            return when (mType) {
                1 -> {
                    if (mConfig != null) {
                        RSAEncrypt.encryptByPublicKey(data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.encryptByPublicKey(data, key, mLong, mBlock)
                    }
                }

                2 -> {
                    if (mConfig != null) {
                        RSAEncrypt.encryptByPrivateKey(data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.encryptByPrivateKey(data, key, mLong, mBlock)
                    }
                }

                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?): String? {
        return rsaDecrypt(encryptBase64Data, key, null)
    }

    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?, options: JSObject?): String? {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
        }
        try {
            return when (mType) {
                1 -> {
                    if (mConfig != null) {
                        RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mLong, mBlock)
                    }
                }

                2 -> {
                    if (mConfig != null) {
                        RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mLong, mBlock)
                    }
                }

                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun req(url: String, options: JSObject): JSObject {
        val ctx = requireNotNull(runtime) { "js runtime not ready" }
        try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            requireSession(req)
            val res = Connect.to(url, req, httpTag).execute()
            return Connect.success(ctx, req, res)
        } catch (e: Exception) {
            return Connect.error(ctx)
        }
    }

    private fun requireSession(req: Req) {
        val id = req.getSessionId() ?: return
        if (!sessions.contains(id)) throw IllegalStateException("session closed")
    }

    @Keep
    @Function
    fun _http(url: String, options: JSObject): JSObject? {
        val complete = options.getJSFunction("complete")
        if (complete == null) return req(url, options)
        val ctx = runtime
        return try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            requireSession(req)
            httpGate.dispatch(HttpGate.Request(
                start = Runnable {
                    try {
                        Connect.to(url, req, httpTag).enqueue(getCallback(complete, req, ctx))
                    } catch (th: Throwable) {
                        httpGate.finish()
                        deliver(ctx, complete, req, null, th.message.orEmpty())
                    }
                },
                fail = { message -> deliver(ctx, complete, req, null, message) }
            ))
            null
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            deliver(ctx, complete, null, null, th.message.orEmpty())
            null
        }
    }

    @Keep
    @Function(name = "_openStream")
    fun openStream(url: String, options: JSObject): JSObject? {
        val ctx = runtime ?: return null
        var opened: OpenStream? = null
        return try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            requireSession(req)
            val call = Connect.to(url, req, httpTag)
            val response = call.execute()
            val stream = OpenStream(call, response, req.getSessionId())
            opened = stream
            streams.add(stream)
            val obj = ctx.createNewJSObject()
            val headers = ctx.createNewJSObject()
            Connect.setHeader(ctx, response.headers, headers)
            ctx.setProperty(obj, "code", response.code)
            ctx.setProperty(obj, "headers", headers)
            ctx.setProperty(obj, "contentType", response.header("Content-Type") ?: "application/octet-stream")
            ctx.setProperty(obj, "read", object : JSCallFunction {
                override fun call(vararg args: Any?): Any? {
                    val size = (args.firstOrNull() as? Number)?.toInt() ?: DEFAULT_CHUNK
                    if (size !in 1..MAX_CHUNK) return null
                    val bytes = stream.read(size)
                    if (bytes == null) streams.remove(stream)
                    return bytes
                }
            })
            ctx.setProperty(obj, "close", object : JSCallFunction {
                override fun call(vararg args: Any?): Any? {
                    streams.remove(stream)
                    return stream.close()
                }
            })
            obj
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            opened?.let {
                streams.remove(it)
                it.close()
            }
            null
        }
    }

    @Keep
    @Function(name = "_stream")
    fun stream(url: String, options: JSObject, resolve: JSFunction?, reject: JSFunction?): Any? {
        val ctx = runtime
        if (ctx == null || resolve == null || reject == null) return null
        val reply = StreamReply(resolve, reject)
        reply.hold()
        val req = try {
            Req.objectFrom(JSUtils.toJsonObject(options).toString())
        } catch (th: Throwable) {
            settleStream(reply, null, th.message.orEmpty())
            return null
        }
        try {
            requireSession(req)
            val call = Connect.to(url, req, httpTag)
            httpGate.dispatch(HttpGate.Request(
                start = Runnable {
                    try {
                        call.enqueue(streamCallback(ctx, reply, req.getSessionId()))
                    } catch (th: Throwable) {
                        httpGate.finish()
                        settleStream(reply, null, th.message.orEmpty())
                    }
                },
                fail = { message -> settleStream(reply, null, message) }
            ))
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            settleStream(reply, null, th.message.orEmpty())
        }
        return null
    }

    @Keep
    @Function(name = "_streamClose")
    fun streamClose(token: Int): Boolean {
        val entry = proxyStreams.remove(token) ?: return false
        entry.stream.close()
        AppLog.i("echo-js-stream close " + token)
        return true
    }

    fun consumeStream(token: Int): ProxyStream? {
        return proxyStreams.remove(token)?.stream
    }

    private fun settleStream(reply: StreamReply, result: JSObject?, message: String) {
        if (!reply.take()) return
        try {
            executor.submit(Runnable {
                try {
                    if (result != null) reply.ok(result) else reply.fail(message)
                } catch (th: Throwable) {
                    AppLog.e("Global", th)
                } finally {
                    reply.release()
                }
            })
        } catch (th: Throwable) {
            AppLog.d("Global", "executor shutdown, drop stream callback")
            reply.release()
        }
    }

    private fun streamCallback(ctx: QuickJSContext, reply: StreamReply, sessionId: Int?): Callback {
        return object : Callback {
            override fun onResponse(call: Call, response: Response) {
                httpGate.finish()
                if (executor.isShutdown) {
                    response.close()
                    settleStream(reply, null, "executor shutdown")
                    return
                }
                try {
                    executor.submit(Runnable { deliverStream(ctx, response, reply, sessionId) })
                } catch (th: Throwable) {
                    response.close()
                    settleStream(reply, null, th.message.orEmpty())
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                httpGate.finish()
                settleStream(reply, null, e.message.orEmpty())
            }
        }
    }

    private fun deliverStream(ctx: QuickJSContext, response: Response, reply: StreamReply, sessionId: Int?) {
        try {
            sweepProxyStreams()
            val token = streamTokens.incrementAndGet()
            val headers = ctx.createNewJSObject()
            Connect.setHeader(ctx, response.headers, headers)
            val obj = ctx.createNewJSObject()
            ctx.setProperty(obj, "code", response.code)
            ctx.setProperty(obj, "headers", headers)
            ctx.setProperty(obj, "contentType", response.header("Content-Type") ?: "application/octet-stream")
            val handle = ctx.createNewJSObject()
            ctx.setProperty(handle, "__token", token)
            ctx.setProperty(handle, "close", object : JSCallFunction {
                override fun call(vararg args: Any?): Any? = streamClose(token)
            })
            obj.setProperty("stream", handle)
            proxyStreams[token] = StreamEntry(ProxyStream(response), SystemClock.elapsedRealtime(), sessionId)
            AppLog.i("echo-js-stream open " + token + " code=" + response.code)
            settleStream(reply, obj, "")
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            response.close()
            settleStream(reply, null, th.message.orEmpty())
        }
    }

    private fun sweepProxyStreams() {
        val now = SystemClock.elapsedRealtime()
        for ((token, entry) in proxyStreams) {
            if (now - entry.created < STREAM_MAX_IDLE_MS) continue
            if (proxyStreams.remove(token, entry)) entry.stream.close()
        }
    }

    @Keep
    @Function(name = "_htmlQuery")
    fun htmlQuery(html: String, plan: JSObject): JSObject? {
        val ctx = runtime ?: return null
        return try {
            val json = HtmlQuery.query(html, plan.stringify())
            ctx.parse(json) as? JSObject
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            null
        }
    }

    @Keep
    @Function
    fun setTimeout(func: JSFunction, delay: Int?) {
        func.hold()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!executor.isShutdown) {
                    try {
                        executor.submit(Runnable {
                            try {
                                func.call()
                            } finally {
                                func.release()
                            }
                        })
                    } catch (e: RejectedExecutionException) {
                        func.release()
                    }
                } else {
                    func.release()
                }
            }
        }, requireNotNull(delay) { "delay is null" }.toLong())
    }

    private fun closeSessionStreams(token: Int) {
        for (stream in streams) {
            if (stream.sessionId != token) continue
            if (streams.remove(stream)) stream.close()
        }
        for ((key, entry) in proxyStreams) {
            if (entry.sessionId != token) continue
            if (proxyStreams.remove(key, entry)) entry.stream.close()
        }
    }

    fun destroy() {
        for (token in sessions) {
            JsSession.close(token)
            closeSessionStreams(token)
        }
        sessions.clear()
        timer.cancel()
        httpGate.clear()
        for (stream in streams) stream.close()
        streams.clear()
        for (entry in proxyStreams.values) entry.stream.close()
        proxyStreams.clear()
        runtime = null
    }

    fun cancelPending() {
        httpGate.cancelPending()
        for (token in sessions) JsSession.find(token)?.cancelCalls()
    }

    private fun getCallback(complete: JSFunction, req: Req, ctx: QuickJSContext?): Callback {
        return object : Callback {
            override fun onResponse(call: Call, response: Response) {
                var result: Connect.Result? = null
                var message = ""
                try {
                    result = Connect.read(response)
                } catch (th: Throwable) {
                    message = th.message.orEmpty()
                }
                httpGate.finish()
                if (executor.isShutdown) return
                deliver(ctx, complete, req, result, message)
            }

            override fun onFailure(call: Call, e: IOException) {
                httpGate.finish()
                if (executor.isShutdown) return
                deliver(ctx, complete, req, null, e.message.orEmpty())
            }
        }
    }

    private fun deliver(ctx: QuickJSContext?, complete: JSFunction, req: Req?, result: Connect.Result?, message: String) {
        if (ctx == null) return
        try {
            executor.submit(Runnable {
                try {
                    val obj = if (result != null && req != null) Connect.success(ctx, req, result) else Connect.error(ctx, message)
                    complete.call(obj)
                } catch (th: Throwable) {
                    AppLog.e("Global", th)
                }
            })
        } catch (ignored: RejectedExecutionException) {
            AppLog.d("Global", "executor shutdown, drop callback")
        }
    }

    @Keep
    @ContextSetter
    fun setJSContext(runtime: QuickJSContext) {
        this.runtime = runtime
    }

    private class StreamEntry(val stream: ProxyStream, val created: Long, val sessionId: Int?)

    private class StreamReply(private val resolve: JSFunction, private val reject: JSFunction) {

        private val settled = AtomicBoolean(false)

        fun hold() {
            resolve.hold()
            reject.hold()
        }

        fun take(): Boolean = settled.compareAndSet(false, true)

        fun ok(result: JSObject) {
            resolve.call(result)
        }

        fun fail(message: String) {
            reject.call(message)
        }

        fun release() {
            try {
                resolve.release()
            } catch (th: Throwable) {
                AppLog.d("Global", "release stream resolve failed")
            }
            try {
                reject.release()
            } catch (th: Throwable) {
                AppLog.d("Global", "release stream reject failed")
            }
        }
    }

    companion object {

        @Volatile
        private var userAgent: String = ""

        private fun resolveUserAgent(): String {
            val cached = userAgent
            if (cached.isNotEmpty()) return cached
            val value = try {
                WebSettings.getDefaultUserAgent(requireNotNull(AppContextHolder.context()) { "app context not initialized" })
            } catch (th: Throwable) {
                AppLog.e("Global", th)
                UserAgentPool.random()
            }
            userAgent = value
            return value
        }

        private const val DEFAULT_CHUNK = 65536

        private const val MAX_CHUNK = 1048576

        private const val STREAM_MAX_IDLE_MS = 120_000L
    }
}
