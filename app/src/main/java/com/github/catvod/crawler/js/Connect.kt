package com.github.catvod.crawler.js

import android.util.Base64

import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog
import com.google.common.net.HttpHeaders
import com.google.gson.JsonElement
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext

import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class Connect {

    class Result(@JvmField val code: Int, @JvmField val headers: Headers, @JvmField val bytes: ByteArray)

    companion object {

        @JvmField
        var client: OkHttpClient? = null

        @JvmStatic
        fun to(url: String, req: Req): Call {
            return to(url, req, "js_okhttp_tag")
        }

        @JvmStatic
        fun to(url: String, req: Req, tag: Any?): Call {
            val session = req.getSessionId()?.let { JsSession.find(it) ?: throw IllegalStateException("session closed") }
            val base = when {
                session != null -> session.client(req.isRedirect())
                req.isCookie() -> WebViewCookies.client(req.isRedirect())
                req.isRedirect() -> requireNotNull(HttpClients.defaultClient) { "okhttp client not ready" }
                else -> requireNotNull(HttpClients.noRedirectClient) { "okhttp client not ready" }
            }
            val okHttpClient = withTimeout(req, base)
            client = okHttpClient
            return okHttpClient.newCall(getRequest(url, req, req.getHeader().toHeaders(), session?.tag ?: tag))
        }

        @JvmStatic
        fun withTimeout(req: Req, base: OkHttpClient): OkHttpClient {
            var timeout = requireNotNull(req.getTimeout()) { "timeout is null" }.toLong()
            if (timeout <= 0) timeout = HttpClients.DEFAULT_MILLISECONDS
            val callTimeout = req.getCallTimeout().toLong()
            if (timeout == HttpClients.DEFAULT_MILLISECONDS && callTimeout <= 0) return base
            val builder = base.newBuilder()
            if (timeout != HttpClients.DEFAULT_MILLISECONDS) {
                builder.connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS)
            }
            if (callTimeout > 0) builder.callTimeout(callTimeout, TimeUnit.MILLISECONDS)
            return builder.build()
        }

        @JvmStatic
        fun read(res: Response): Result {
            try {
                return Result(res.code, res.headers, res.body.bytes())
            } finally {
                res.close()
            }
        }

        @JvmStatic
        fun success(ctx: QuickJSContext, req: Req, res: Response): JSObject {
            return try {
                success(ctx, req, read(res))
            } catch (e: Exception) {
                error(ctx, e.message.orEmpty())
            }
        }

        @JvmStatic
        fun success(ctx: QuickJSContext, req: Req, result: Result): JSObject {
            try {
                val jsObject = ctx.createNewJSObject()
                val jsHeader = ctx.createNewJSObject()
                setHeader(ctx, result.headers, jsHeader)
                ctx.setProperty(jsObject, "code", result.code)
                ctx.setProperty(jsObject, "headers", jsHeader)
                when (req.getBuffer()) {
                    0 -> ctx.setProperty(jsObject, "content", String(result.bytes, Charset.forName(req.getCharset())))
                    1 -> {
                        val array = ctx.createNewJSArray()
                        for (i in result.bytes.indices) array.set(result.bytes[i].toInt() and 0xFF, i)
                        ctx.setProperty(jsObject, "content", array)
                    }

                    2 -> ctx.setProperty(jsObject, "content", Base64.encodeToString(result.bytes, Base64.DEFAULT or Base64.NO_WRAP))
                    else -> ctx.setProperty(jsObject, "content", result.bytes)
                }
                return jsObject
            } catch (e: Exception) {
                return error(ctx, e.message.orEmpty())
            }
        }

        @JvmStatic
        fun error(ctx: QuickJSContext): JSObject {
            return error(ctx, "")
        }

        @JvmStatic
        fun error(ctx: QuickJSContext, message: String): JSObject {
            val jsObject = ctx.createNewJSObject()
            val jsHeader = ctx.createNewJSObject()
            ctx.setProperty(jsObject, "code", "")
            ctx.setProperty(jsObject, "headers", jsHeader)
            ctx.setProperty(jsObject, "content", "")
            ctx.setProperty(jsObject, "error", message)
            return jsObject
        }

        private fun getRequest(url: String, req: Req, headers: Headers, tag: Any?): Request {
            val target = buildUrl(url, req.getParams())
            return if (req.getMethod().equals("post", ignoreCase = true)) {
                Request.Builder().url(target).tag(tag).headers(headers).post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build()
            } else if (req.getMethod().equals("header", ignoreCase = true)) {
                Request.Builder().url(target).tag(tag).headers(headers).head().build()
            } else {
                Request.Builder().url(target).tag(tag).headers(headers).get().build()
            }
        }

        @JvmStatic
        fun buildUrl(url: String, params: JsonElement?): String {
            if (params == null || !params.isJsonObject) return url
            val entries = params.asJsonObject.entrySet()
            if (entries.isEmpty()) return url
            val builder = StringBuilder(url)
            var first = !url.contains('?')
            for ((key, value) in entries) {
                if (value == null || value.isJsonNull) continue
                if (value.isJsonArray) {
                    for (item in value.asJsonArray) {
                        val text = paramText(item) ?: continue
                        first = appendParam(builder, first, key, text)
                    }
                } else {
                    val text = paramText(value) ?: continue
                    first = appendParam(builder, first, key, text)
                }
            }
            return builder.toString()
        }

        private fun paramText(value: JsonElement?): String? {
            if (value == null || !value.isJsonPrimitive) return null
            return value.asString
        }

        private fun appendParam(builder: StringBuilder, first: Boolean, key: String, value: String): Boolean {
            builder.append(if (first) '?' else '&')
            builder.append(URLEncoder.encode(key, "UTF-8"))
            builder.append('=')
            builder.append(URLEncoder.encode(value, "UTF-8"))
            return false
        }

        private fun getPostBody(req: Req, contentType: String?): RequestBody {
            if (req.getData() != null && req.getPostType() == "json") return getJsonBody(req)
            if (req.getData() != null && req.getPostType() == "form") return getFormBody(req)
            if (req.getData() != null && req.getPostType() == "form-data") return getFormDataBody(req)
            val body = req.getBody()
            if (body != null && contentType != null) return body.toRequestBody(contentType.toMediaTypeOrNull())
            return "".toRequestBody(null)
        }

        private fun getJsonBody(req: Req): RequestBody {
            return requireNotNull(req.getData()) { "data is null" }.toString().toRequestBody("application/json".toMediaTypeOrNull())
        }

        private fun getFormBody(req: Req): RequestBody {
            val formBody = FormBody.Builder()
            val params = Json.toMap(req.getData())
            for ((key, value) in params) formBody.add(key, value)
            return formBody.build()
        }

        private fun getFormDataBody(req: Req): RequestBody {
            val boundary = "--dio-boundary-" + ThreadLocalRandom.current().nextInt(42949) + "" + ThreadLocalRandom.current().nextInt(67296)
            val builder = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
            val params = Json.toMap(req.getData())
            for ((key, value) in params) builder.addFormDataPart(key, value)
            return builder.build()
        }

        @JvmStatic
        fun setHeader(ctx: QuickJSContext, headers: Headers, obj: JSObject) {
            for (entry in headers.toMultimap().entries) {
                if (entry.value.size == 1) ctx.setProperty(obj, entry.key, entry.value[0])
                if (entry.value.size >= 2) ctx.setProperty(obj, entry.key, JSUtils<String>().toArray(ctx, entry.value))
            }
        }

        @JvmStatic
        fun cancelByTag(tag: Any?) {
            try {
                val target = requireNotNull(tag) { "tag is null" }
                cancelCalls(client, target)
                cancelCalls(HttpClients.defaultClient, target)
                cancelCalls(HttpClients.noRedirectClient, target)
            } catch (e: Exception) {
                AppLog.d("Connect", "cancel tag failed")
            }
        }

        @JvmStatic
        fun cancelCalls(okHttpClient: OkHttpClient?, tag: Any?) {
            if (okHttpClient == null || tag == null) return
            for (call in okHttpClient.dispatcher.queuedCalls()) {
                if (tag == call.request().tag()) call.cancel()
            }
            for (call in okHttpClient.dispatcher.runningCalls()) {
                if (tag == call.request().tag()) call.cancel()
            }
        }
    }
}
