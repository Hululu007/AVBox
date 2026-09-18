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
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.AVBoxOptionSheet
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PlayerHelper
import xyz.doikki.videoplayer.player.VideoView

@Composable
fun PlaySettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    var optionSheet by remember { mutableStateOf<OptionSheetState?>(null) }

    fun openOptions(title: String, options: List<String>, currentIndex: Int, onSelect: (Int) -> Unit) {
        optionSheet = OptionSheetState(title, options, currentIndex, onSelect)
    }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "播放设置",
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
                    SettingsRow(
                        title = "播放内核",
                        valueText = PlayerHelper.getPlayerName(state.playType),
                        onClick = {
                            val types = PlayerHelper.getExistPlayerTypes().sortedDescending()
                            openOptions(
                                "播放内核",
                                types.map { PlayerHelper.getPlayerName(it) },
                                types.indexOf(state.playType).coerceAtLeast(0),
                            ) { idx -> vm.put(HawkConfig.PLAY_TYPE, types[idx]) }
                        },
                    )
                    Text(
                        text = "部分站点使用自己声明的内核",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "画面渲染",
                        valueText = PlayerHelper.getRenderName(state.playRender),
                        onClick = {
                            openOptions("画面渲染", listOf("SurfaceView", "TextureView"), 1 - state.playRender) { idx ->
                                val render = 1 - idx
                                if (render == 0 && state.playTunnel) vm.put(HawkConfig.PLAY_TUNNEL, false)
                                vm.put(HawkConfig.PLAY_RENDER, render)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "画面缩放",
                        valueText = PlayerHelper.getScaleName(state.playScale),
                        onClick = {
                            val scales = listOf(
                                VideoView.SCREEN_SCALE_DEFAULT to "默认",
                                VideoView.SCREEN_SCALE_16_9 to "16:9",
                                VideoView.SCREEN_SCALE_4_3 to "4:3",
                                VideoView.SCREEN_SCALE_MATCH_PARENT to "填充",
                                VideoView.SCREEN_SCALE_ORIGINAL to "原始",
                                VideoView.SCREEN_SCALE_CENTER_CROP to "裁剪",
                            )
                            openOptions(
                                "画面缩放",
                                scales.map { it.second },
                                scales.indexOfFirst { it.first == state.playScale },
                            ) { idx -> vm.put(HawkConfig.PLAY_SCALE, scales[idx].first) }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    // 解码方式单行联动(2026-09-17):显示/写入**当前内核**那一份设置 —— IJK 与 EXO 独立开键、
                    // 各自记忆(IJK 软解 = 内核自带 ffmpeg;EXO 软解 = 系统软件解码器 c2.android.*,仅视频)
                    val isIjkKernel = state.playType == 1
                    val isExoKernel = state.playType == 2
                    val codec = if (isIjkKernel) state.ijkCodec else state.exoDecode
                    SettingsRow(
                        title = "解码方式",
                        valueText = codec,
                        enabled = isIjkKernel || isExoKernel,
                        onClick = if (isIjkKernel || isExoKernel) {
                            {
                                openOptions("解码方式", listOf("硬解码", "软解码"), if (codec == "软解码") 1 else 0) { idx ->
                                    vm.put(
                                        if (isIjkKernel) HawkConfig.IJK_CODEC else HawkConfig.EXO_DECODE,
                                        if (idx == 1) "软解码" else "硬解码",
                                    )
                                }
                            }
                        } else null,
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "IJK 缓存播放",
                        checked = state.ijkCachePlay,
                        onCheckedChange = { vm.put(HawkConfig.IJK_CACHE_PLAY, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "隧道模式",
                        checked = state.playTunnel,
                        onCheckedChange = { checked ->
                            if (checked && state.playRender != 1) vm.put(HawkConfig.PLAY_RENDER, 1)
                            vm.put(HawkConfig.PLAY_TUNNEL, checked)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "AAC 优先",
                        checked = state.preferAac,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_PREFER_AAC, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = "音乐播放页",
                        subtitle = "识别为纯音频时自动打开",
                        checked = state.musicPlayerPage,
                        onCheckedChange = {
                            MusicSettings.setAutoOpenPage(it)
                            vm.refresh()
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    optionSheet?.let { sheet ->
        AVBoxOptionSheet(
            onDismissRequest = { optionSheet = null },
            title = sheet.title,
            options = sheet.options,
            selected = sheet.options.getOrNull(sheet.selectedIndex),
        ) { option ->
            sheet.onSelect(sheet.options.indexOf(option))
        }
    }
}
