package com.github.tvbox.osc.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义弹幕 API 占位符的编码回归。
 *
 * 存在理由:原先裸字符串替换,剧名里的 & 会被解析成额外的空参数、# 之后整段变 fragment,
 * 服务端拿不到正确的 name/episode,弹幕匹配静默失败。
 */
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
        // query 里 + 与 %20 等价,path 里 + 是字面量 ⇒ 一律 %20 才不会两处只对一边
        val url = DanmakuApi.fillPlaceholders("https://d/api/{name}", "Tom Jerry", "").toHttpUrl()
        assertTrue(url.toString().contains("%20"))
        assertEquals("Tom Jerry", url.pathSegments.last())
    }
}
