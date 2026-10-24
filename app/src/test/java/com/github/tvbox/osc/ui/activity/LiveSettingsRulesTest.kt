package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSettingsRulesTest {

    private fun group(index: Int): LiveSettingGroup {
        val group = LiveSettingGroup()
        group.groupIndex = index
        group.groupName = "G$index"
        return group
    }

    private fun channel(urls: List<String>?): LiveChannelItem {
        val item = LiveChannelItem()
        item.channelName = "CCTV1"
        if (urls != null) {
            item.channelUrls = ArrayList(urls)
        }
        return item
    }

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

    @Test
    fun hasChannelSource_requiresUrlsAndIndexInRange() {
        assertFalse(LiveSettingsRules.hasChannelSource(null, 0))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(null), 0))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(emptyList()), 0))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(listOf("http://a/1")), 1))
        assertFalse(LiveSettingsRules.hasChannelSource(channel(listOf("http://a/1")), -1))
        assertTrue(LiveSettingsRules.hasChannelSource(channel(listOf("http://a/1")), 0))
    }

    @Test
    fun currentConfigIndex_followVodIsSyntheticFirstItem() {
        assertEquals(0, LiveSettingsRules.currentConfigIndex(true, emptyList(), "http://a/config.json"))
    }

    @Test
    fun currentConfigIndex_offsetsHistoryBySyntheticItem() {
        val history = listOf("http://a/config.json", "http://b/config.json")
        assertEquals(2, LiveSettingsRules.currentConfigIndex(false, history, "http://b/config.json"))
    }

    @Test
    fun currentConfigIndex_returnsMinusOneWhenNotInHistory() {
        assertEquals(-1, LiveSettingsRules.currentConfigIndex(false, emptyList(), "http://a/config.json"))
        assertEquals(-1, LiveSettingsRules.currentConfigIndex(false, listOf("http://a/config.json"), "http://c/config.json"))
    }

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
