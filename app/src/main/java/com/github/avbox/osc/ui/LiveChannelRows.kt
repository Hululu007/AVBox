package com.github.avbox.osc.ui

import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.bean.LiveChannelItem
import java.util.ArrayList

internal class LiveListRow(
    val group: LiveChannelGroup?,
    val channel: LiveChannelItem?,
    val channelPos: Int,
    val key: String,
)

internal object LiveChannelRows {

    fun of(
        groups: List<LiveChannelGroup>,
        expandedGroups: Set<Int>,
        lockedGroups: Set<Int>,
    ): List<LiveListRow> {
        val rows = ArrayList<LiveListRow>()
        for (group in groups) {
            rows.add(LiveListRow(group, null, -1, "g" + group.groupIndex))
            if (!expandedGroups.contains(group.groupIndex)) continue
            if (lockedGroups.contains(group.groupIndex)) continue
            val channels = group.liveChannels ?: continue
            for (index in channels.indices) {
                val channel = channels[index]
                rows.add(LiveListRow(group, channel, index, "c" + group.groupIndex + "_" + channel.channelIndex))
            }
        }
        return rows
    }

    fun lockedGroups(groups: List<LiveChannelGroup>, confirmedGroups: Set<Int>): Set<Int> {
        val locked = HashSet<Int>()
        for (group in groups) {
            if (group.groupPassword.orEmpty().isEmpty()) continue
            if (confirmedGroups.contains(group.groupIndex)) continue
            locked.add(group.groupIndex)
        }
        return locked
    }
}
