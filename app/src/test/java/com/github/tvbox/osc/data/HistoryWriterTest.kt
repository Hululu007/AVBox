package com.github.tvbox.osc.data

import com.github.tvbox.osc.bean.VodInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryWriterTest {

    @Test
    fun resolveWriteKey_prefersSessionKeyOverVodSource() {
        val info = VodInfo(sourceKey = "剧圈99")
        assertEquals("当前源", HistoryWriter.resolveWriteKey("当前源", info))
    }

    @Test
    fun resolveWriteKey_fallsBackToVodSourceWhenSessionKeyBlank() {
        val info = VodInfo(sourceKey = "剧圈99")
        assertEquals("剧圈99", HistoryWriter.resolveWriteKey("", info))
        assertEquals("剧圈99", HistoryWriter.resolveWriteKey(null, info))
        assertEquals("", HistoryWriter.resolveWriteKey(null, VodInfo()))
    }
}
