package com.github.avbox.osc.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyHeaderPolicyTest {

    @Test
    fun keepsSeekRelatedHeaders() {
        val filtered = ProxyHeaderPolicy.filter(linkedMapOf(
            "Content-Range" to "bytes 0-99/100",
            "Accept-Ranges" to "bytes",
            "Content-Length" to "100",
            "Cache-Control" to "no-cache",
            "Content-Encoding" to "gzip"
        ))
        assertEquals(5, filtered.size)
        assertEquals("bytes 0-99/100", filtered["Content-Range"])
    }

    @Test
    fun dropsHopByHopAndCookieHeaders() {
        val filtered = ProxyHeaderPolicy.filter(linkedMapOf(
            "Transfer-Encoding" to "chunked",
            "Connection" to "keep-alive",
            "Set-Cookie" to "a=b",
            "content-range" to "bytes 0-9/10"
        ))
        assertEquals(mapOf("content-range" to "bytes 0-9/10"), filtered)
    }

    @Test
    fun emptyStaysEmpty() {
        assertTrue(ProxyHeaderPolicy.filter(emptyMap()).isEmpty())
    }
}
