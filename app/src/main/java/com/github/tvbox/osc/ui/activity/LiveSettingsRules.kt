package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.bean.LiveSettingItem
import java.util.ArrayList

/**
 * 直播设置面板的纯判定:哪些设置组可见、当前频道是否可切线路、
 * 「配置切换」选中项的下标。不碰播放器与 UI。
 */
internal object LiveSettingsRules {

    /**
     * 面板要显示哪些设置组:当前频道没有可切换线路时,隐藏「线路选择 / 画面比例 / 播放解码」三组(index 0..2)。
     * 判据是 groupIndex 而非列表下标 —— 列表本身可能被配置裁剪过。
     */
    internal fun visibleGroups(groups: List<LiveSettingGroup>, hasChannelSource: Boolean): List<LiveSettingGroup> =
        groups.filter { group -> !(group.groupIndex in 0..2 && !hasChannelSource) }

    /** 当前频道是否有可切换的线路:url 列表存在、数量大于 0,且当前下标落在范围内 */
    internal fun hasChannelSource(item: LiveChannelItem?): Boolean {
        val channel = item ?: return false
        return channel.channelUrls != null && channel.sourceNum > 0 &&
                channel.sourceIndex >= 0 && channel.sourceIndex < channel.channelUrls.size
    }

    /**
     * 当前生效的直播配置在「配置切换」组里的下标:
     * 跟随点播源 = 0(第 0 项是合成项),否则为历史下标 +1;两者都不是则 -1(不选中任何项)。
     */
    internal fun currentConfigIndex(followVod: Boolean, history: List<String>, currentUrl: String): Int {
        if (followVod) return 0
        val index = history.indexOf(currentUrl)
        return if (index < 0) -1 else index + 1
    }

    /** 把当前频道的线路名列表转成设置项(itemIndex 即线路下标) */
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
