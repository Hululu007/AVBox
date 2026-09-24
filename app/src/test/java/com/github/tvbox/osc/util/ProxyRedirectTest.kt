package com.github.tvbox.osc.util

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 相对 Location 与 m3u8 改写兜底的回归。
 *
 * 存在理由:302 用相对 Location 很常见,原实现直接透传,下游 new Request.Builder().url() 抛
 * IllegalArgumentException 让 go=bom 返回 500;改写失败原实现返回 null,会把字面量 null 写进播放列表。
 */
class ProxyRedirectTest {

    private val request = "http://v.qq.com/a/b/index.m3u8".toHttpUrl()

    @Test
    fun resolveRedirectLocation_resolvesRelativeLocation() {
        assertEquals(
            "http://v.qq.com/vod/play/index.m3u8",
            Proxy.resolveRedirectLocation(request, "/vod/play/index.m3u8")
        )
        assertEquals(
            "http://v.qq.com/a/b/seg.m3u8",
            Proxy.resolveRedirectLocation(request, "seg.m3u8")
        )
        assertEquals(
            "http://cdn.example.com/a.m3u8",
            Proxy.resolveRedirectLocation(request, "//cdn.example.com/a.m3u8")
        )
    }

    @Test
    fun resolveRedirectLocation_keepsAbsoluteLocation() {
        assertEquals(
            "https://cdn.example.com/a.m3u8?sig=1",
            Proxy.resolveRedirectLocation(request, "https://cdn.example.com/a.m3u8?sig=1")
        )
    }

    @Test
    fun resolveRedirectLocation_returnsNullWhenUnusable() {
        // 返回 null 时调用方回落原 URL,不会把不可用的 Location 拼进请求
        assertNull(Proxy.resolveRedirectLocation(request, null))
        assertNull(Proxy.resolveRedirectLocation(request, ""))
        assertNull(Proxy.resolveRedirectLocation(request, "ftp://cdn.example.com/a.m3u8"))
    }

    @Test
    fun joinUrl_returnsOriginalUrlInsteadOfNull() {
        assertEquals("seg 1.ts", Proxy.joinUrl("http://v.qq.com/a/index.m3u8", "seg 1.ts", "media", emptyMap<String, String>()))
        assertEquals("data:text/plain;base64,AAAA", Proxy.joinUrl("http://v.qq.com/a/index.m3u8", "data:text/plain;base64,AAAA", "media", emptyMap<String, String>()))
    }
}
