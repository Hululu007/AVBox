package com.github.avbox.osc.util.net

import okhttp3.OkHttp
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRequestTest {

    @Test
    fun build_injectsDefaultHeaders() {
        val request = build("https://example.com/api")
        assertEquals("okhttp/" + OkHttp.VERSION, request.header("User-Agent"))
        assertNotNull(request.header("Accept-Language"))
    }

    @Test
    fun build_explicitUserAgentReplacesDefault() {
        val request = build("https://example.com/api") {
            headers("User-Agent", "custom-agent")
        }
        assertEquals("custom-agent", request.header("User-Agent"))
        assertEquals(1, request.headers("User-Agent").size)
    }

    @Test
    fun build_headersMapApplied() {
        val request = build("https://example.com/api") {
            headers(mapOf("Referer" to "https://ref.example.com/", "X-Token" to "abc"))
        }
        assertEquals("https://ref.example.com/", request.header("Referer"))
        assertEquals("abc", request.header("X-Token"))
    }

    @Test
    fun build_paramsGoToQuery() {
        val request = build("https://example.com/api") {
            params("wd", "hello")
            params("pg", "2")
        }
        assertEquals("hello", request.url.queryParameter("wd"))
        assertEquals("2", request.url.queryParameter("pg"))
    }

    @Test
    fun build_sameKeyReplacesValue() {
        val request = build("https://example.com/api") {
            params("pg", "1")
            params("pg", "2")
        }
        assertEquals("2", request.url.queryParameter("pg"))
        assertEquals(1, request.url.queryParameterValues("pg").size)
    }

    @Test
    fun build_nullValueSkipped() {
        val request = build("https://example.com/api") {
            params("ac", null)
        }
        assertNull(request.url.queryParameter("ac"))
    }

    @Test
    fun build_keepsExistingQuery() {
        val request = build("https://example.com/api?t=1") {
            params("wd", "x")
        }
        assertEquals("1", request.url.queryParameter("t"))
        assertEquals("x", request.url.queryParameter("wd"))
    }

    @Test
    fun build_encodesParamValuesAsUtf8() {
        val request = build("https://example.com/api") {
            params("wd", "中文")
        }
        assertEquals("wd=%E4%B8%AD%E6%96%87", request.url.encodedQuery)
    }

    @Test
    fun build_encodesSpaceAsPercent20() {
        val request = build("https://example.com/api") {
            params("wd", "a b")
        }
        assertEquals("wd=a%20b", request.url.encodedQuery)
    }

    private fun build(url: String, init: HttpRequest.() -> Unit = {}): Request {
        return HttpRequest(url).apply(init).build()
    }
}
