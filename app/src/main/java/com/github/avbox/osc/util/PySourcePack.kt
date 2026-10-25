package com.github.avbox.osc.util

import java.net.URLDecoder

object PySourcePack {

    @JvmStatic
    fun packUrl(apiUrl: String?, content: String?): String? {
        val url = apiUrl?.trim().orEmpty()
        val text = content?.trimStart('\ufeff', ' ', '\t', '\n', '\r').orEmpty()
        if (text.isEmpty() || text.startsWith("{") || !url.contains(".py")) return null
        val digest = MD5.encode(url) ?: return null
        return build("py_" + digest.take(8), nameFromUrl(url), rewriteLanClan(url))
    }

    @JvmStatic
    fun packLocal(pyFileName: String, siteName: String, key: String): String =
        build(key, siteName, "./" + pyFileName)

    @JvmStatic
    fun packLocalJs(jsApi: String, siteName: String, key: String): String =
        build(key, siteName, jsApi, filterable = 0, fallbackName = "JS源")

    private fun build(
        key: String,
        name: String,
        api: String,
        filterable: Int = 1,
        fallbackName: String = "Python源",
    ): String =
        "{\"sites\":[{\"key\":\"${jsonEscape(key)}\",\"name\":\"${jsonEscape(name.ifBlank { fallbackName })}\"," +
            "\"type\":3,\"api\":\"${jsonEscape(api)}\",\"searchable\":1,\"quickSearch\":1,\"filterable\":$filterable}]}"

    private fun rewriteLanClan(url: String): String {
        if (!url.startsWith("clan://") || url.startsWith("clan://localhost/")) return url
        val link = url.substring(7)
        val end = link.indexOf('/')
        if (end <= 0) return url
        return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1)
    }

    private fun nameFromUrl(url: String): String {
        val tail = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val raw = if (tail.endsWith(".py", ignoreCase = true)) tail.dropLast(3) else tail
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (th: Throwable) {
            raw
        }
    }

    private fun jsonEscape(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
