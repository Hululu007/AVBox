package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.bean.LiveSettingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSettingsSnapshotTest {

    private class FakeSource(
        private val groups: List<LiveSettingGroup>,
        private val scale: Int = 0,
        private val type: Int = 0,
        private val timeout: Int = 1,
        private val checked: Set<Int> = emptySet(),
        private val groupIndex: Int = 0,
        private val configIndex: Int = 0,
        private val lineMode: Boolean = false,
    ) : LiveSettingsSource {
        override fun settingGroups(): List<LiveSettingGroup> = groups

        override fun playerScale(): Int = scale

        override fun playerType(): Int = type

        override fun connectTimeoutIndex(): Int = timeout

        override fun switchChecked(position: Int): Boolean = checked.contains(position)

        override fun liveGroupIndex(): Int = groupIndex

        override fun configIndex(): Int = configIndex

        override fun isLiveApiLineMode(): Boolean = lineMode
    }

    private fun group(groupIndex: Int, itemNames: List<String>, startIndex: Int = 0): LiveSettingGroup {
        val group = LiveSettingGroup()
        group.groupIndex = groupIndex
        group.groupName = "G$groupIndex"
        val items = ArrayList<LiveSettingItem>()
        itemNames.forEachIndexed { index, name ->
            val item = LiveSettingItem()
            item.itemIndex = startIndex + index
            item.itemName = name
            items.add(item)
        }
        group.liveSettingItems = items
        return group
    }

    private fun channel(sourceNum: Int, urlCount: Int = sourceNum): LiveChannelItem {
        val item = LiveChannelItem()
        item.channelName = "CCTV1"
        item.sourceNum = sourceNum
        item.channelUrls = ArrayList<String>().apply { repeat(urlCount) { add("http://example.com/$it") } }
        return item
    }

    private fun allGroups(): List<LiveSettingGroup> = listOf(
        group(0, listOf("线路一", "线路二")),
        group(1, listOf("默认", "16:9")),
        group(2, listOf("硬解", "软解")),
        group(3, listOf("3 秒", "5 秒")),
        group(4, listOf("显示时间", "显示网速")),
        group(5, listOf("源一", "源二")),
        group(6, listOf("跟随点播源", "配置A")),
    )

    @Test
    fun withoutChannelSource_dropsFirstThreeGroups() {
        val snapshot = LiveSettingsSnapshot.of(FakeSource(allGroups()), null, -1)
        assertEquals(listOf(3, 4, 5, 6), snapshot.map { it.groupIndex })
    }

    @Test
    fun withChannelSource_keepsAllGroups() {
        val snapshot = LiveSettingsSnapshot.of(FakeSource(allGroups()), channel(sourceNum = 2), 1)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), snapshot.map { it.groupIndex })
    }

    @Test
    fun channelWithoutValidSourceIndex_countsAsNoSource() {
        val snapshot = LiveSettingsSnapshot.of(FakeSource(allGroups()), channel(sourceNum = 2), 5)
        assertEquals(listOf(3, 4, 5, 6), snapshot.map { it.groupIndex })
    }

    @Test
    fun groupWithoutItems_isDropped() {
        val bare = LiveSettingGroup()
        bare.groupIndex = 3
        bare.groupName = "G3"
        val snapshot = LiveSettingsSnapshot.of(FakeSource(listOf(bare)), null, -1)
        assertEquals(emptyList<Int>(), snapshot.map { it.groupIndex })
    }

    @Test
    fun switchGroup_takesCheckedFromSourceAndNeverSelected() {
        val snapshot = LiveSettingsSnapshot.of(
            FakeSource(listOf(group(4, listOf("显示时间", "显示网速"))), checked = setOf(1)),
            null, -1,
        )
        val switchGroup = snapshot.single()
        assertTrue(switchGroup.switchRow)
        assertEquals(listOf(false, true), switchGroup.items.map { it.checked })
        assertFalse(switchGroup.items.any { it.selected })
    }

    @Test
    fun selectedIndex_followsChannelSourceIndex() {
        val snapshot = LiveSettingsSnapshot.of(
            FakeSource(listOf(group(0, listOf("线路一", "线路二", "线路三")))),
            channel(sourceNum = 3), 2,
        )
        val items = snapshot.single().items
        assertEquals(listOf(0, 1, 2), items.map { it.itemIndex })
        assertEquals(listOf(false, false, true), items.map { it.selected })
    }

    @Test
    fun selectedIndex_outOfRangeChannelSourceSelectsNothing() {
        val snapshot = LiveSettingsSnapshot.of(
            FakeSource(listOf(group(0, listOf("线路一", "线路二")))),
            channel(sourceNum = 6), 5,
        )
        assertEquals(listOf(false, false), snapshot.single().items.map { it.selected })
    }

    @Test
    fun selectedIndex_matchesItemIndexWithinGroup() {
        val snapshot = LiveSettingsSnapshot.of(FakeSource(allGroups(), timeout = 1), null, -1)
        val timeout = snapshot.first { it.groupIndex == 3 }
        assertEquals(listOf(false, true), timeout.items.map { it.selected })
    }

    @Test
    fun selectedIndex_usesPlayerAndLiveGroupAndConfigIndex() {
        val snapshot = LiveSettingsSnapshot.of(
            FakeSource(allGroups(), scale = 1, type = 0, groupIndex = 1, configIndex = 1),
            channel(sourceNum = 2), 0,
        )
        assertEquals(listOf(false, true), snapshot.first { it.groupIndex == 1 }.items.map { it.selected })
        assertEquals(listOf(true, false), snapshot.first { it.groupIndex == 2 }.items.map { it.selected })
        assertEquals(listOf(false, true), snapshot.first { it.groupIndex == 5 }.items.map { it.selected })
        assertEquals(listOf(false, true), snapshot.first { it.groupIndex == 6 }.items.map { it.selected })
    }

    @Test
    fun longPressDelete_onlyInConfigGroupOutsideLineMode() {
        val config = LiveSettingsSnapshot.CONFIG_GROUP_INDEX
        val outsideRepo = LiveSettingsSnapshot.of(FakeSource(allGroups()), null, -1).first { it.groupIndex == config }
        val inRepo = LiveSettingsSnapshot.of(FakeSource(allGroups(), lineMode = true), null, -1)
            .first { it.groupIndex == config }
        assertTrue(outsideRepo.longPressDelete)
        assertFalse(inRepo.longPressDelete)
        assertFalse(
            LiveSettingsSnapshot.of(FakeSource(allGroups()), null, -1)
                .filter { it.groupIndex != config }
                .any { it.longPressDelete },
        )
    }

    @Test
    fun snapshotDetachesItemTitlesFromBeans() {
        val config = group(6, listOf("跟随点播源", "配置A"))
        val snapshot = LiveSettingsSnapshot.of(FakeSource(listOf(config)), null, -1)
        config.liveSettingItems!![1].itemName = "改过的名字"
        assertEquals("配置A", snapshot.single().items[1].title)
    }

    @Test
    fun switchGroupIndexIsFourAndConfigGroupIndexIsSix() {
        assertEquals(4, LiveSettingsSnapshot.SWITCH_GROUP_INDEX)
        assertEquals(6, LiveSettingsSnapshot.CONFIG_GROUP_INDEX)
    }
}
