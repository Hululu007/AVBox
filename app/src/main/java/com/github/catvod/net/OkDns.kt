package com.github.catvod.net

import com.github.avbox.osc.util.RegexUtils

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

import okhttp3.Dns

class OkDns : Dns {

    private val hosts = ConcurrentHashMap<String, String>()

    fun addAll(hosts: List<String>?) {
        if (hosts == null) return
        for (host in hosts) {
            if (host == null) continue
            val splits = RegexUtils.getPattern("=").split(host, 2)
            if (splits.size == 2) this.hosts[splits[0].trim { it <= ' ' }] = splits[1].trim { it <= ' ' }
        }
    }

    fun clear() {
        hosts.clear()
    }

    private fun get(hostname: String): String {
        val target = hosts[hostname]
        if (target != null) return target
        for ((k, v) in hosts) {
            if (hostname.contains(k)) return v
        }
        return hostname
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val dns = dnsOverHttpsProvider?.invoke() ?: Dns.SYSTEM
        return dns.lookup(get(hostname))
    }

    companion object {

        @JvmStatic
        @Volatile
        var dnsOverHttpsProvider: (() -> Dns?)? = null
    }
}
