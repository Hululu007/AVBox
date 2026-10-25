package com.github.avbox.osc.ui

import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.bean.LiveChannelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveChannelNavigatorTest {

    private fun group(groupIndex: Int, password: String = "", channelNames: List<String>): LiveChannelGroup {
        val group = LiveChannelGroup()
        group.groupIndex = groupIndex
        group.groupName = "G$groupIndex"
        group.groupPassword = password
        val items = ArrayList<LiveChannelItem>()
        channelNames.forEachIndexed { index, name ->
            val item = LiveChannelItem()
            item.channelIndex = index
            item.channelName = name
            items.add(item)
        }
        group.liveChannels = items
        return group
    }

    private fun next(
        groups: List<LiveChannelGroup>,
        current: IntArray,
        direction: Int,
        crossGroup: Boolean,
        channelsOf: (Int) -> List<LiveChannelItem>? = { index -> groups.getOrNull(index)?.liveChannels },
    ): List<Int> = LiveChannelNavigator
        .nextPosition(groups, current[0], current[1], direction, crossGroup, channelsOf)
        .toList()

    @Test
    fun forward_movesWithinGroup() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1", "CCTV2", "CCTV3")))
        assertEquals(listOf(0, 1), next(groups, intArrayOf(0, 0), 1, crossGroup = false))
        assertEquals(listOf(0, 2), next(groups, intArrayOf(0, 1), 1, crossGroup = false))
    }

    @Test
    fun backward_movesWithinGroup() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1", "CCTV2", "CCTV3")))
        assertEquals(listOf(0, 1), next(groups, intArrayOf(0, 2), -1, crossGroup = false))
    }

    @Test
    fun forward_atGroupEnd_wrapsToFirstChannelWhenCrossGroupOff() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2", "CCTV3")),
            group(1, channelNames = listOf("湖南卫视", "浙江卫视")),
        )
        assertEquals(listOf(0, 0), next(groups, intArrayOf(0, 2), 1, crossGroup = false))
    }

    @Test
    fun backward_atGroupStart_wrapsToLastChannelWhenCrossGroupOff() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1", "CCTV2", "CCTV3")))
        assertEquals(listOf(0, 2), next(groups, intArrayOf(0, 0), -1, crossGroup = false))
    }

    @Test
    fun forward_crossGroupEntersNextUnlockedGroup() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2")),
            group(1, channelNames = listOf("湖南卫视", "浙江卫视")),
        )
        assertEquals(listOf(1, 0), next(groups, intArrayOf(0, 1), 1, crossGroup = true))
    }

    @Test
    fun forward_crossGroupSkipsPasswordGroup() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2")),
            group(1, password = "1234", channelNames = listOf("付费台")),
            group(2, channelNames = listOf("凤凰卫视")),
        )
        assertEquals(listOf(2, 0), next(groups, intArrayOf(0, 1), 1, crossGroup = true))
    }

    @Test
    fun forward_crossGroupStaysInCurrentGroupWhenAllOthersLocked() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2")),
            group(1, password = "1234", channelNames = listOf("付费台")),
        )
        assertEquals(listOf(0, 0), next(groups, intArrayOf(0, 1), 1, crossGroup = true))
    }

    @Test
    fun forward_crossGroupWithSingleGroupStaysOnFirstChannel() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1", "CCTV2")))
        assertEquals(listOf(0, 0), next(groups, intArrayOf(0, 1), 1, crossGroup = true))
    }

    @Test
    fun backward_crossGroupStaysInCurrentGroupWhenAllOthersLocked() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2", "CCTV3")),
            group(1, password = "1234", channelNames = listOf("付费台")),
        )
        assertEquals(listOf(0, 2), next(groups, intArrayOf(0, 0), -1, crossGroup = true))
    }

    @Test
    fun backward_crossGroupEntersLastChannelOfPreviousUnlockedGroup() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2", "CCTV3")),
            group(1, channelNames = listOf("湖南卫视", "浙江卫视")),
        )
        assertEquals(listOf(0, 2), next(groups, intArrayOf(1, 0), -1, crossGroup = true))
    }

    @Test
    fun backward_crossGroupSkipsPasswordGroup() {
        val groups = listOf(
            group(0, password = "1234", channelNames = listOf("付费台")),
            group(1, channelNames = listOf("湖南卫视", "浙江卫视")),
            group(2, channelNames = listOf("凤凰卫视", "翡翠台")),
        )
        assertEquals(listOf(1, 1), next(groups, intArrayOf(2, 0), -1, crossGroup = true))
    }

    @Test
    fun backward_crossGroupWrapsFromFirstGroupToLast() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1")),
            group(1, channelNames = listOf("湖南卫视")),
            group(2, channelNames = listOf("凤凰卫视", "翡翠台")),
        )
        assertEquals(listOf(2, 1), next(groups, intArrayOf(0, 0), -1, crossGroup = true))
    }

    @Test
    fun forward_lockedGroupHasNoVisibleChannel_staysOnFirstChannel() {
        val groups = listOf(group(0, password = "1234", channelNames = listOf("付费台1", "付费台2")))
        assertEquals(listOf(0, 0), next(groups, intArrayOf(0, 0), 1, crossGroup = false) { emptyList() })
    }

    @Test
    fun forward_confirmedPasswordGroupMovesNormally() {
        val groups = listOf(group(0, password = "1234", channelNames = listOf("付费台1", "付费台2")))
        assertEquals(listOf(0, 1), next(groups, intArrayOf(0, 0), 1, crossGroup = false) { groups[0].liveChannels })
    }

    @Test
    fun firstChannelByName_returnsBeanIndicesNotListPositions() {
        val groups = listOf(
            group(7, channelNames = listOf("湖南卫视", "浙江卫视")),
            group(9, channelNames = listOf("CCTV1", "CCTV2")),
        )
        assertEquals(listOf(9, 0), LiveChannelNavigator.firstChannelByName(groups, "cctv1") { false }!!.toList())
    }

    @Test
    fun firstChannelByName_matchesSubstringAndIgnoresCase() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1 综合", "CCTV5+")))
        assertEquals(listOf(0, 0), LiveChannelNavigator.firstChannelByName(groups, "cctv1") { false }!!.toList())
        assertEquals(listOf(0, 1), LiveChannelNavigator.firstChannelByName(groups, "5+") { false }!!.toList())
    }

    @Test
    fun firstChannelByName_skipsGroupNeedingPassword() {
        val groups = listOf(
            group(0, password = "1234", channelNames = listOf("CCTV1")),
            group(1, channelNames = listOf("CCTV1 综合")),
        )
        assertEquals(listOf(1, 0), LiveChannelNavigator.firstChannelByName(groups, "CCTV1") { it == 0 }!!.toList())
        assertEquals(listOf(0, 0), LiveChannelNavigator.firstChannelByName(groups, "CCTV1") { false }!!.toList())
    }

    @Test
    fun firstChannelByName_returnsNullForEmptyKeywordOrNoMatch() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1")))
        assertNull(LiveChannelNavigator.firstChannelByName(groups, null) { false })
        assertNull(LiveChannelNavigator.firstChannelByName(groups, "") { false })
        assertNull(LiveChannelNavigator.firstChannelByName(groups, "不存在的台") { false })
    }

    @Test
    fun firstUnlockedGroupIndex_returnsBeanGroupIndex() {
        val groups = listOf(
            group(5, password = "1234", channelNames = listOf("付费台")),
            group(6, channelNames = listOf("湖南卫视")),
        )
        assertEquals(6, LiveChannelNavigator.firstUnlockedGroupIndex(groups))
    }

    @Test
    fun firstUnlockedGroupIndex_allLockedOrEmpty() {
        assertEquals(-1, LiveChannelNavigator.firstUnlockedGroupIndex(emptyList()))
        assertEquals(
            -1,
            LiveChannelNavigator.firstUnlockedGroupIndex(listOf(group(0, password = "1234", channelNames = listOf("付费台")))),
        )
    }
}
