package com.github.avbox.osc.net

import java.util.Locale

object ProxyHeaderPolicy {

    private val FORWARDABLE = setOf(
        "content-range",
        "accept-ranges",
        "content-length",
        "content-encoding",
        "cache-control"
    )

    fun filter(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((key, value) in headers) {
            if (FORWARDABLE.contains(key.lowercase(Locale.ROOT))) result[key] = value
        }
        return result
    }
}
