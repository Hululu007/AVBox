package com.github.avbox.osc.bean

import android.net.Uri
import android.text.TextUtils
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.net.InetSocketAddress
import java.util.ArrayList
import java.util.Collections

class ProxyRule : Comparable<ProxyRule> {

    @field:SerializedName("name")
    private var name: String? = null

    @field:SerializedName("hosts")
    private var hosts: MutableList<String>? = null

    @field:SerializedName("urls")
    private var urls: MutableList<String>? = null

    private var proxies: MutableList<java.net.Proxy>? = null

    private var uris: MutableList<Uri>? = null

    private var wildcard: Boolean = false

    fun init() {
        wildcard = false
        for (host in getHosts()) {
            val hostItem: String? = host
            if (hostItem != null && hostItem.indexOf('*') >= 0) {
                wildcard = true
                break
            }
        }
        uris = ArrayList()
        for (url in getUrls()) {
            if (TextUtils.isEmpty(url)) continue
            val uri = Uri.parse(url)
            if (isValid(uri)) uris?.add(uri)
        }
        proxies = ArrayList()
        for (uri in uris.orEmpty()) {
            val proxy = create(uri)
            if (proxy != null) proxies?.add(proxy)
        }
    }

    fun getName(): String {
        return name ?: ""
    }

    fun getHosts(): MutableList<String> {
        return hosts ?: Collections.emptyList()
    }

    fun getUrls(): MutableList<String> {
        return urls ?: Collections.emptyList()
    }

    fun getProxies(): MutableList<java.net.Proxy> {
        return proxies ?: Collections.emptyList()
    }

    fun getUserInfo(host: String?): String? {
        if (uris == null || host == null) return null
        for (uri in uris.orEmpty()) {
            if (uri == null || uri.host == null) continue
            if (host.equals(uri.host, true)) {
                val userInfo = uri.userInfo
                if (!TextUtils.isEmpty(userInfo)) return userInfo
            }
        }
        return null
    }

    private fun isValid(uri: Uri?): Boolean {
        return uri != null && uri.scheme != null && uri.host != null && uri.port > 0
    }

    private fun create(uri: Uri): java.net.Proxy? {
        val address = InetSocketAddress.createUnresolved(uri.host, uri.port)
        if (isScheme(uri, "http")) return java.net.Proxy(java.net.Proxy.Type.HTTP, address)
        if (isScheme(uri, "socks")) return java.net.Proxy(java.net.Proxy.Type.SOCKS, address)
        return null
    }

    private fun isScheme(uri: Uri, scheme: String): Boolean {
        return uri.scheme?.startsWith(scheme) == true
    }

    override fun compareTo(other: ProxyRule): Int {
        return java.lang.Boolean.compare(this.wildcard, other.wildcard)
    }

    companion object {

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<ProxyRule> {
            return try {
                val listType: Type = object : TypeToken<List<ProxyRule>>() {}.type
                val items: List<ProxyRule>? = Gson().fromJson(element, listType)
                items ?: Collections.emptyList()
            } catch (e: Exception) {
                Collections.emptyList()
            }
        }
    }
}
