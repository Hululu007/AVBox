package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiveSettingsRules] 的纯函数单测(纯 JVM,无需 Robolectric)。
 *
 * 选点理由:这些下标判定错了不会崩,只会"设置面板少一项""高亮停在别的项上""线路列表空白",
 * 真机上很难判断是数据问题还是算错,只能靠单测把规则固定下来。
 */
class LiveSettingsRulesTest {

    private fun group(index: Int): LiveSettingGroup {
        val group = LiveSettingGroup()
        group.groupIndex = index
        group.groupName = "G$index"
        return group
    }

    private fun channel(urls: List<String>?, sourceIndex: Int): LiveChannelItem {
        val item = LiveChannelItem()
        item.channelName = "CCTV1"
        if (urls != null) {
            item.channelUrls = ArrayList(urls)
        }
        item.sourceIndex = sourceIndex
        return item
    }

    // ---------- 设置组可见性 ----------

    @Test
    fun visibleGroups_hidesChannelGroupsWithoutSwitchableSource() {
        val groups = (0..6).map { group(it) }
        val visible = LiveSettingsRules.visibleGroups(groups, hasChannelSource = false)
        assertEquals(listOf(3, 4, 5, 6), visible.map { it.groupIndex })
    }

    @Test
    fun visibleGroups_keepsAllGroupsWithSwitchableSource() {
        val groups = (0..6).map { group(it) }
        assertEquals(7, LiveSettingsRules.visibleGroups(groups, hasChannelSource = true).size)
    }

    @Test
    fun visibleGroups_handlesEmptyList() {
        assertTrue(LiveSettingsRules.visibleGroups(emptyList(), hasChannelSource = false).isEmpty())
    }

    // ---------- 线路可切换性 ----------

    @Test
    fun hasChannelSource_requiresUrlsAndIndexInRange() {
        assertFalse(LiveSettingsRules.hasChannelSource(null))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(null, 0)))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(emptyList(), 0)))
        // 下标越界(切线路后源列表被换掉时的中间态)
        assertFalse(LiveSettingsRules.hasChannelSource(channel(listOf("http://a/1"), 1)))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(listOf("http://a/1"), -1)))
        assertTrue(LiveSettingsRules.hasChannelSource(channel(listOf("http://a/1"), 0)))
    }

    // ---------- 配置切换的下标 ----------

    @Test
    fun currentConfigIndex_followVodIsSyntheticFirstItem() {
        assertEquals(0, LiveSettingsRules.currentConfigIndex(true, emptyList(), "http://a/config.json"))
    }

    @Test
    fun currentConfigIndex_offsetsHistoryBySyntheticItem() {
        val history = listOf("http://a/config.json", "http://b/config.json")
        // 第 0 项是"跟随点播源",历史项从 1 开始
        assertEquals(2, LiveSettingsRules.currentConfigIndex(false, history, "http://b/config.json"))
    }

    @Test
    fun currentConfigIndex_returnsMinusOneWhenNotInHistory() {
        assertEquals(-1, LiveSettingsRules.currentConfigIndex(false, emptyList(), "http://a/config.json"))
        assertEquals(-1, LiveSettingsRules.currentConfigIndex(false, listOf("http://a/config.json"), "http://c/config.json"))
    }

    // ---------- 线路名转设置项 ----------

    @Test
    fun sourceItems_mapsNamesToIndexedItems() {
        val items = LiveSettingsRules.sourceItems(listOf("线路一", "线路二"))
        assertEquals(2, items.size)
        assertEquals(0, items[0].itemIndex)
        assertEquals("线路一", items[0].itemName)
        assertEquals(1, items[1].itemIndex)
        assertEquals("线路二", items[1].itemName)
    }

    @Test
    fun sourceItems_nullBecomesEmpty() {
        assertTrue(LiveSettingsRules.sourceItems(null).isEmpty())
    }
}
