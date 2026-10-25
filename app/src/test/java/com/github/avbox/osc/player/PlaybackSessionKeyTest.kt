package com.github.avbox.osc.player

import com.github.avbox.osc.bean.VodInfo
import java.util.LinkedHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackSessionKeyTest {

    private fun info(
        reverseSort: Boolean,
        playIndex: Int,
        series: List<VodInfo.VodSeries>,
    ): VodInfo {
        val map = LinkedHashMap<String?, MutableList<VodInfo.VodSeries>>()
        map["BB蓝光"] = series.toMutableList()
        return VodInfo(
            id = "93687",
            playFlag = "BB蓝光",
            playIndex = playIndex,
            reverseSort = reverseSort,
            seriesMap = map,
        )
    }

    @Test
    fun reversedOrderSameIndexProducesDifferentKey() {
        val ascending = listOf(
            VodInfo.VodSeries("第1集", "u1"),
            VodInfo.VodSeries("第47集", "u47"),
        )
        val descending = ascending.reversed()
        val played = PlaybackSession(info(false, 0, ascending), "s", false)
        val clicked = PlaybackSession(info(true, 0, descending), "s", false)

        assertEquals(played.vodKey(), clicked.vodKey())
        assertNotEquals(played.playbackKey(), clicked.playbackKey())
    }

    @Test
    fun sameEpisodeKeepsSameKey() {
        val series = listOf(VodInfo.VodSeries("第1集", "u1"), VodInfo.VodSeries("第2集", "u2"))
        val first = PlaybackSession(info(false, 1, series), "s", false)
        val again = PlaybackSession(info(false, 1, series.toMutableList()), "s", false)

        assertEquals(first.playbackKey(), again.playbackKey())
    }
}
