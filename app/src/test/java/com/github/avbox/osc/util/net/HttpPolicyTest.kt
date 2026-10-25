package com.github.avbox.osc.util.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

class HttpPolicyTest {

    @Test
    fun shouldFail_for404AndServerErrors() {
        assertTrue(HttpPolicy.shouldFail(404))
        assertTrue(HttpPolicy.shouldFail(500))
        assertTrue(HttpPolicy.shouldFail(503))
    }

    @Test
    fun shouldFail_notForOtherCodes() {
        assertFalse(HttpPolicy.shouldFail(200))
        assertFalse(HttpPolicy.shouldFail(204))
        assertFalse(HttpPolicy.shouldFail(301))
        assertFalse(HttpPolicy.shouldFail(400))
        assertFalse(HttpPolicy.shouldFail(403))
        assertFalse(HttpPolicy.shouldFail(499))
    }

    @Test
    fun shouldRetry_onlySocketTimeoutWithinLimit() {
        val timeout = SocketTimeoutException("read timed out")
        assertTrue(HttpPolicy.shouldRetry(0, timeout))
        assertTrue(HttpPolicy.shouldRetry(1, timeout))
        assertTrue(HttpPolicy.shouldRetry(2, timeout))
        assertFalse(HttpPolicy.shouldRetry(3, timeout))
        assertFalse(HttpPolicy.shouldRetry(0, ConnectException("connection refused")))
        assertFalse(HttpPolicy.shouldRetry(0, UnknownHostException("unknown host")))
    }

    @Test
    fun buildUrl_appendsParams() {
        val url = HttpPolicy.buildUrl("https://example.com/api", linkedMapOf("ac" to "detail", "pg" to "2"))
        assertEquals("https://example.com/api?ac=detail&pg=2", url.toString())
    }

    @Test
    fun buildUrl_keepsExistingQuery() {
        val url = HttpPolicy.buildUrl("https://example.com/api?t=1", linkedMapOf("wd" to "abc"))
        assertEquals("https://example.com/api?t=1&wd=abc", url.toString())
    }

    @Test
    fun buildUrl_withoutParamsKeepsUrl() {
        val url = HttpPolicy.buildUrl("https://example.com/api", emptyMap())
        assertEquals("https://example.com/api", url.toString())
    }

    @Test
    fun acceptLanguage_followsOkGoShape() {
        assertEquals("zh-CN,zh;q=0.8", HttpPolicy.acceptLanguage(Locale.forLanguageTag("zh-CN")))
        assertEquals("en", HttpPolicy.acceptLanguage(Locale.forLanguageTag("en")))
    }
}
