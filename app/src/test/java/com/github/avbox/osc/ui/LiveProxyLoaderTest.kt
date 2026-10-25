package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProxyLoaderTest {

    @Test
    fun acceptsSupportedSchemes() {
        assertTrue(LiveProxyLoader.isValidProxyUrl("http://a.tv/live.txt"))
        assertTrue(LiveProxyLoader.isValidProxyUrl("https://a.tv/live.txt"))
        assertTrue(LiveProxyLoader.isValidProxyUrl("rtsp://a.tv/ch1"))
        assertTrue(LiveProxyLoader.isValidProxyUrl("rtmp://a.tv/ch1"))
        assertTrue(LiveProxyLoader.isValidProxyUrl("rtp://a.tv/ch1"))
    }

    @Test
    fun ignoresCaseAndSurroundingSpaces() {
        assertTrue(LiveProxyLoader.isValidProxyUrl("  HTTP://A.TV/live.txt  "))
        assertTrue(LiveProxyLoader.isValidProxyUrl("Rtmp://a.tv/ch1"))
    }

    @Test
    fun rejectsEmptyAndNull() {
        assertFalse(LiveProxyLoader.isValidProxyUrl(null))
        assertFalse(LiveProxyLoader.isValidProxyUrl(""))
        assertFalse(LiveProxyLoader.isValidProxyUrl("   "))
    }

    @Test
    fun rejectsUnsupportedOrMalformedSchemes() {
        assertFalse(LiveProxyLoader.isValidProxyUrl("ftp://a.tv/live.txt"))
        assertFalse(LiveProxyLoader.isValidProxyUrl("a.tv/live.txt"))
        assertFalse(LiveProxyLoader.isValidProxyUrl("127.0.0.1:9978/proxy"))
        assertFalse(LiveProxyLoader.isValidProxyUrl("http:/a.tv/live.txt"))
        assertFalse(LiveProxyLoader.isValidProxyUrl("httpx://a.tv/live.txt"))
    }
}
