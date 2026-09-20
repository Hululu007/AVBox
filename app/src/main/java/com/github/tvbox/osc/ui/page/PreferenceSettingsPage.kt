package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryMerge
import kotlin.math.roundToInt
import org.greenrobot.eventbus.EventBus

@Composable
fun PreferenceSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    var sliderSpeed by remember(state.longPressSpeed) { mutableStateOf(state.longPressSpeed) }
    var sliderBuffer by remember(state.bufferTimes) { mutableStateOf(state.bufferTimes) }
    var sliderThreads by remember(state.searchThreads) { mutableStateOf(state.searchThreads) }
    var danmuApiDialog by remember { mutableStateOf(false) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "偏好设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, "返回", onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = "历史合并",
                        checked = state.historyMerge,
                        onCheckedChange = {
                            HistoryMerge.setEnabled(it)
                            vm.refresh()
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "无痕模式",
                        checked = state.incognito,
                        onCheckedChange = { vm.put(HawkConfig.INCOGNITO, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "禁用手势控制",
                        subtitle = "开启后将禁用手势控制亮度和音量",
                        checked = state.gestureControlDisabled,
                        onCheckedChange = { vm.put(HawkConfig.GESTURE_CONTROL_DISABLED, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = "禁用导航动画",
                        subtitle = "开启后将禁用底部导航的侧滑动画",
                        checked = state.navAnimationDisabled,
                        onCheckedChange = { vm.put(HawkConfig.NAV_ANIMATION_DISABLED, it) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = "自动换线",
                        checked = state.autoSwitchLine,
                        onCheckedChange = { vm.put(HawkConfig.AUTO_SWITCH_LINE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "M3U8 净化",
                        checked = state.m3u8Purify,
                        onCheckedChange = { vm.put(HawkConfig.M3U8_PURIFY, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "弹幕开关",
                        checked = state.danmuOpen,
                        onCheckedChange = { vm.put(HawkConfig.DANMU_OPEN, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "弹幕 API",
                        valueText = state.danmuApi.ifEmpty { "未设置" },
                        onClick = { danmuApiDialog = true },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = "长按倍速",
                        value = sliderSpeed.toFloat(),
                        valueText = "${sliderSpeed}x",
                        valueRange = 2f..10f,
                        steps = 7,
                        onValueChange = { sliderSpeed = (it - 2).roundToInt() + 2 },
                        onValueChangeFinished = {
                            if (sliderSpeed != state.longPressSpeed) {
                                vm.put(HawkConfig.LONG_PRESS_SPEED, sliderSpeed)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = "缓冲时间",
                        value = sliderBuffer.toFloat(),
                        valueText = "${sliderBuffer}x",
                        valueRange = 1f..10f,
                        steps = 8,
                        onValueChange = { sliderBuffer = (it - 1).roundToInt() + 1 },
                        onValueChangeFinished = {
                            if (sliderBuffer != state.bufferTimes) {
                                vm.put(HawkConfig.BUFFER_TIMES, sliderBuffer)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = "搜索线程",
                        value = sliderThreads.toFloat(),
                        valueText = "$sliderThreads",
                        valueRange = 16f..64f,
                        steps = 2,
                        onValueChange = { sliderThreads = ((it - 16) / 16).roundToInt() * 16 + 16 },
                        onValueChangeFinished = {
                            if (sliderThreads != state.searchThreads) {
                                vm.put(HawkConfig.SEARCH_THREADS, sliderThreads)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    if (danmuApiDialog) {
        TextEditDialog(
            title = "弹幕 API",
            initialText = state.danmuApi,
            onDismiss = { danmuApiDialog = false },
            onConfirm = { text ->
                vm.put(HawkConfig.DANMU_API, text)
                danmuApiDialog = false
            },
        )
    }
}
