package com.github.tvbox.osc.sourcedata

import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.ui.activity.DetailResponseGuard
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResultParserRoutingTest {

    private val gson = Gson()

    private class RecordingChannel : SourceChannel<AbsXml?>() {
        val posted = ArrayList<AbsXml?>()
        override fun postValue(value: AbsXml?) {
            posted.add(value)
        }
    }

    private val payload =
        """{"list":[{"vod_id":"1","vod_name":"测试片","vod_play_from":"线路甲","vod_play_url":"第1集${'$'}http://a.example/1.m3u8"}]}"""

    private fun newParser(search: SourceChannel<AbsXml?>, detail: SourceChannel<AbsXml?>) =
        SourceResultParser(gson, search, detail, PushDetailResolver(gson, detail))

    @Test
    fun searchChannelReceivesSearchResult() {
        val search = RecordingChannel()
        val detail = RecordingChannel()
        runBlocking { newParser(search, detail).json(search, payload, "src", "token") }
        assertEquals("搜索结果应统一经 searchResult 通道投递", 1, search.posted.size)
        assertTrue("载荷应为 AbsXml", search.posted[0] is AbsXml)
    }

    @Test
    fun detailChannelReceivesParsedDetail() {
        val search = RecordingChannel()
        val detail = RecordingChannel()
        runBlocking { newParser(search, detail).json(detail, payload, "src") }
        assertEquals(1, detail.posted.size)
        val data = detail.posted[0]!!
        val videos = data.movie!!.videoList!!
        assertEquals(1, videos.size)
        assertEquals("测试片", videos[0].name)
        assertEquals("src", data.sourceKey)
    }

    @Test
    fun plainChannelReceivesValue() {
        val search = RecordingChannel()
        val detail = RecordingChannel()
        val list = RecordingChannel()
        runBlocking { newParser(search, detail).json(list, payload, "src") }
        assertEquals(1, list.posted.size)
        assertEquals("src", list.posted[0]!!.sourceKey)
    }

    @Test
    fun detailTokenSurvivesFlowDelivery() = runBlocking {
        val search = SourceChannel<AbsXml?>()
        val detail = SourceChannel<AbsXml?>()
        newParser(search, detail).json(detail, payload, "src", "", 42)
        val data = withTimeout(1000) { detail.flow.first() }
        assertEquals(42, data?.detailToken)
        assertTrue("当前代次应被守卫采信", DetailResponseGuard.isCurrent(42, data?.detailToken))
        assertTrue("上一代必须被守卫丢弃", !DetailResponseGuard.isCurrent(41, data?.detailToken))
    }
}
