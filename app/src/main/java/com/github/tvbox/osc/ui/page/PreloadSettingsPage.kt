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
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import kotlin.math.roundToInt

@Composable
fun PreloadSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    var sliderPreloadDuration by remember(state.preloadDuration) { mutableStateOf(state.preloadDuration) }
    var sliderCacheSize by remember(state.exoCacheSizeMb) { mutableStateOf(state.exoCacheSizeMb) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_preload),
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

            SettingsGroup(title = stringResource(R.string.settings_group_preload_cache)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_next_episode),
                        subtitle = stringResource(R.string.preload_next_episode_subtitle),
                        checked = state.preloadNextEpisode,
                        onCheckedChange = { vm.put(HawkConfig.PRELOAD_NEXT_EPISODE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_duration),
                        value = sliderPreloadDuration.toFloat(),
                        valueText = "${sliderPreloadDuration}s",
                        valueRange = 20f..120f,
                        steps = 9,
                        onValueChange = { sliderPreloadDuration = ((it - 20) / 10).roundToInt() * 10 + 20 },
                        onValueChangeFinished = {
                            if (sliderPreloadDuration != state.preloadDuration) {
                                vm.put(HawkConfig.PRELOAD_DURATION, sliderPreloadDuration)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_play_cache),
                        subtitle = stringResource(R.string.preload_play_cache_subtitle),
                        checked = state.playCache,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_CACHE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_cache_size),
                        value = sliderCacheSize.toFloat(),
                        valueText = if (sliderCacheSize >= 1024) "%.1fGB".format(sliderCacheSize / 1024f) else "${sliderCacheSize}MB",
                        valueRange = 128f..4096f,
                        steps = 30,
                        onValueChange = { sliderCacheSize = ((it - 128) / 128).roundToInt() * 128 + 128 },
                        onValueChangeFinished = {
                            if (sliderCacheSize != state.exoCacheSizeMb) {
                                vm.put(HawkConfig.EXO_CACHE_SIZE_MB, sliderCacheSize)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }
}
