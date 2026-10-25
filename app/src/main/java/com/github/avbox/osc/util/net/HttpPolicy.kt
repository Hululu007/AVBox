package com.github.avbox.osc.util.net

import java.net.SocketTimeoutException
import java.util.Locale

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object HttpPolicy {

    const val MAX_RETRY = 3

    fun shouldFail(code: Int): Boolean {
        return code == 404 || code >= 500
    }

    fun shouldRetry(retryCount: Int, e: Throwable): Boolean {
        return retryCount < MAX_RETRY && e is SocketTimeoutException
    }

    fun buildUrl(url: String, params: Map<String, String>): HttpUrl {
        val builder = url.toHttpUrl().newBuilder()
        for ((key, value) in params) {
            builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    fun acceptLanguage(locale: Locale = Locale.getDefault()): String {
        val language = locale.language
        val country = locale.country
        return if (country.isEmpty()) language else language + "-" + country + "," + language + ";q=0.8"
    }
}
