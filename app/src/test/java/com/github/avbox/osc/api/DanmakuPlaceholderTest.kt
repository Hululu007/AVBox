package com.github.avbox.osc.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPlaceholderTest {

    private val template = "https://d/api?name={name}&episode={episode}"

    @Test
    fun placeholdersRoundTripThroughUrlParsing() {
        val url = DanmakuApi.fillPlaceholders(template, "Tom & Jerry", "1#2").toHttpUrl()
        assertEquals("Tom & Jerry", url.queryParameter("name"))
        assertEquals("1#2", url.queryParameter("episode"))
    }

    @Test
    fun chineseTitleRoundTrips() {
        val url = DanmakuApi.fillPlaceholders(template, "繁花", "第3集").toHttpUrl()
        assertEquals("繁花", url.queryParameter("name"))
        assertEquals("第3集", url.queryParameter("episode"))
    }

    @Test
    fun hashInPathPlaceholderNoLongerStartsFragment() {
        val url = DanmakuApi.fillPlaceholders("https://d/api/{name}", "a#b", "").toHttpUrl()
        assertTrue(url.toString().contains("%23"))
        assertEquals("a#b", url.pathSegments.last())
    }

    @Test
    fun spaceInPathPlaceholderUsesPercent20() {
        val url = DanmakuApi.fillPlaceholders("https://d/api/{name}", "Tom Jerry", "").toHttpUrl()
        assertTrue(url.toString().contains("%20"))
        assertEquals("Tom Jerry", url.pathSegments.last())
    }
}
