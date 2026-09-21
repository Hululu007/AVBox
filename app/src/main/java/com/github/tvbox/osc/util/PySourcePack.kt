package com.github.tvbox.osc.util

import java.net.URLDecoder

/**
 * py 爬虫 → 单站点配置的包装:「输入框直填 py 地址」与「本地导入 py 文件」共用这一份 JSON 形状。
 */
object PySourcePack {

    /** 配置拉取到的正文是 py 源码时返回包装后的 JSON;地址不像 py、或正文本身是 JSON 配置(含带 BOM)时返回 null */
    @JvmStatic
    fun packUrl(apiUrl: String?, content: String?): String? {
        val url = apiUrl?.trim().orEmpty()
        val text = content?.trimStart('\ufeff', ' ', '\t', '\n', '\r').orEmpty()
        if (text.isEmpty() || text.startsWith("{") || !url.contains(".py")) return null
        val digest = MD5.encode(url) ?: return null
        return build("py_" + digest.take(8), nameFromUrl(url), rewriteLanClan(url))
    }

    /** 本地导入的 py 文件:api 用 `./` 相对引用(配置加载阶段会被改写成本机服务地址) */
    @JvmStatic
    fun packLocal(pyFileName: String, siteName: String, key: String): String =
        build(key, siteName, "./" + pyFileName)

    private fun build(key: String, name: String, api: String): String =
        "{\"sites\":[{\"key\":\"${jsonEscape(key)}\",\"name\":\"${jsonEscape(name.ifBlank { "Python源" })}\"," +
            "\"type\":3,\"api\":\"${jsonEscape(api)}\",\"searchable\":1,\"quickSearch\":1,\"filterable\":1}]}"

    /**
     * 局域网 clan 地址(`clan://<ip>/…`)必须在这里换成 http:配置加载的 clanContentFix 只改写
     * `clan://localhost/`,留给它的话会在 Python 侧 `requests.get("clan://…")` 直接 InvalidSchema。
     * localhost 形式刻意保留 —— 由 clanContentFix 用**运行时**本机服务地址替换,比这里写死更准。
     */
    private fun rewriteLanClan(url: String): String {
        if (!url.startsWith("clan://") || url.startsWith("clan://localhost/")) return url
        val link = url.substring(7)
        val end = link.indexOf('/')
        if (end <= 0) return url
        return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1)
    }

    /** URL 末段去 .py、去查询串当站点名(URL 里常是百分号编码,解回原文);取不到由 build 兜底 */
    private fun nameFromUrl(url: String): String {
        val tail = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val raw = if (tail.endsWith(".py", ignoreCase = true)) tail.dropLast(3) else tail
        return try {
            // 路径段里的 + 是字面加号,只有 query 里 URLDecoder 才该把它当空格
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (th: Throwable) {
            raw
        }
    }

    /** JSON 字符串字面量转义(站点名来自文件名/URL,可能含引号/反斜杠/控制符) */
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
