package com.github.avbox.osc.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
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
        connectTimeoutIndex = KV.get(KVKeys.LIVE_CONNECT_TIMEOUT, 1),
        showTime = KV.get(KVKeys.LIVE_SHOW_TIME, false),
        showNetSpeed = KV.get(KVKeys.LIVE_SHOW_NET_SPEED, false),
        channelReverse = KV.get(KVKeys.LIVE_CHANNEL_REVERSE, false),
        crossGroup = KV.get(KVKeys.LIVE_CROSS_GROUP, false),
        groupList = KV.get(KVKeys.LIVE_GROUP_LIST, JsonArray()),
    )

    fun reload() {
        current = load()
    }

    var connectTimeoutIndex: Int
        get() = current.connectTimeoutIndex
        set(value) {
            KV.put(KVKeys.LIVE_CONNECT_TIMEOUT, value)
            current = current.copy(connectTimeoutIndex = value)
        }

    var showTime: Boolean
        get() = current.showTime
        set(value) {
            KV.put(KVKeys.LIVE_SHOW_TIME, value)
            current = current.copy(showTime = value)
        }

    var showNetSpeed: Boolean
        get() = current.showNetSpeed
        set(value) {
            KV.put(KVKeys.LIVE_SHOW_NET_SPEED, value)
            current = current.copy(showNetSpeed = value)
        }

    var channelReverse: Boolean
        get() = current.channelReverse
        set(value) {
            KV.put(KVKeys.LIVE_CHANNEL_REVERSE, value)
            current = current.copy(channelReverse = value)
        }

    var crossGroup: Boolean
        get() = current.crossGroup
        set(value) {
            KV.put(KVKeys.LIVE_CROSS_GROUP, value)
            current = current.copy(crossGroup = value)
        }
}
