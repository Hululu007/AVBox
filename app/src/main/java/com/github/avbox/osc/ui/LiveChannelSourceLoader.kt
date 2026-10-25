package com.github.avbox.osc.ui

import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.bean.LiveChannelItem
import com.github.avbox.osc.player.PlayerKernelView
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.launch
import java.util.ArrayList

internal class LiveChannelSourceLoader(
    private val vm: LivePlayViewModel,
    private val host: Host,
    private val overlay: LiveOverlayController,
) {

    internal interface Host {
        fun videoView(): PlayerKernelView?

        fun releasePlayerKernel()

        fun isNeedInputPassword(groupIndex: Int): Boolean

        fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int)

        fun initLiveSettingGroupList()

        fun onSettingsInputsChanged()

        fun cancelEpgPending()

        fun initCatchup()

        fun initPlayer(view: PlayerKernelView)

        fun cancelSourceSwitch()

        fun toast(msg: String)
    }

    private var refreshingLiveChannelList = false
    private var loadingLiveConfigOnEnter = false
    private var pendingLiveRefreshChannelName: String? = null

    private val proxyLoader = LiveProxyLoader(object : LiveProxyLoader.Host {
        override fun isRefreshing(): Boolean = refreshingLiveChannelList

        override fun onLoading() {
            vm.updateFrame { it.copy(pageState = PageState.LOADING) }
        }

        override fun onEmpty() {
            setEmptyLiveChannelList()
        }

        override fun onGroupsLoaded(groups: List<LiveChannelGroup>) {
            applyLiveChannelGroups(groups)
        }
    })

    fun cancelAll() {
        proxyLoader.cancelAll()
    }

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? {
        val group = vm.channelGroups().getOrNull(groupIndex) ?: return null
        return if (!host.isNeedInputPassword(groupIndex)) group.liveChannels else ArrayList()
    }

    fun initLiveChannelList() {
        val list = ApiConfig.get().channelGroupList
        if (list.isEmpty()) {
            loadLiveConfigOnEnter()
            return
        }
        host.initCatchup()
        if (list.size == 1 && list[0].groupName.orEmpty().startsWith("http://127.0.0.1")) {
            loadProxyLives(list[0].groupName.orEmpty())
        } else {
            applyLiveChannelGroups(ArrayList(list))
        }
    }

    fun loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return
        loadingLiveConfigOnEnter = true
        vm.updateFrame { it.copy(pageState = PageState.LOADING) }
        vm.viewModelScope.launch {
            val error = ApiConfig.get().loadLiveConfig(true)
            loadingLiveConfigOnEnter = false
            if (error == null) {
                initLiveChannelList()
                host.initLiveSettingGroupList()
                host.onSettingsInputsChanged()
            } else {
                AppLog.e("echo-live-config-error msg=$error")
                setEmptyLiveChannelList()
            }
        }
    }

    private fun loadProxyLives(url: String) {
        proxyLoader.load(url)
    }

    private fun applyLiveChannelGroups(groups: List<LiveChannelGroup>) {
        vm.onChannelGroupsChanged(groups)
        vm.updateFrame { it.copy(pageState = PageState.READY) }
        initLiveState()
    }

    private fun initLiveState() {
        refreshingLiveChannelList = false
        val lastChannelName = pendingLiveRefreshChannelName ?: KV.get(KVKeys.LIVE_CHANNEL, "")
        pendingLiveRefreshChannelName = null
        val preferredSourceIndex = vm.currentSourceIndex()

        var lastChannelGroupIndex = -1
        var lastLiveChannelIndex = -1
        var lastLiveChannelItem: LiveChannelItem? = null
        for (group in vm.channelGroups()) {
            val groupChannels = group.liveChannels
            if (groupChannels == null || groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                if (item.channelName == lastChannelName) {
                    lastChannelGroupIndex = group.groupIndex
                    lastLiveChannelIndex = item.channelIndex
                    lastLiveChannelItem = item
                    break
                }
            }
            if (lastChannelGroupIndex != -1) break
        }
        if (lastChannelGroupIndex == -1) {
            val cctv1Channel = LiveChannelNavigator.firstChannelByName(
                vm.channelGroups(), "CCTV1"
            ) { groupIndex -> host.isNeedInputPassword(groupIndex) }
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0]
                lastLiveChannelIndex = cctv1Channel[1]
            } else {
                lastChannelGroupIndex = LiveChannelNavigator.firstUnlockedGroupIndex(vm.channelGroups())
                if (lastChannelGroupIndex == -1) lastChannelGroupIndex = 0
                lastLiveChannelIndex = 0
            }
        }
        if (lastLiveChannelItem != null && preferredSourceIndex >= 0 && lastLiveChannelItem.sourceNum > 0) {
            vm.updateSourceIndex(minOf(preferredSourceIndex, lastLiveChannelItem.sourceNum - 1))
        } else {
            vm.updateSourceIndex(0)
        }
        vm.pendingSourceIndex = vm.currentSourceIndex()

        host.videoView()?.let { host.initPlayer(it) }
        overlay.showTime()
        overlay.showNetSpeed()
        vm.updateChannelList { it.copy(playingChannelIndex = -1, expandedGroups = emptySet()) }
        host.selectChannelGroup(lastChannelGroupIndex, lastLiveChannelIndex)
    }

    fun refreshLiveChannelListAndPlay(channelName: String?, sourceIndex: Int) {
        refreshingLiveChannelList = true
        pendingLiveRefreshChannelName = channelName
        vm.updateSourceIndex(sourceIndex)
        vm.updateEpg { it.copy(lookBackIndex = -1) }
        vm.resetSourceSwitchAttempts()
        vm.updateChannelList { it.copy(confirmedPasswordGroups = emptySet(), expandedGroups = emptySet()) }
        host.cancelSourceSwitch()
        host.cancelEpgPending()
        overlay.hideSwitchChannelSnapshot()
        vm.updateTimeshift { it.copy(isBackState = false) }
        vm.updateOverlay { it.copy(visible = false) }
        initLiveChannelList()
        host.initLiveSettingGroupList()
        host.onSettingsInputsChanged()
    }

    private fun clearLiveChannelList(releasePlayer: Boolean) {
        refreshingLiveChannelList = false
        pendingLiveRefreshChannelName = null
        vm.updateChannelList {
            it.copy(
                playingChannel = null,
                playingChannelIndex = -1,
                tappedGroupIndex = 0,
                expandedGroups = emptySet(),
            )
        }
        vm.updateEpg { it.copy(lookBackIndex = -1, channelName = "", epgList = emptyList()) }
        vm.resetSourceSwitchAttempts()
        vm.updateSourceIndex(-1)
        vm.onChannelGroupsChanged(emptyList())
        ApiConfig.get().clearLiveChannelGroups()
        host.cancelSourceSwitch()
        host.cancelEpgPending()
        overlay.hideSwitchChannelSnapshot()
        if (releasePlayer) host.releasePlayerKernel()
        vm.updateChannelInfo(ChannelInfoUi())
        vm.updateFrame { it.copy(pageState = PageState.EMPTY) }
    }

    fun setEmptyLiveChannelList(releasePlayer: Boolean = true) {
        clearLiveChannelList(releasePlayer)
    }
}
