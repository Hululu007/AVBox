package com.github.tvbox.osc.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

data class UiSettingsConfig(
    val navAnimationDisabled: Boolean,
    val navLiveHidden: Boolean,
    val defaultLoadLive: Boolean,
    val collectColumns: Int,
    val hideStatusBar: Boolean,
    val gestureControlDisabled: Boolean,
    val incognito: Boolean,
    val longPressSpeed: Int,
    val musicAutoOpenPage: Boolean,
    val dohIndex: Int,
)

object UiSettingsState {

    private var current by mutableStateOf(load())

    val config: UiSettingsConfig get() = current

    private fun load(): UiSettingsConfig = UiSettingsConfig(
        navAnimationDisabled = KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false),
        navLiveHidden = KV.get(HawkConfig.NAV_LIVE_HIDDEN, false),
        defaultLoadLive = KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false),
        collectColumns = KV.get(HawkConfig.COLLECT_COLUMNS, 3),
        hideStatusBar = KV.get(HawkConfig.HIDE_STATUS_BAR, false),
        gestureControlDisabled = KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false),
        incognito = KV.get(HawkConfig.INCOGNITO, false),
        longPressSpeed = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT),
        musicAutoOpenPage = KV.get(HawkConfig.MUSIC_AUTO_OPEN_PAGE, true),
        dohIndex = KV.get(HawkConfig.DOH_URL, 0),
    )

    fun reload() {
        current = load()
    }

    fun setNavAnimationDisabled(disabled: Boolean) {
        KV.put(HawkConfig.NAV_ANIMATION_DISABLED, disabled)
        current = current.copy(navAnimationDisabled = disabled)
    }

    fun setNavLiveHidden(hidden: Boolean) {
        KV.put(HawkConfig.NAV_LIVE_HIDDEN, hidden)
        current = current.copy(navLiveHidden = hidden)
    }

    fun setDefaultLoadLive(enabled: Boolean) {
        KV.put(HawkConfig.DEFAULT_LOAD_LIVE, enabled)
        current = current.copy(defaultLoadLive = enabled)
    }

    fun setCollectColumns(columns: Int) {
        KV.put(HawkConfig.COLLECT_COLUMNS, columns)
        current = current.copy(collectColumns = columns)
    }

    fun setHideStatusBar(hidden: Boolean) {
        KV.put(HawkConfig.HIDE_STATUS_BAR, hidden)
        current = current.copy(hideStatusBar = hidden)
    }

    fun setGestureControlDisabled(disabled: Boolean) {
        KV.put(HawkConfig.GESTURE_CONTROL_DISABLED, disabled)
        current = current.copy(gestureControlDisabled = disabled)
    }

    fun setIncognito(enabled: Boolean) {
        KV.put(HawkConfig.INCOGNITO, enabled)
        current = current.copy(incognito = enabled)
    }

    fun setLongPressSpeed(speed: Int) {
        KV.put(HawkConfig.LONG_PRESS_SPEED, speed)
        current = current.copy(longPressSpeed = speed)
    }

    fun setMusicAutoOpenPage(enabled: Boolean) {
        KV.put(HawkConfig.MUSIC_AUTO_OPEN_PAGE, enabled)
        current = current.copy(musicAutoOpenPage = enabled)
    }

    fun setDohIndex(index: Int) {
        KV.put(HawkConfig.DOH_URL, index)
        current = current.copy(dohIndex = index)
    }
}
