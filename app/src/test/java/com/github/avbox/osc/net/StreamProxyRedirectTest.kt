package com.github.avbox.osc.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamProxyRedirectTest {

    private val request = "http://v.qq.com/a/b/index.m3u8".toHttpUrl()

    @Test
    fun resolveRedirectLocation_resolvesRelativeLocation() {
        assertEquals(
            "http://v.qq.com/vod/play/index.m3u8",
            StreamProxy.resolveRedirectLocation(request, "/vod/play/index.m3u8")
        )
        assertEquals(
            "http://v.qq.com/a/b/seg.m3u8",
            StreamProxy.resolveRedirectLocation(request, "seg.m3u8")
        )
        assertEquals(
            "http://cdn.example.com/a.m3u8",
            StreamProxy.resolveRedirectLocation(request, "//cdn.example.com/a.m3u8")
        )
    }

    @Test
    fun resolveRedirectLocation_keepsAbsoluteLocation() {
        assertEquals(
            "https://cdn.example.com/a.m3u8?sig=1",
            StreamProxy.resolveRedirectLocation(request, "https://cdn.example.com/a.m3u8?sig=1")
        )
    }

    @Test
    fun resolveRedirectLocation_returnsNullWhenUnusable() {
        assertNull(StreamProxy.resolveRedirectLocation(request, null))
        assertNull(StreamProxy.resolveRedirectLocation(request, ""))
        assertNull(StreamProxy.resolveRedirectLocation(request, "ftp://cdn.example.com/a.m3u8"))
    }

    @Test
    fun joinUrl_returnsOriginalUrlInsteadOfNull() {
        assertEquals("seg 1.ts", StreamProxy.joinUrl("http://v.qq.com/a/index.m3u8", "seg 1.ts", "media", emptyMap<String, String>()))
        assertEquals("data:text/plain;base64,AAAA", StreamProxy.joinUrl("http://v.qq.com/a/index.m3u8", "data:text/plain;base64,AAAA", "media", emptyMap<String, String>()))
    }
}
