package com.github.avbox.osc.net

object ProxyResponsePolicy {

    const val HTTP_OK = 200

    const val HTTP_SERVICE_UNAVAILABLE = 503

    private const val M3U8_MIME = "mpegurl"

    fun isM3u8Mime(mime: String?): Boolean =
        mime != null && mime.contains(M3U8_MIME, ignoreCase = true)

    fun effectiveStatus(status: Int, mime: String?, firstByte: Int): Int =
        if (status == HTTP_OK && firstByte < 0 && isM3u8Mime(mime)) HTTP_SERVICE_UNAVAILABLE else status
}
