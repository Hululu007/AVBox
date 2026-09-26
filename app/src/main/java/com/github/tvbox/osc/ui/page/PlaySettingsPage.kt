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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PlayerHelper
import kotlin.math.roundToInt
import xyz.doikki.videoplayer.player.VideoView

// KV 持久化值(ijk_codec/exo_decode),不能翻;显示走 player_decode_* 资源
private const val DecodeHard = "硬解码" // i18n: keep
private const val DecodeSoft = "软解码" // i18n: keep

@Composable
fun PlaySettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    var sliderPreloadDuration by remember(state.preloadDuration) { mutableStateOf(state.preloadDuration) }
    var sliderCacheSize by remember(state.exoCacheSizeMb) { mutableStateOf(state.exoCacheSizeMb) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_play),
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

            SettingsGroup(title = stringResource(R.string.settings_group_play_picture)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    val playerTypes = PlayerHelper.getExistPlayerTypes().sortedDescending()
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_kernel),
                        valueText = PlayerHelper.getPlayerName(state.playType),
                        options = playerTypes.map { PlayerHelper.getPlayerName(it) },
                        selectedIndex = playerTypes.indexOf(state.playType).coerceAtLeast(0),
                        onSelect = { idx -> vm.put(HawkConfig.PLAY_TYPE, playerTypes[idx]) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_render),
                        valueText = PlayerHelper.getRenderName(state.playRender),
                        options = listOf("SurfaceView", "TextureView"),
                        selectedIndex = 1 - state.playRender,
                        onSelect = { idx ->
                            val render = 1 - idx
                            if (render == 0 && state.playTunnel) vm.put(HawkConfig.PLAY_TUNNEL, false)
                            vm.put(HawkConfig.PLAY_RENDER, render)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    val scales = listOf(
                        VideoView.SCREEN_SCALE_DEFAULT to stringResource(R.string.common_default),
                        VideoView.SCREEN_SCALE_16_9 to "16:9",
                        VideoView.SCREEN_SCALE_4_3 to "4:3",
                        VideoView.SCREEN_SCALE_MATCH_PARENT to stringResource(R.string.player_scale_fill),
                        VideoView.SCREEN_SCALE_ORIGINAL to stringResource(R.string.player_scale_origin),
                        VideoView.SCREEN_SCALE_CENTER_CROP to stringResource(R.string.player_scale_crop),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_scale),
                        valueText = PlayerHelper.getScaleName(state.playScale),
                        options = scales.map { it.second },
                        selectedIndex = scales.indexOfFirst { it.first == state.playScale },
                        onSelect = { idx -> vm.put(HawkConfig.PLAY_SCALE, scales[idx].first) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    // 解码方式单行联动(2026-09-17):显示/写入**当前内核**那一份设置 —— IJK 与 EXO 独立开键、
                    // 各自记忆(IJK 软解 = 内核自带 ffmpeg;EXO 软解 = 系统软件解码器 c2.android.*,仅视频)
                    val isIjkKernel = state.playType == 1
                    val isExoKernel = state.playType == 2
                    val codec = if (isIjkKernel) state.ijkCodec else state.exoDecode
                    val decodeLabels = listOf(
                        stringResource(R.string.player_decode_hard),
                        stringResource(R.string.player_decode_soft),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_decode),
                        valueText = when (codec) {
                            DecodeSoft -> decodeLabels[1]
                            DecodeHard -> decodeLabels[0]
                            else -> codec
                        },
                        enabled = isIjkKernel || isExoKernel,
                        options = decodeLabels,
                        selectedIndex = if (codec == DecodeSoft) 1 else 0,
                        onSelect = { idx ->
                            vm.put(
                                if (isIjkKernel) HawkConfig.IJK_CODEC else HawkConfig.EXO_DECODE,
                                if (idx == 1) DecodeSoft else DecodeHard,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_behavior)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_ijk_cache_play),
                        checked = state.ijkCachePlay,
                        onCheckedChange = { vm.put(HawkConfig.IJK_CACHE_PLAY, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_tunnel),
                        checked = state.playTunnel,
                        onCheckedChange = { checked ->
                            if (checked && state.playRender != 1) vm.put(HawkConfig.PLAY_RENDER, 1)
                            vm.put(HawkConfig.PLAY_TUNNEL, checked)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_prefer_aac),
                        checked = state.preferAac,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_PREFER_AAC, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_music_page),
                        subtitle = stringResource(R.string.settings_music_page_subtitle),
                        checked = state.musicPlayerPage,
                        onCheckedChange = {
                            MusicSettings.setAutoOpenPage(it)
                            vm.refresh()
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

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
