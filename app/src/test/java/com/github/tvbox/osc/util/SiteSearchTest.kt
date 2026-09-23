package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.SourceBean
import org.junit.Assert.assertEquals
import org.junit.Test

/** 站点查找口径:名/地址都命中、忽略大小写、空词原样返回、保持原顺序 */
class SiteSearchTest {

    private fun site(key: String, name: String): SourceBean = SourceBean().apply {
        this.key = key
        this.name = name
    }

    private val sites = listOf(
        site("https://a.example.com/api", "量子资源"),
        site("https://b.example.com/tianyi", "天翼影视"),
        site("csp_TianYi", "TianYi Backup"),
    )

    @Test
    fun filter_matchesNameOrKey() {
        // 仓里同名子源只给名字分不清,所以地址片段也要能命中
        assertEquals(listOf("天翼影视"), SiteSearch.filter(sites, "天翼").map { it.name })
        assertEquals(listOf("天翼影视"), SiteSearch.filter(sites, "b.example").map { it.name })
    }

    @Test
    fun filter_ignoresCaseAndSurroundingSpaces() {
        assertEquals(listOf("天翼影视", "TianYi Backup"), SiteSearch.filter(sites, " tianyi ").map { it.name })
    }

    @Test
    fun filter_returnsInputForBlankQuery() {
        assertEquals(sites, SiteSearch.filter(sites, ""))
        assertEquals(sites, SiteSearch.filter(sites, "   "))
    }

    @Test
    fun filter_keepsOriginalOrderAndDropsNonMatches() {
        assertEquals(emptyList<String>(), SiteSearch.filter(sites, "不存在的站点").map { it.name })
        assertEquals(listOf("量子资源", "天翼影视"), SiteSearch.filter(sites, "example").map { it.name })
    }
}
