package com.github.catvod.net

import androidx.collection.ArrayMap

import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.SSL.SSLSocketFactoryCompat

import java.net.ProxySelector
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import javax.net.ssl.X509TrustManager

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class OkHttp {

    companion object {

        private val TIMEOUT: Long = TimeUnit.SECONDS.toMillis(30)
        private var dns: OkDns? = null
        private var client: OkHttpClient? = null

        @JvmStatic
        @Volatile
        var baseClientProvider: (() -> OkHttpClient?)? = null

        @JvmStatic
        @Volatile
        var noRedirectClientProvider: (() -> OkHttpClient?)? = null

        @JvmStatic
        @Volatile
        var proxySelectorProvider: (() -> ProxySelector?)? = null

        @JvmStatic
        @Volatile
        var proxyAuthenticatorProvider: (() -> Authenticator?)? = null

        @JvmStatic
        fun dns(): OkDns {
            synchronized(OkHttp::class.java) {
                return dns ?: OkDns().also { dns = it }
            }
        }

        @JvmStatic
        fun client(): OkHttpClient {
            synchronized(OkHttp::class.java) {
                client?.let { return it }
                val base = baseClientProvider?.invoke()
                if (base != null) return base.newBuilder().dns(dns()).build().also { client = it }
                val builder = OkHttpClient.Builder().dns(dns()).connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                proxySelectorProvider?.invoke()?.let { builder.proxySelector(it) }
                proxyAuthenticatorProvider?.invoke()?.let { builder.proxyAuthenticator(it) }
                setOkHttpSsl(builder)
                return builder.build().also { client = it }
            }
        }

        @JvmStatic
        fun player(): OkHttpClient {
            return client()
        }

        @JvmStatic
        fun client(timeout: Long): OkHttpClient {
            return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build()
        }

        @JvmStatic
        fun noRedirect(): OkHttpClient {
            return noRedirect(TIMEOUT)
        }

        @JvmStatic
        fun noRedirect(timeout: Long): OkHttpClient {
            val base = noRedirectClientProvider?.invoke() ?: client()
            return base.newBuilder().dns(dns()).connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
        }

        @JvmStatic
        fun reset() {
            synchronized(OkHttp::class.java) {
                client = null
                dns = null
            }
        }

        @JvmStatic
        fun resetClient() {
            synchronized(OkHttp::class.java) {
                client = null
            }
        }

        @JvmStatic
        fun client(redirect: Boolean, timeout: Long): OkHttpClient {
            return if (redirect) client(timeout) else noRedirect(timeout)
        }

        @JvmStatic
        fun string(url: String?): String {
            if (url == null || !url.startsWith("http")) return ""
            try {
                newCall(url).execute().use { res ->
                    return res.body.string()
                }
            } catch (e: Exception) {
                AppLog.e("OkHttp", e)
                return ""
            }
        }

        @JvmStatic
        fun string(url: String?, timeout: Long): String {
            if (url == null || !url.startsWith("http")) return ""
            try {
                newCall(client(timeout), url).execute().use { res ->
                    return res.body.string()
                }
            } catch (e: Exception) {
                AppLog.e("OkHttp", e)
                return ""
            }
        }

        @JvmStatic
        fun string(url: String?, headers: Map<String, String>?): String {
            if (url == null || !url.startsWith("http")) return ""
            try {
                newCall(url, headers).execute().use { res ->
                    return res.body.string()
                }
            } catch (e: Exception) {
                AppLog.e("OkHttp", e)
                return ""
            }
        }

        @JvmStatic
        fun newCall(url: String): Call {
            return client().newCall(Request.Builder().url(url).build())
        }

        @JvmStatic
        fun newCall(url: String, tag: String?): Call {
            return client().newCall(Request.Builder().url(url).tag(tag).build())
        }

        @JvmStatic
        fun newCall(client: OkHttpClient, url: String): Call {
            return client.newCall(Request.Builder().url(url).build())
        }

        @JvmStatic
        fun newCall(client: OkHttpClient, url: String, tag: String?): Call {
            return client.newCall(Request.Builder().url(url).tag(tag).build())
        }

        @JvmStatic
        fun newCall(url: String, headers: Map<String, String>?): Call {
            return client().newCall(Request.Builder().url(url).headers(headers(headers)).build())
        }

        @JvmStatic
        fun newCall(url: String, headers: Map<String, String>?, params: ArrayMap<String, String>?): Call {
            return client().newCall(Request.Builder().url(buildUrl(url, params)).headers(headers(headers)).build())
        }

        @JvmStatic
        fun newCall(url: String, headers: Map<String, String>?, body: RequestBody): Call {
            return client().newCall(Request.Builder().url(url).headers(headers(headers)).post(body).build())
        }

        @JvmStatic
        fun newCall(url: String, body: RequestBody, tag: String?): Call {
            return client().newCall(Request.Builder().url(url).post(body).tag(tag).build())
        }

        @JvmStatic
        fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call {
            return client.newCall(Request.Builder().url(url).post(body).build())
        }

        @JvmStatic
        fun cancel(tag: String?) {
            cancel(client(), tag)
        }

        @JvmStatic
        fun cancel(client: OkHttpClient?, tag: String?) {
            if (client == null || tag == null) return
            for (call in client.dispatcher.queuedCalls()) if (tag == call.request().tag()) call.cancel()
            for (call in client.dispatcher.runningCalls()) if (tag == call.request().tag()) call.cancel()
        }

        @JvmStatic
        fun cancelAll() {
            cancelAll(client())
        }

        @JvmStatic
        fun cancelAll(client: OkHttpClient?) {
            if (client != null) client.dispatcher.cancelAll()
        }

        @JvmStatic
        fun toBody(params: ArrayMap<String, String>?): FormBody {
            val body = FormBody.Builder()
            if (params != null) for ((key, value) in params) body.add(key, value)
            return body.build()
        }

        private fun headers(headers: Map<String, String>?): Headers {
            return if (headers == null) Headers.Builder().build() else headers.toHeaders()
        }

        private fun setOkHttpSsl(builder: OkHttpClient.Builder) {
            try {
                val sslSocketFactory = SSLSocketFactoryCompat(TRUST_ALL_CERT)
                builder.sslSocketFactory(sslSocketFactory, TRUST_ALL_CERT)
                builder.hostnameVerifier { _, _ -> true }
            } catch (th: Throwable) {
                AppLog.e("OkHttp", "trust-all ssl setup failed, https sites may fail", th)
            }
        }

        private val TRUST_ALL_CERT: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }

        private fun buildUrl(url: String, params: ArrayMap<String, String>?): HttpUrl {
            val builder = requireNotNull(url.toHttpUrlOrNull()) { "invalid url" }.newBuilder()
            if (params != null) for ((key, value) in params) builder.addQueryParameter(key, value)
            return builder.build()
        }
    }
}
