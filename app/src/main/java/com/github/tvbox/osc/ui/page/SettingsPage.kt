@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.AVBoxOptionSheet
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PlaySettingsActivity
import com.github.tvbox.osc.ui.activity.PreferenceSettingsActivity
import com.github.tvbox.osc.ui.activity.PreloadSettingsActivity
import com.github.tvbox.osc.ui.activity.ThemeSettingsActivity
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val playType: Int,
    val playRender: Int,
    val playScale: Int,
    val ijkCodec: String,
    val exoDecode: String,
    val ijkCachePlay: Boolean,
    val playTunnel: Boolean,
    val preferAac: Boolean,
    val musicPlayerPage: Boolean,
    val autoSwitchLine: Boolean,
    val m3u8Purify: Boolean,
    val incognito: Boolean,
    val gestureControlDisabled: Boolean,
    val navAnimationDisabled: Boolean,
    val danmuOpen: Boolean,
    val danmuApi: String,
    val defaultLoadLive: Boolean,
    val historyNumIndex: Int,
    val historyMerge: Boolean,
    val searchThreads: Int,
    val longPressSpeed: Int,
    val bufferTimes: Int,
    val preloadNextEpisode: Boolean,
    val preloadDuration: Int,
    val playCache: Boolean,
    val exoCacheSizeMb: Int,
    val apiUrl: String,
    val apiLines: List<String>,
    val dohIndex: Int,
    val cacheSizeText: String = "",
) {
    val apiLineVisible: Boolean
        get() = HistoryHelper.isApiLineUrl(apiUrl)
}

class SettingsViewModel : ViewModel() {
    private var cacheSizeText: String = ""

    private val _state = mutableStateOf(loadState())

    init {
        refresh()
    }

    val state: androidx.compose.runtime.State<SettingsState> = _state

    fun refresh() {
        _state.value = loadState()
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { FileUtils.formatCacheSize(FileUtils.getCacheSize()) }
            applyCacheSizeText(text)
        }
    }

    fun clearCache(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                FileUtils.clearCache()
                FileUtils.formatCacheSize(FileUtils.getCacheSize())
            }
            applyCacheSizeText(text)
            onCleared()
        }
    }

    private fun applyCacheSizeText(text: String) {
        if (text != cacheSizeText) {
            cacheSizeText = text
            _state.value = _state.value.copy(cacheSizeText = text)
        }
    }

    private fun loadState(): SettingsState = SettingsState(
        playType = KV.get(HawkConfig.PLAY_TYPE, 2),
        playRender = KV.get(HawkConfig.PLAY_RENDER, 1),
        playScale = KV.get(HawkConfig.PLAY_SCALE, 0),
        ijkCodec = KV.get(HawkConfig.IJK_CODEC, "硬解码"),
        exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码"),
        ijkCachePlay = KV.get(HawkConfig.IJK_CACHE_PLAY, false),
        playTunnel = KV.get(HawkConfig.PLAY_TUNNEL, false),
        preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false),
        musicPlayerPage = MusicSettings.autoOpenPage(),
        autoSwitchLine = KV.get(HawkConfig.AUTO_SWITCH_LINE, false),
        m3u8Purify = KV.get(HawkConfig.M3U8_PURIFY, false),
        incognito = KV.get(HawkConfig.INCOGNITO, false),
        gestureControlDisabled = KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false),
        navAnimationDisabled = KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false),
        danmuOpen = KV.get(HawkConfig.DANMU_OPEN, true),
        danmuApi = KV.get(HawkConfig.DANMU_API, ""),
        defaultLoadLive = KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false),
        historyNumIndex = KV.get(HawkConfig.HISTORY_NUM, 0),
        historyMerge = HistoryMerge.isEnabled(),
        searchThreads = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT),
        longPressSpeed = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT),
        bufferTimes = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT),
        preloadNextEpisode = KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false),
        preloadDuration = KV.get(HawkConfig.PRELOAD_DURATION, HawkConfig.PRELOAD_DURATION_DEFAULT),
        playCache = KV.get(HawkConfig.PLAY_CACHE, false),
        exoCacheSizeMb = KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT),
        apiUrl = KV.get(HawkConfig.API_URL, ""),
        apiLines = KV.get(HawkConfig.API_LINE_LIST, ArrayList()),
        dohIndex = KV.get(HawkConfig.DOH_URL, 0),
        cacheSizeText = cacheSizeText,
    )

    fun <T> put(key: String, value: T) {
        KV.put(key, value)
        refresh()
    }
}

class OptionSheetState(
    val title: String,
    val options: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
)

@Composable
fun SettingsPage(vm: SettingsViewModel = viewModel(), bottomPadding: Dp = 0.dp) {
    val state by vm.state
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshCacheSize() }
    val context = LocalContext.current
    var optionSheet by remember { mutableStateOf<OptionSheetState?>(null) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (ignored: Exception) {
            LOG.d("SettingsPage", "read versionName failed")
            ""
        }
    }
    var aboutSheet by remember { mutableStateOf(false) }

    val listState = rememberScrollState()

    fun openOptions(title: String, options: List<String>, currentIndex: Int, onSelect: (Int) -> Unit) {
        optionSheet = OptionSheetState(title, options, currentIndex, onSelect)
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(topPad - 20.dp))

            AppInfoHeaderCard(versionName)

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = "配置管理",
                        subtitle = "导入或删除订阅源",
                        iconRes = R.drawable.ic_settings_api,
                        onClick = { ConfigManageActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "主题设置",
                        subtitle = "修改应用的配色和效果",
                        iconRes = R.drawable.ic_settings_theme,
                        onClick = { ThemeSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "播放设置",
                        subtitle = "播放核心和解码方式",
                        iconRes = R.drawable.ic_settings_play,
                        onClick = { PlaySettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "偏好设置",
                        subtitle = "修改应用的使用偏好",
                        iconRes = R.drawable.ic_settings_preference,
                        onClick = { PreferenceSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = "预载设置",
                        subtitle = "播放视频时预加载",
                        iconRes = R.drawable.ic_settings_preload,
                        onClick = { PreloadSettingsActivity.start(context) },
                    )
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsOptionMenuRow(
                        title = "默认启动页",
                        subtitle = "首次打开应用的所在位置",
                        iconRes = R.drawable.ic_settings_start,
                        valueText = if (state.defaultLoadLive) "直播" else "点播",
                        options = listOf("点播", "直播"),
                        selectedIndex = if (state.defaultLoadLive) 1 else 0,
                        onSelect = { idx -> vm.put(HawkConfig.DEFAULT_LOAD_LIVE, idx == 1) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = "历史记录上限",
                        subtitle = "最多保留多少条记录",
                        iconRes = R.drawable.ic_settings_history,
                        valueText = HistoryHelper.getHistoryNumName(state.historyNumIndex),
                        options = listOf(0, 1, 2).map { HistoryHelper.getHistoryNumName(it) },
                        selectedIndex = state.historyNumIndex,
                        onSelect = { idx -> vm.put(HawkConfig.HISTORY_NUM, idx) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "清除缓存",
                        subtitle = "清理应用的使用缓存",
                        iconRes = R.drawable.ic_delete,
                        valueText = state.cacheSizeText,
                        onClick = {
                            vm.clearCache {
                                Toast.makeText(context, "清理成功", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsOptionMenuRow(
                        title = "DOH",
                        subtitle = "安全DNS",
                        iconRes = R.drawable.ic_settings_doh,
                        valueText = OkGoHelper.dnsHttpsList.getOrNull(state.dohIndex) ?: "关闭",
                        options = OkGoHelper.dnsHttpsList,
                        selectedIndex = state.dohIndex,
                        onSelect = { idx -> vm.put(HawkConfig.DOH_URL, idx) },
                    )
                }
            }

            if (state.apiLineVisible) {
                SettingsGroup(title = null) {
                    SettingsCard(SettingsCardPosition.SINGLE) {
                        SettingsRow(
                            title = "接口线路",
                            valueText = currentLineName(state),
                            onClick = {
                                val lines = state.apiLines
                                openOptions(
                                    "接口线路",
                                    lines.map { HistoryHelper.getApiLineName(it) },
                                    currentLineIndex(state),
                                ) { idx ->
                                    val newApi = lines.getOrNull(idx)?.let { HistoryHelper.getApiLineUrl(it) }
                                    if (!newApi.isNullOrEmpty()) {
                                        val oldApi = KV.get(HawkConfig.API_URL, "")
                                        val followLive = ApiConfig.isLiveFollowVod()
                                        KV.put(HawkConfig.API_URL, newApi)
                                        if (followLive) KV.put(HawkConfig.LIVE_API_URL, "")
                                        vm.refresh()
                                        if (oldApi != newApi) {
                                            AppBootstrap.onApiUrlChanged()
                                        } else {
                                            ApiConfig.get().invalidateLiveConfig()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = "关于",
                        subtitle = "查看详细信息",
                        iconRes = R.drawable.ic_settings_about,
                        onClick = { aboutSheet = true },
                    )
                }
            
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = "访问 GitHub 仓库",
                        subtitle = "访问项目源代码仓库",
                        iconRes = R.drawable.ic_settings_github,
                        onClick = { openExternalUrl(context, GITHUB_REPO_URL) },
                    )
                }
            }
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

    if (aboutSheet) {
        AboutSheet(versionName = versionName, onDismiss = { aboutSheet = false })
    }

}

@Composable
private fun AppInfoHeaderCard(versionName: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(scheme.primaryContainer, scheme.tertiaryContainer),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AVBox",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer,
                )
                Text(
                    text = "TVBox手机版",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Surface(
                    shape = CircleShape,
                    color = scheme.onPrimaryContainer,
                    contentColor = scheme.primaryContainer,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        text = if (versionName.isEmpty()) "v-.-.-" else "v$versionName",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(84.dp)
                    .scale(1.7f),
            )
        }
    }
}

@Composable
private fun AboutSheet(versionName: String, onDismiss: () -> Unit) {
    AVBoxBottomSheet(onDismissRequest = onDismiss, title = "关于") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            if (versionName.isNotEmpty()) {
                Text(
                    text = "版本 v" + versionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "本软件只提供聚合展示功能，所有资源均来自互联网，软件不参与任何内置、制作、上传、储存、下载等内容，也不接受任何捐赠、打赏、付费等谋利行为，软件仅供开源学习参考, 请于安装后24小时内删除。\n\n打包分发请保留出处\nhttps://github.com/CatVodTVOfficial/TVBoxOSC\nhttps://github.com/q215613905/TVBoxOS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun currentLineName(state: SettingsState): String {
    val current = HistoryHelper.getApiLineUrl(state.apiUrl)
    val line = state.apiLines.firstOrNull { HistoryHelper.getApiLineUrl(it) == current }
    return if (line == null) state.apiUrl else HistoryHelper.getApiLineName(line)
}

private fun currentLineIndex(state: SettingsState): Int {
    val current = HistoryHelper.getApiLineUrl(state.apiUrl)
    return state.apiLines.indexOfFirst { HistoryHelper.getApiLineUrl(it) == current }.coerceAtLeast(0)
}

private const val GITHUB_REPO_URL = "https://github.com/XiaochangXu/AVBox"

private fun openExternalUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TextEditDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("请输入 API 地址") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") }
        },
    )
}
