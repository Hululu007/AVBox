package com.github.avbox.osc.player.core

import android.text.TextUtils

object HttpHeaders {

    const val HEADER_FORMAT = "TVBox-Format"

    const val HEADER_ISO = "TVBox-Iso"

    @JvmStatic
    fun toRequestHeaders(headers: Map<String, String>?): HashMap<String, String> {
        val requestHeaders = HashMap<String, String>()
        if (headers == null) {
            return requestHeaders
        }
        for (entry in headers.entries) {
            val key = entry.key
            val value = entry.value
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                continue
            }
            if (HEADER_FORMAT.equals(key, ignoreCase = true)) {
                continue
            }
            if (HEADER_ISO.equals(key, ignoreCase = true)) {
                continue
            }
            requestHeaders[key] = value.trim { it <= ' ' }
        }
        return requestHeaders
    }
}
