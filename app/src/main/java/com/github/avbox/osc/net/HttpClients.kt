package com.github.avbox.osc.net

import com.github.catvod.net.OkDns
import com.github.catvod.net.OkHttp
import com.github.avbox.osc.bean.ProxyRule
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.Probe
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.SSL.SSLSocketFactoryCompat
import com.github.avbox.osc.util.VodImages
import com.google.gson.JsonArray
import com.google.gson.JsonParser

import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.TimeUnit

import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

object HttpClients {
    const val DEFAULT_MILLISECONDS = 10000L

    private const val dnsConfigJson = ("["
            + "{\"name\": \"腾讯\", \"url\": \"https://doh.pub/dns-query\"}," // i18n: keep(DNS 配置数据)
            + "{\"name\": \"阿里\", \"url\": \"https://dns.alidns.com/dns-query\"}," // i18n: keep(DNS 配置数据)
            + "{\"name\": \"360\", \"url\": \"https://doh.360.cn/dns-query\"}"
            + "]")

    @JvmField
    var ItvClient: OkHttpClient? = null

    private var proxySelector: OkProxySelector? = null
    private var proxyAuthenticator: ProxyAuthenticator? = null

    @JvmStatic
    @Synchronized
    fun proxySelector(): OkProxySelector {
        var selector = proxySelector
        if (selector == null) {
            selector = OkProxySelector()
            proxySelector = selector
        }
        return selector
    }

    @JvmStatic
    @Synchronized
    fun proxyAuthenticator(): ProxyAuthenticator {
        var authenticator = proxyAuthenticator
        if (authenticator == null) {
            authenticator = ProxyAuthenticator(proxySelector())
            proxyAuthenticator = authenticator
        }
        return authenticator
    }

    @JvmStatic
    @Synchronized
    fun setProxyList(proxyRules: List<ProxyRule>?) {
        proxySelector().clear()
        if (proxyRules != null && !proxyRules.isEmpty()) proxySelector().addAll(proxyRules)
        OkHttp.reset()
    }

    private fun initExoOkHttpClient() {
        val base = defaultClient
        val builder = if (base != null) base.newBuilder() else OkHttpClient.Builder()

        builder.retryOnConnectionFailure(true)
        builder.followRedirects(true)
        builder.followSslRedirects(true)
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())

        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            AppLog.e("HttpClients", th)
        }

        builder.dns(CustomDns())
        ItvClient = Probe.call("boot:httpItvClient") { builder.build() }
    }

    @JvmField
    @Volatile
    var dnsOverHttps: DnsOverHttps? = null

    @JvmField
    @Volatile
    var dnsHttpsList: List<String> = Collections.emptyList()

    @JvmField
    var is_doh: Boolean = false

    @JvmField
    @Volatile
    var myHosts: Map<String, String>? = null

    @JvmStatic
    var hostsProvider: (() -> Map<String, String>?)? = null

    @JvmStatic
    fun dohConfigArray(): JsonArray {
        val merged = JsonArray()
        val keys = HashSet<String>()
        try {
            appendDohItems(merged, keys, JsonParser.parseString(dnsConfigJson).asJsonArray)
        } catch (e: Exception) {
            AppLog.e("HttpClients", e)
        }
        appendDohItems(merged, keys, parseDohArray(KV.get(KVKeys.DOH_JSON, "")))
        return merged
    }

    private fun parseDohArray(json: String?): JsonArray? {
        if (json == null || json.isEmpty()) return null
        try {
            return JsonParser.parseString(json).asJsonArray
        } catch (e: Exception) {
            AppLog.e("HttpClients", e)
            return null
        }
    }

    private fun appendDohItems(target: JsonArray, keys: MutableSet<String>, source: JsonArray?) {
        if (source == null) return
        for (i in 0 until source.size()) {
            val element = source.get(i)
            if (element == null || !element.isJsonObject) continue
            val item = element.asJsonObject
            val key = if (item.has("url")) item.get("url").asString
                    else (if (item.has("name")) item.get("name").asString else null)
            if (key == null || !keys.add(key)) continue
            target.add(item)
        }
    }

    @JvmStatic
    fun getDohUrl(type: Int): String {
        val jsonArray = dohConfigArray()
        if (type >= 1 && type <= jsonArray.size()) {
            val dnsConfig = jsonArray.get(type - 1).asJsonObject
            return if (dnsConfig.has("url")) dnsConfig.get("url").asString else ""
        }
        return ""
    }

    @JvmStatic
    fun applyDohConfig(dohJson: String?) {
        val pinned = getDohUrl(KV.get(KVKeys.DOH_URL, 0))
        KV.put(KVKeys.DOH_JSON, dohJson)
        val merged = dohConfigArray()

        val list = ArrayList<String>()
        list.add("关闭") // i18n: keep(DNS 选项索引锚点,显示由设置页映射资源)
        for (i in 0 until merged.size()) {
            val dnsConfig = merged.get(i).asJsonObject
            val name = if (dnsConfig.has("name")) dnsConfig.get("name").asString else "Unknown Name"
            list.add(name)
        }
        dnsHttpsList = list

        val index = indexOfDohUrl(merged, pinned)
        if (index >= 0) {
            KV.put(KVKeys.DOH_URL, index + 1)
        } else if (KV.get(KVKeys.DOH_URL, 0) > merged.size()) {
            KV.put(KVKeys.DOH_URL, 0)
        }
        refreshHosts()
    }

    @JvmStatic
    fun indexOfDohUrl(merged: JsonArray?, url: String?): Int {
        if (merged == null || url == null || url.isEmpty()) return -1
        for (i in 0 until merged.size()) {
            val element = merged.get(i)
            if (element == null || !element.isJsonObject) continue
            val item = element.asJsonObject
            val key = if (item.has("url")) item.get("url").asString
                    else (if (item.has("name")) item.get("name").asString else null)
            if (url == key) return i
        }
        return -1
    }

    @JvmStatic
    fun refreshHosts() {
        myHosts = hostsProvider?.invoke()
    }

    private fun DohIps(ips: JsonArray?): List<InetAddress> {
        val inetAddresses = ArrayList<InetAddress>()
        if (ips != null) {
            for (j in 0 until ips.size()) {
                try {
                    val inetAddress = InetAddress.getByName(ips.get(j).asString)
                    inetAddresses.add(inetAddress)
                } catch (e: Exception) {
                    AppLog.e("HttpClients", e)
                }
            }
        }
        return inetAddresses
    }

    private fun initDnsOverHttps() {
        val selector = KV.get(KVKeys.DOH_URL, 0)
        val ips = initDohSelector(selector)
        Thread({ buildDohClient(selector, ips) }, "http-doh-init").start()
    }

    private fun initDohSelector(selector: Int): JsonArray? {
        var ips: JsonArray? = null
        Probe.step("httpDohSelector") {
            try {
                val jsonArray = dohConfigArray()
                val effective = if (selector > jsonArray.size()) {
                    KV.put(KVKeys.DOH_URL, 0)
                    0
                } else {
                    selector
                }
                val list = ArrayList<String>()
                list.add("关闭") // i18n: keep(DNS 选项索引锚点,显示由设置页映射资源)
                for (i in 0 until jsonArray.size()) {
                    val dnsConfig = jsonArray.get(i).asJsonObject
                    val name = if (dnsConfig.has("name")) dnsConfig.get("name").asString else "Unknown Name"
                    list.add(name)
                    if (effective == (i + 1)) ips = if (dnsConfig.has("ips")) dnsConfig.getAsJsonArray("ips") else null
                }
                dnsHttpsList = list
            } catch (e: Exception) {
                AppLog.e("HttpClients", e)
            }
        }
        return ips
    }

    private fun buildDohClient(selector: Int, ips: JsonArray?) {
        Probe.step("httpDohBuild") {
            try {
                val builder = OkHttpClient.Builder()
                builder.proxySelector(proxySelector())
                builder.proxyAuthenticator(proxyAuthenticator())
                try {
                    setOkHttpSsl(builder)
                } catch (th: Throwable) {
                    AppLog.e("HttpClients", th)
                }
                Probe.step("httpDohCache") {
                    builder.cache(Cache(File(requireNotNull(AppContextHolder.context()) { "app context not initialized" }.cacheDir.absolutePath, "dohcache"), 100L * 1024 * 1024))
                }
                val dohClient = builder.build()
                val dohUrl = getDohUrl(selector)
                dnsOverHttps = if (dohUrl.isEmpty()) null
                        else Probe.step("httpDohDns") { DnsOverHttps.Builder().client(dohClient).url(dohUrl.toHttpUrl()).bootstrapDnsHosts(if (ips != null && dohUrl != "https://doh.pub/dns-query") DohIps(ips) else null).build() }
            } catch (e: Throwable) {
                AppLog.e("HttpClients", e)
            }
        }
    }

    private class CustomDns : Dns {
        private var map: ConcurrentHashMap<String, List<InetAddress>>? = null
        private val excludeIps = "2409:8087:6c02:14:100::14,2409:8087:6c02:14:100::18,39.134.108.253,39.134.108.245"

        constructor()

        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            val originalHost = hostname
            var hosts = myHosts
            if (hosts == null) hosts = hostsProvider?.invoke()
            var hostname = hostname
            if (hosts != null && !hosts.isEmpty() && hosts.containsKey(hostname)) {
                hostname = hosts.get(hostname) ?: hostname
            }
            assert(hostname != null)
            if (isValidIpAddress(hostname)) {
                return Collections.singletonList(InetAddress.getByName(hostname))
            } else {
                val dns = dnsOverHttps ?: Dns.SYSTEM
                return dns.lookup(hostname)
            }
        }

        @Synchronized
        @Throws(UnknownHostException::class)
        fun mapHosts(hosts: Map<String, String>) {
            val m = ConcurrentHashMap<String, List<InetAddress>>()
            map = m
            for (entry in hosts.entries) {
                val key = entry.key
                val value = entry.value
                if (isValidIpAddress(value)) {
                    m.put(key, Collections.singletonList(InetAddress.getByName(value)))
                } else {
                    m.put(key, allByName(value))
                }
            }
        }

        private fun allByName(host: String): List<InetAddress> {
            try {
                val allAddresses = InetAddress.getAllByName(host)
                if (excludeIps.isEmpty()) return Arrays.asList(*allAddresses)
                val validAddresses = ArrayList<InetAddress>()
                val excludeIpsSet = HashSet<String>()
                for (ip in RegexUtils.getPattern(",").split(excludeIps)) {
                    excludeIpsSet.add(ip.trim { it <= ' ' })
                }
                for (address in allAddresses) {
                    if (!excludeIpsSet.contains(address.hostAddress)) {
                        validAddresses.add(address)
                    }
                }
                return validAddresses
            } catch (e: Exception) {
                return ArrayList()
            }
        }

        private fun isValidIpAddress(str: String): Boolean {
            if (str.indexOf('.') > 0) return isValidIPv4(str)
            return str.indexOf(':') > 0
        }

        private fun isValidIPv4(str: String): Boolean {
            val parts = RegexUtils.getPattern("\\.").split(str)
            if (parts.size != 4) return false
            for (part in parts) {
                try {
                    Integer.parseInt(part)
                } catch (e: NumberFormatException) {
                    return false
                }
            }
            return true
        }
    }

    @Volatile
    internal var defaultClient: OkHttpClient? = null

    @Volatile
    internal var noRedirectClient: OkHttpClient? = null

    @JvmStatic
    fun itvClient(): OkHttpClient? {
        return ItvClient
    }

    @JvmStatic
    fun init() {
        Probe.step("httpDohClient") { initDnsOverHttps() }

        val builder = OkHttpClient.Builder()

        Probe.step("httpClients") {
            builder.dns(CustomDns())
            builder.proxySelector(proxySelector())
            builder.proxyAuthenticator(proxyAuthenticator())
            builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
            builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
            builder.connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
            try {
                setOkHttpSsl(builder)
            } catch (th: Throwable) {
                AppLog.e("HttpClients", th)
            }
        }

        val okHttpClient = builder.build()
        okHttpClient.dispatcher.maxRequestsPerHost = 10

        defaultClient = okHttpClient

        builder.followRedirects(false)
        builder.followSslRedirects(false)
        noRedirectClient = builder.build()

        Probe.step("httpExoClient") { initExoOkHttpClient() }
    }

    @Synchronized
    private fun setOkHttpSsl(builder: OkHttpClient.Builder) {
        try {
            val trustAllCert: X509TrustManager =
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                        }

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                            return arrayOf()
                        }
                    }
            val sslSocketFactory: SSLSocketFactory = SSLSocketFactoryCompat(trustAllCert)
            builder.sslSocketFactory(sslSocketFactory, trustAllCert)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    init {
        OkHttp.baseClientProvider = { defaultClient }
        OkHttp.noRedirectClientProvider = { noRedirectClient }
        OkHttp.proxySelectorProvider = { proxySelector() }
        OkHttp.proxyAuthenticatorProvider = { proxyAuthenticator() }
        OkDns.dnsOverHttpsProvider = { dnsOverHttps }
        VodImages.dnsProvider = { defaultClient?.dns }
    }
}
