package com.github.tvbox.osc.util

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class OkGoHelperDohTest {

    private val tencent = "https://doh.pub/dns-query"
    private val alidns = "https://dns.alidns.com/dns-query"
    private val doh360 = "https://doh.360.cn/dns-query"

    private fun dohArray(vararg items: Pair<String, String>): JsonArray {
        val array = JsonArray()
        items.forEach { (name, url) -> array.add(JsonParser.parseString("""{"name":"$name","url":"$url"}""")) }
        return array
    }

    private val builtin3 = dohArray("腾讯" to tencent, "阿里" to alidns, "360" to doh360)

    @Test
    fun findsBuiltinByUrlNoMatterWhatFollows() {
        val withApiItems = dohArray(
            "腾讯" to tencent,
            "阿里" to alidns,
            "360" to doh360,
            "接口甲" to "https://a.example/dns-query",
            "接口乙" to "https://b.example/dns-query",
        )
        assertEquals(0, OkGoHelper.indexOfDohUrl(withApiItems, tencent))
        assertEquals(1, OkGoHelper.indexOfDohUrl(withApiItems, alidns))
        assertEquals(0, OkGoHelper.indexOfDohUrl(builtin3, tencent))
        assertEquals(1, OkGoHelper.indexOfDohUrl(builtin3, alidns))
        assertEquals(2, OkGoHelper.indexOfDohUrl(builtin3, doh360))
    }

    @Test
    fun followsUrlWhenApiItemsChange() {
        val reordered = dohArray(
            "腾讯" to tencent,
            "阿里" to alidns,
            "360" to doh360,
            "接口乙" to "https://b.example/dns-query",
        )
        assertEquals(3, OkGoHelper.indexOfDohUrl(reordered, "https://b.example/dns-query"))
    }

    @Test
    fun returnsMinusOneWhenSelectionDisappeared() {
        assertEquals(-1, OkGoHelper.indexOfDohUrl(builtin3, "https://gone.example/dns-query"))
    }

    @Test
    fun returnsMinusOneForMissingOrEmptyUrl() {
        assertEquals(-1, OkGoHelper.indexOfDohUrl(builtin3, ""))
        assertEquals(-1, OkGoHelper.indexOfDohUrl(builtin3, null))
        assertEquals(-1, OkGoHelper.indexOfDohUrl(null, tencent))
    }

    @Test
    fun fallsBackToNameWhenItemHasNoUrl() {
        val namedOnly = JsonParser.parseString("""[{"name":"https://named.example/dns-query"}]""").asJsonArray
        assertEquals(0, OkGoHelper.indexOfDohUrl(namedOnly, "https://named.example/dns-query"))
    }
}
