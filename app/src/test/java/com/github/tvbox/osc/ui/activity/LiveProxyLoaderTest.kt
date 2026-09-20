package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiveProxyLoader.isValidProxyUrl] 的纯函数单测(纯 JVM,无需 Robolectric)。
 *
 * 选点理由:这个判断是"代理源加载"的第一道闸门 —— 判定过松会把 base64 解出来的垃圾当地址去请求,
 * 表现为"直播页一直空";判定过严会把 rtsp/rtmp 这类非 http 的直播源直接拦掉,表现为"这个源永远加载不出来"。
 * 两种都不报错,只能靠单测兜。
 */
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
        // 少一个斜杠、或 scheme 写错都不算合法地址
        assertFalse(LiveProxyLoader.isValidProxyUrl("http:/a.tv/live.txt"))
        assertFalse(LiveProxyLoader.isValidProxyUrl("httpx://a.tv/live.txt"))
    }
}
