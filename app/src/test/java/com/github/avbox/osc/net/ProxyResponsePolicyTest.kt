package com.github.avbox.osc.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyResponsePolicyTest {

    private val m3u8 = "application/vnd.apple.mpegurl"

    @Test
    fun emptyM3u8BecomesUnavailable() {
        assertEquals(503, ProxyResponsePolicy.effectiveStatus(200, m3u8, -1))
        assertEquals(503, ProxyResponsePolicy.effectiveStatus(200, "application/vnd.apple.mpegURL", -1))
    }

    @Test
    fun servedM3u8KeepsStatus() {
        assertEquals(200, ProxyResponsePolicy.effectiveStatus(200, m3u8, '#'.code))
    }

    @Test
    fun otherMimeKeepsStatusWhenEmpty() {
        assertEquals(200, ProxyResponsePolicy.effectiveStatus(200, "video/mp2t", -1))
        assertEquals(200, ProxyResponsePolicy.effectiveStatus(200, null, -1))
    }

    @Test
    fun nonOkStatusIsUntouched() {
        assertEquals(404, ProxyResponsePolicy.effectiveStatus(404, m3u8, -1))
    }

    @Test
    fun m3u8MimeMatching() {
        assertTrue(ProxyResponsePolicy.isM3u8Mime(m3u8))
        assertFalse(ProxyResponsePolicy.isM3u8Mime("video/mp2t"))
        assertFalse(ProxyResponsePolicy.isM3u8Mime(null))
    }
}
