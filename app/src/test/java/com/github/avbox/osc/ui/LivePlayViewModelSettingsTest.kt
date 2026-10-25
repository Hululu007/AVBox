package com.github.avbox.osc.ui

import com.github.avbox.osc.bean.LiveChannelItem
import com.github.avbox.osc.bean.LiveSettingGroup
import com.github.avbox.osc.bean.LiveSettingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlayViewModelSettingsTest {

    private class RecordingSource(private val groups: List<LiveSettingGroup>, private val checked: MutableSet<Int>) :
        LiveSettingsSource {
        override fun settingGroups(): List<LiveSettingGroup> = groups

        override fun playerScale(): Int = 0

        override fun playerType(): Int = 0

        override fun connectTimeoutIndex(): Int = 0

        override fun switchChecked(position: Int): Boolean = checked.contains(position)

        override fun liveGroupIndex(): Int = 0

        override fun configIndex(): Int = 0

        override fun isLiveApiLineMode(): Boolean = false
    }

    private class RecordingHost : LivePlayViewModel.Host {
        var removed: Int? = null

        override fun currentChannelItem(): LiveChannelItem? = null

        override fun currentPlayerScale(): Int = 0

        override fun currentPlayerType(): Int = 0

        override fun replayCurrentChannel() = Unit

        override fun applyPlayerScale(position: Int) = Unit

        override fun applyPlayerType(position: Int) = Unit

        override fun releasePlayerKernel() = Unit

        override fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int) = Unit

        override fun setEmptyChannelList(releasePlayer: Boolean) = Unit

        override fun removeConfigHistory(itemIndex: Int) {
            removed = itemIndex
        }

        override fun toast(msg: String) = Unit

        override fun isFinishing(): Boolean = false
    }

    private fun switchGroup(): LiveSettingGroup {
        val group = LiveSettingGroup()
        group.groupIndex = LiveSettingsSnapshot.SWITCH_GROUP_INDEX
        group.groupName = "偏好设置"
        val items = ArrayList<LiveSettingItem>()
        listOf("显示时间", "显示网速").forEachIndexed { index, name ->
            val item = LiveSettingItem()
            item.itemIndex = index
            item.itemName = name
            items.add(item)
        }
        group.liveSettingItems = items
        return group
    }

    private fun vmWithSettings(checked: MutableSet<Int>): Pair<LivePlayViewModel, LiveSettingsSource> {
        val vm = LivePlayViewModel()
        val source = RecordingSource(listOf(switchGroup()), checked)
        vm.attachSettingsSource(source)
        return vm to source
    }

    @Test
    fun settingsOpened_buildsSnapshotAndShowsSheet() {
        val (vm, _) = vmWithSettings(mutableSetOf(0))
        vm.onSettingsOpened()
        val settings = vm.state.value.settings
        assertTrue(settings.sheetVisible)
        assertEquals(1, settings.groups.size)
        assertEquals(listOf(true, false), settings.groups[0].items.map { it.checked })
    }

    @Test
    fun inputsChanged_refreshesOnlyWhileSheetVisible() {
        val checked = mutableSetOf(0)
        val (vm, _) = vmWithSettings(checked)
        checked.add(1)
        vm.onSettingsInputsChanged()
        assertEquals(emptyList<LiveSettingsGroupUi>(), vm.state.value.settings.groups)
        vm.onSettingsOpened()
        checked.remove(0)
        vm.onSettingsInputsChanged()
        assertEquals(listOf(false, true), vm.state.value.settings.groups[0].items.map { it.checked })
    }

    @Test
    fun settingRemoved_callsHostThenRefreshesSnapshot() {
        val (vm, _) = vmWithSettings(mutableSetOf(0))
        val host = RecordingHost()
        vm.onSettingsOpened()
        vm.onSettingRemoved(2, host)
        assertEquals(2, host.removed)
    }

    @Test
    fun updateSettings_togglesSheetVisibility() {
        val (vm, _) = vmWithSettings(mutableSetOf())
        vm.updateSettings { it.copy(sheetVisible = true) }
        assertTrue(vm.state.value.settings.sheetVisible)
        vm.updateSettings { it.copy(sheetVisible = false) }
        assertFalse(vm.state.value.settings.sheetVisible)
    }

    @Test
    fun snapshotWithoutSource_staysEmpty() {
        val vm = LivePlayViewModel()
        vm.onSettingsOpened()
        assertTrue(vm.state.value.settings.sheetVisible)
        assertEquals(emptyList<LiveSettingsGroupUi>(), vm.state.value.settings.groups)
    }
}
