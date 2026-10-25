package com.github.avbox.osc.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.PlaybackSettingsState
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsOptionMenuRow
import com.github.avbox.osc.ui.components.SettingsRow
import com.github.avbox.osc.ui.components.SettingsSliderRow
import com.github.avbox.osc.ui.components.SettingsSwitchRow
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.event.UiChange
import com.github.avbox.osc.event.UiEvents
import com.github.avbox.osc.util.AppLanguage
import com.github.avbox.osc.util.DanmuSettingsState
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.SearchSettingsState
import com.github.avbox.osc.util.restartApp
import kotlin.math.roundToInt

@Composable
fun PreferenceSettingsScreen(onNavigateBack: () -> Unit) {
    val uiConfig = UiSettingsState.config
    val playbackConfig = PlaybackSettingsState.config
    val danmuConfig = DanmuSettingsState.config
    val searchConfig = SearchSettingsState.config
    var sliderSpeed by remember(uiConfig.longPressSpeed) { mutableStateOf(uiConfig.longPressSpeed) }
    var sliderBuffer by remember(playbackConfig.bufferTimes) { mutableStateOf(playbackConfig.bufferTimes) }
    var sliderThreads by remember(searchConfig.searchThreads) { mutableStateOf(searchConfig.searchThreads) }
    var danmuApiDialog by remember { mutableStateOf(false) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_preference_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
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

            SettingsGroup(title = stringResource(R.string.settings_group_language_layout)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    LanguageRow()
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    CollectColumnsRow(
                        columns = uiConfig.collectColumns,
                        onSelect = { columns -> UiSettingsState.collectColumns = columns },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_privacy)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_history_merge),
                        leadingIconRes = R.drawable.ic_pref_history_merge,
                        checked = searchConfig.historyMerge,
                        onCheckedChange = {
                            SearchSettingsState.historyMerge = it
                            UiEvents.post(UiChange.HistoryChanged)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_incognito),
                        leadingIconRes = R.drawable.ic_pref_incognito,
                        checked = uiConfig.incognito,
                        onCheckedChange = {
                            UiSettingsState.incognito = it
                            UiEvents.post(UiChange.HistoryChanged)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_hide_status_bar),
                        leadingIconRes = R.drawable.ic_pref_hide_status_bar,
                        subtitle = stringResource(R.string.settings_hide_status_bar_subtitle),
                        checked = uiConfig.hideStatusBar,
                        onCheckedChange = { UiSettingsState.hideStatusBar = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gesture_disable),
                        leadingIconRes = R.drawable.ic_pref_gesture,
                        subtitle = stringResource(R.string.settings_gesture_disable_subtitle),
                        checked = uiConfig.gestureControlDisabled,
                        onCheckedChange = { UiSettingsState.gestureControlDisabled = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_nav_animation_disable),
                        leadingIconRes = R.drawable.ic_pref_nav_animation,
                        checked = uiConfig.navAnimationDisabled,
                        onCheckedChange = { UiSettingsState.navAnimationDisabled = it },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_nav_live_hidden),
                        leadingIconRes = R.drawable.ic_pref_nav_live_hidden,
                        checked = uiConfig.navLiveHidden,
                        onCheckedChange = { UiSettingsState.navLiveHidden = it },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_search)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_switch_line),
                        leadingIconRes = R.drawable.ic_pref_auto_switch_line,
                        checked = playbackConfig.autoSwitchLine,
                        onCheckedChange = { PlaybackSettingsState.autoSwitchLine = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_m3u8_purify),
                        leadingIconRes = R.drawable.ic_pref_m3u8_purify,
                        checked = playbackConfig.m3u8Purify,
                        onCheckedChange = { PlaybackSettingsState.m3u8Purify = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_danmu_switch),
                        leadingIconRes = R.drawable.ic_pref_danmu,
                        checked = danmuConfig.open,
                        onCheckedChange = { DanmuSettingsState.open = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_danmu_api),
                        leadingIconRes = R.drawable.ic_pref_danmu_api,
                        valueText = stringResource(if (danmuConfig.api.isEmpty()) R.string.common_not_set else R.string.common_set),
                        onClick = { danmuApiDialog = true },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_long_press_speed),
                        leadingIconRes = R.drawable.ic_pref_long_press_speed,
                        value = sliderSpeed.toFloat(),
                        valueText = "${sliderSpeed}x",
                        valueRange = 2f..10f,
                        steps = 7,
                        onValueChange = { sliderSpeed = (it - 2).roundToInt() + 2 },
                        onValueChangeFinished = {
                            if (sliderSpeed != uiConfig.longPressSpeed) {
                                UiSettingsState.longPressSpeed = sliderSpeed
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_buffer_time),
                        leadingIconRes = R.drawable.ic_pref_buffer_time,
                        value = sliderBuffer.toFloat(),
                        valueText = "${sliderBuffer}x",
                        valueRange = 1f..10f,
                        steps = 8,
                        onValueChange = { sliderBuffer = (it - 1).roundToInt() + 1 },
                        onValueChangeFinished = {
                            if (sliderBuffer != playbackConfig.bufferTimes) {
                                PlaybackSettingsState.bufferTimes = sliderBuffer
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_search_threads),
                        leadingIconRes = R.drawable.ic_pref_search_threads,
                        value = sliderThreads.toFloat(),
                        valueText = "$sliderThreads",
                        valueRange = 16f..64f,
                        steps = 2,
                        onValueChange = { sliderThreads = ((it - 16) / 16).roundToInt() * 16 + 16 },
                        onValueChangeFinished = {
                            if (sliderThreads != searchConfig.searchThreads) {
                                SearchSettingsState.searchThreads = sliderThreads
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
            title = stringResource(R.string.settings_danmu_api),
            initialText = danmuConfig.api,
            onDismiss = { danmuApiDialog = false },
            onConfirm = { text ->
                DanmuSettingsState.api = text
                danmuApiDialog = false
            },
        )
    }
}

@Composable
private fun CollectColumnsRow(columns: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.settings_collect_columns_three),
        stringResource(R.string.settings_collect_columns_two),
    )
    val selectedIndex = if (columns == 3) 0 else 1
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_collect_columns),
        leadingIconRes = R.drawable.ic_pref_collect_columns,
        valueText = options[selectedIndex],
        options = options,
        selectedIndex = selectedIndex,
        onSelect = { idx -> onSelect(if (idx == 0) 3 else 2) },
    )
}

@Composable
private fun LanguageRow() {
    val available = LanguageManager.available()
    val current = LanguageManager.current()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<AppLanguage?>(null) }
    var rollback by remember { mutableStateOf(AppLanguage.System) }
    var restarting by remember { mutableStateOf(false) }
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_language),
        leadingIconRes = R.drawable.ic_pref_language,
        valueText = stringResource(languageLabelRes(current)),
        options = available.map { stringResource(languageLabelRes(it)) },
        selectedIndex = available.indexOf(current),
        onSelect = { idx ->
            val target = available.getOrNull(idx)
            if (target != null && target != current) {
                rollback = current
                LanguageManager.set(target)
                pending = target
            }
        },
    )
    val cancel = {
        LanguageManager.set(rollback)
        pending = null
    }
    pending?.let {
        AVBoxAlertDialog(
            onDismissRequest = cancel,
            text = { Text(stringResource(R.string.settings_language_restart_message)) },
            dismissButton = {
                val dismissAnimated = LocalSheetDismiss.current
                TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        pending = null
                        restarting = true
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }
    if (restarting) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            restartApp(context.applicationContext)
        }
    }
}

private fun languageLabelRes(lang: AppLanguage): Int = when (lang) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.SimplifiedChinese -> R.string.settings_language_zh_hans
    AppLanguage.English -> R.string.settings_language_en
    AppLanguage.TraditionalTW -> R.string.settings_language_zh_hant_tw
    AppLanguage.TraditionalHK -> R.string.settings_language_zh_hant_hk
}
