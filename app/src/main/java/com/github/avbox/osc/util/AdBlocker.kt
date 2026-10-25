package com.github.avbox.osc.util

import android.webkit.WebResourceResponse

import java.io.ByteArrayInputStream
import java.nio.charset.Charset

object AdBlocker {
    private val AD_HOSTS = ArrayList<String>()

    @JvmStatic
    fun clear() {
        AD_HOSTS.clear()
    }

    @JvmStatic
    fun isEmpty(): Boolean {
        return AD_HOSTS.isEmpty()
    }

    @JvmStatic
    fun addAdHost(host: String) {
        AD_HOSTS.add(host)
    }

    @JvmStatic
    fun hasHost(host: String): Boolean {
        return AD_HOSTS.contains(host)
    }

    @JvmStatic
    fun isAd(url: String): Boolean {
        val lower = url.lowercase()
        for (adHost in AD_HOSTS) {
            if (lower.contains(adHost)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray(Charset.defaultCharset())))
    }

}
