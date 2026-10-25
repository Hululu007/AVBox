package com.github.avbox.osc.ui.page

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseSubscribeTest {

    @Test
    fun stripsLeadingControlCharFromUrl() {
        val source = parseSubscribe("王二小\t\u0013https://9280.kstore.vip/aiwex.json")
        assertEquals("王二小", source.name)
        assertEquals("https://9280.kstore.vip/aiwex.json", source.url)
    }

    @Test
    fun stripsControlCharsFromPlainValueWithoutSeparator() {
        val source = parseSubscribe("\u0013https://9280.kstore.vip/aiwex.json")
        assertEquals("https://9280.kstore.vip/aiwex.json", source.name)
        assertEquals("https://9280.kstore.vip/aiwex.json", source.url)
    }

    @Test
    fun keepsInternalSpacesAndNonAsciiText() {
        val source = parseSubscribe("我的 源\thttps://例 子.com/x.json")
        assertEquals("我的 源", source.name)
        assertEquals("https://例 子.com/x.json", source.url)
    }

    @Test
    fun stripsTrailingTabFromUrl() {
        val source = parseSubscribe("name\thttps://example.com/x.json\t")
        assertEquals("https://example.com/x.json", source.url)
    }

    @Test
    fun stripsDelAndC1Controls() {
        val source = parseSubscribe("na\u007Fme\thttps://exa\u0085mple.com/x.json")
        assertEquals("name", source.name)
        assertEquals("https://example.com/x.json", source.url)
    }
}
