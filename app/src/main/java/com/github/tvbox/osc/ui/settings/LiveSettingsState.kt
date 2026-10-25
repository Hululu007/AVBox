package com.github.tvbox.osc.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.google.gson.JsonArray

data class LiveSettingsConfig(
    val connectTimeoutIndex: Int,
    val showTime: Boolean,
    val showNetSpeed: Boolean,
    val channelReverse: Boolean,
    val crossGroup: Boolean,
    val groupList: JsonArray,
)

object LiveSettingsState {

    private var current by mutableStateOf(load())

    val config: LiveSettingsConfig get() = current

    private fun load(): LiveSettingsConfig = LiveSettingsConfig(
        connectTimeoutIndex = KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1),
        showTime = KV.get(HawkConfig.LIVE_SHOW_TIME, false),
        showNetSpeed = KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false),
        channelReverse = KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false),
        crossGroup = KV.get(HawkConfig.LIVE_CROSS_GROUP, false),
        groupList = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray()),
    )

    fun reload() {
        current = load()
    }

    fun setConnectTimeoutIndex(index: Int) {
        KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, index)
        current = current.copy(connectTimeoutIndex = index)
    }

    fun setShowTime(enabled: Boolean) {
        KV.put(HawkConfig.LIVE_SHOW_TIME, enabled)
        current = current.copy(showTime = enabled)
    }

    fun setShowNetSpeed(enabled: Boolean) {
        KV.put(HawkConfig.LIVE_SHOW_NET_SPEED, enabled)
        current = current.copy(showNetSpeed = enabled)
    }

    fun setChannelReverse(enabled: Boolean) {
        KV.put(HawkConfig.LIVE_CHANNEL_REVERSE, enabled)
        current = current.copy(channelReverse = enabled)
    }

    fun setCrossGroup(enabled: Boolean) {
        KV.put(HawkConfig.LIVE_CROSS_GROUP, enabled)
        current = current.copy(crossGroup = enabled)
    }
}
