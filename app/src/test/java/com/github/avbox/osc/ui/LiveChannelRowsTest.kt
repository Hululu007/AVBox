package com.github.avbox.osc.ui

import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.bean.LiveChannelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelRowsTest {

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

    private fun groupWithoutChannels(groupIndex: Int): LiveChannelGroup {
        val group = LiveChannelGroup()
        group.groupIndex = groupIndex
        group.groupName = "G$groupIndex"
        return group
    }

    @Test
    fun collapsedGroup_emitsHeaderOnly() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1", "CCTV2")))
        val rows = LiveChannelRows.of(groups, expandedGroups = emptySet(), lockedGroups = emptySet())
        assertEquals(1, rows.size)
        assertEquals("g0", rows[0].key)
        assertEquals(-1, rows[0].channelPos)
    }

    @Test
    fun expandedGroup_emitsHeaderThenChannels() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1", "CCTV2")))
        val rows = LiveChannelRows.of(groups, expandedGroups = setOf(0), lockedGroups = emptySet())
        assertEquals(listOf("g0", "c0_0", "c0_1"), rows.map { it.key })
        assertEquals(listOf(0, 1), rows.drop(1).map { it.channelPos })
        assertEquals("CCTV1", rows[1].channel?.channelName)
    }

    @Test
    fun expandedLockedGroup_emitsHeaderOnly() {
        val groups = listOf(group(0, password = "1234", channelNames = listOf("付费台")))
        val rows = LiveChannelRows.of(groups, expandedGroups = setOf(0), lockedGroups = setOf(0))
        assertEquals(listOf("g0"), rows.map { it.key })
    }

    @Test
    fun groupWithoutChannels_emitsHeaderOnly() {
        val rows = LiveChannelRows.of(
            listOf(groupWithoutChannels(0)),
            expandedGroups = setOf(0),
            lockedGroups = emptySet(),
        )
        assertEquals(listOf("g0"), rows.map { it.key })
    }

    @Test
    fun keysUseBeanGroupIndexNotListPosition() {
        val groups = listOf(
            group(7, channelNames = listOf("湖南卫视")),
            group(9, channelNames = listOf("CCTV1")),
        )
        val rows = LiveChannelRows.of(groups, expandedGroups = setOf(9), lockedGroups = emptySet())
        assertEquals(listOf("g7", "g9", "c9_0"), rows.map { it.key })
    }

    @Test
    fun keysAreUniqueAcrossGroups() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1", "CCTV2")),
            group(1, channelNames = listOf("湖南卫视")),
        )
        val rows = LiveChannelRows.of(groups, expandedGroups = setOf(0, 1), lockedGroups = emptySet())
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun rowsKeepGroupAndChannelReferences() {
        val groups = listOf(group(3, channelNames = listOf("CCTV1")))
        val rows = LiveChannelRows.of(groups, expandedGroups = setOf(3), lockedGroups = emptySet())
        assertTrue(rows.all { it.group === groups[0] })
    }

    @Test
    fun lockedGroups_marksOnlyPasswordProtectedUnconfirmedGroups() {
        val groups = listOf(
            group(0, channelNames = listOf("CCTV1")),
            group(1, password = "1234", channelNames = listOf("付费台")),
            group(2, password = "5678", channelNames = listOf("付费台2")),
        )
        assertEquals(setOf(1, 2), LiveChannelRows.lockedGroups(groups, emptySet()))
        assertEquals(setOf(2), LiveChannelRows.lockedGroups(groups, setOf(1)))
        assertEquals(emptySet<Int>(), LiveChannelRows.lockedGroups(groups, setOf(1, 2)))
    }

    @Test
    fun lockedGroups_emptyForBlankPassword() {
        val groups = listOf(group(0, channelNames = listOf("CCTV1")))
        assertEquals(emptySet<Int>(), LiveChannelRows.lockedGroups(groups, emptySet()))
    }
}
