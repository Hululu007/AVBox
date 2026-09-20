package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import java.util.Locale

/**
 * 直播频道导航的纯索引计算:上/下一台、按名字定位频道、首个无密码组。
 * 只算下标,不碰播放器与 UI;某分组当前可见哪些频道由调用方注入。
 */
internal object LiveChannelNavigator {

    /**
     * 计算上/下一台的位置。
     *
     * @param groups 频道分组,顺序即列表顺序
     * @param currentGroupIndex 当前分组在列表中的下标
     * @param currentChannelIndex 当前频道在分组内的下标
     * @param direction 正数下一台,否则上一台
     * @param crossGroup 是否允许跨组(HawkConfig.LIVE_CROSS_GROUP)
     * @param channelsOf 取某分组当前可见的频道;分组不存在返回 null,需要密码且未确认返回空列表
     * @return [分组下标, 频道下标]
     */
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

    /**
     * 按关键字找第一个频道(频道名包含关键字,忽略大小写)。
     * 返回的是 bean 自带的 [分组索引, 频道索引],不是列表下标;找不到返回 null。
     *
     * @param needsPassword 该分组是否"需要输入密码"(带密码且本次未确认)——未确认的组不参与搜索
     */
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

    /** 第一个不带密码的分组索引(bean 自带 groupIndex),全都有密码或列表为空返回 -1。 */
    internal fun firstUnlockedGroupIndex(groups: List<LiveChannelGroup>): Int {
        for (group in groups) {
            if (group.groupPassword.isNullOrEmpty()) return group.groupIndex
        }
        return -1
    }

    /** 分组是否存在且带密码;分组不存在也算 true(避免越界时被当成可进入) */
    private fun hasPassword(groups: List<LiveChannelGroup>, index: Int): Boolean =
        groups.getOrNull(index)?.groupPassword?.isNotEmpty() != false

    /**
     * 跳过带密码的分组,找下一个可进入的分组;绕回当前组就停在当前组。
     * 必须保持有界:写成 `while (带密码 || 回到当前组)` 时,其余组全带密码或只有 1 个分组会恒真而卡死 UI 线程。
     */
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
