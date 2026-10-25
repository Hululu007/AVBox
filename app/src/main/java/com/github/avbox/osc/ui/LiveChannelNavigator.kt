package com.github.avbox.osc.ui

import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.bean.LiveChannelItem
import java.util.Locale

internal object LiveChannelNavigator {

    internal fun nextPosition(
        groups: List<LiveChannelGroup>,
        currentGroupIndex: Int,
        currentChannelIndex: Int,
        direction: Int,
        crossGroup: Boolean,
        channelsOf: (Int) -> List<LiveChannelItem>?,
    ): IntArray {
        var groupIndex = currentGroupIndex
        var channelIndex = currentChannelIndex
        if (direction > 0) {
            channelIndex++
            if (channelIndex >= (channelsOf(groupIndex)?.size ?: 0)) {
                channelIndex = 0
                if (crossGroup) {
                    groupIndex = advanceToAccessibleGroup(groups, groupIndex, 1, currentGroupIndex)
                }
            }
        } else {
            channelIndex--
            if (channelIndex < 0) {
                if (crossGroup) {
                    groupIndex = advanceToAccessibleGroup(groups, groupIndex, -1, currentGroupIndex)
                }
                channelIndex = (channelsOf(groupIndex)?.size ?: 1) - 1
            }
        }
        return intArrayOf(groupIndex, channelIndex)
    }

    internal fun firstChannelByName(
        groups: List<LiveChannelGroup>,
        keyword: String?,
        needsPassword: (Int) -> Boolean,
    ): IntArray? {
        if (keyword.isNullOrEmpty()) return null
        val upperKeyword = keyword.uppercase(Locale.US)
        for (group in groups) {
            if (needsPassword(group.groupIndex)) continue
            val groupChannels = group.liveChannels ?: continue
            if (groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                val name = item.channelName ?: continue
                if (name.uppercase(Locale.US).contains(upperKeyword)) {
                    return intArrayOf(group.groupIndex, item.channelIndex)
                }
            }
        }
        return null
    }

    internal fun firstUnlockedGroupIndex(groups: List<LiveChannelGroup>): Int {
        for (group in groups) {
            if (group.groupPassword.isNullOrEmpty()) return group.groupIndex
        }
        return -1
    }

    private fun hasPassword(groups: List<LiveChannelGroup>, index: Int): Boolean =
        groups.getOrNull(index)?.groupPassword?.isNotEmpty() != false

    private fun advanceToAccessibleGroup(
        groups: List<LiveChannelGroup>,
        fromIndex: Int,
        direction: Int,
        currentGroupIndex: Int,
    ): Int {
        var groupIndex = fromIndex
        var steps = 0
        do {
            groupIndex += if (direction > 0) 1 else -1
            if (groupIndex >= groups.size) groupIndex = 0
            if (groupIndex < 0) groupIndex = groups.size - 1
            steps++
        } while (steps < groups.size && hasPassword(groups, groupIndex) && groupIndex != currentGroupIndex)
        return groupIndex
    }
}
