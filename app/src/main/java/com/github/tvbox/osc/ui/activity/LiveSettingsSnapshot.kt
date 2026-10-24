package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LiveSettingGroup

internal interface LiveSettingsSource {
    fun settingGroups(): List<LiveSettingGroup>

    fun playerScale(): Int

    fun playerType(): Int

    fun connectTimeoutIndex(): Int

    fun switchChecked(position: Int): Boolean

    fun liveGroupIndex(): Int

    fun configIndex(): Int

    fun isLiveApiLineMode(): Boolean
}

internal data class LiveSettingsItemUi(
    val itemIndex: Int,
    val title: String,
    val checked: Boolean = false,
    val selected: Boolean = false,
)

internal data class LiveSettingsGroupUi(
    val groupIndex: Int,
    val title: String,
    val switchRow: Boolean = false,
    val longPressDelete: Boolean = false,
    val items: List<LiveSettingsItemUi> = emptyList(),
)

internal data class LiveSettingsUi(
    val sheetVisible: Boolean = false,
    val groups: List<LiveSettingsGroupUi> = emptyList(),
)

internal object LiveSettingsSnapshot {

    internal const val SWITCH_GROUP_INDEX = 4
    internal const val CONFIG_GROUP_INDEX = 6

    fun of(source: LiveSettingsSource, channel: LiveChannelItem?, sourceIndex: Int): List<LiveSettingsGroupUi> {
        val groups = LiveSettingsRules.visibleGroups(source.settingGroups(), LiveSettingsRules.hasChannelSource(channel, sourceIndex))
        return groups.mapNotNull { group ->
            val items = group.liveSettingItems ?: return@mapNotNull null
            val switchRow = group.groupIndex == SWITCH_GROUP_INDEX
            val selectedIndex = if (switchRow) -1 else selectedIndex(source, group.groupIndex, sourceIndex)
            LiveSettingsGroupUi(
                groupIndex = group.groupIndex,
                title = group.groupName.orEmpty(),
                switchRow = switchRow,
                longPressDelete = group.groupIndex == CONFIG_GROUP_INDEX && !source.isLiveApiLineMode(),
                items = items.map { item ->
                    LiveSettingsItemUi(
                        itemIndex = item.itemIndex,
                        title = item.itemName.orEmpty(),
                        checked = switchRow && source.switchChecked(item.itemIndex),
                        selected = !switchRow && item.itemIndex == selectedIndex,
                    )
                },
            )
        }
    }

    private fun selectedIndex(source: LiveSettingsSource, groupIndex: Int, sourceIndex: Int): Int =
        when (groupIndex) {
            0 -> sourceIndex
            1 -> source.playerScale()
            2 -> source.playerType()
            3 -> source.connectTimeoutIndex()
            5 -> source.liveGroupIndex()
            CONFIG_GROUP_INDEX -> source.configIndex()
            else -> -1
        }
}
