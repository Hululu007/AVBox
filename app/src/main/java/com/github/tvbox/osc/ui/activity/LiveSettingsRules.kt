package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.bean.LiveSettingItem
import java.util.ArrayList

internal object LiveSettingsRules {

    internal fun visibleGroups(groups: List<LiveSettingGroup>, hasChannelSource: Boolean): List<LiveSettingGroup> =
        groups.filter { group -> !(group.groupIndex in 0..2 && !hasChannelSource) }

    internal fun hasChannelSource(item: LiveChannelItem?, sourceIndex: Int): Boolean {
        val channel = item ?: return false
        val urls = channel.channelUrls ?: return false
        return channel.sourceNum > 0 && sourceIndex >= 0 && sourceIndex < urls.size
    }

    internal fun currentConfigIndex(followVod: Boolean, history: List<String>, currentUrl: String): Int {
        if (followVod) return 0
        val index = history.indexOf(currentUrl)
        return if (index < 0) -1 else index + 1
    }

    internal fun sourceItems(sourceNames: List<String>?): ArrayList<LiveSettingItem> {
        val items = ArrayList<LiveSettingItem>()
        if (sourceNames != null) {
            for (j in sourceNames.indices) {
                val item = LiveSettingItem()
                item.itemIndex = j
                item.itemName = sourceNames[j]
                items.add(item)
            }
        }
        return items
    }
}
