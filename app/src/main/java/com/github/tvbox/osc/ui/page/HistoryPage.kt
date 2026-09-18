@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.tvbox.osc.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PlaybackProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class HistoryViewModel : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodInfo>>(emptyList())
    val episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        EventBus.getDefault().register(this)
        refresh()
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    val scrollSignal = MutableStateFlow(0)

    val placementAnim = MutableStateFlow(false)

    fun refresh(scrollToTop: Boolean = false) {
        if (items.value.isEmpty()) loading.value = true
        if (scrollToTop) placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val limit = HistoryHelper.getHisNum(KV.get(HawkConfig.HISTORY_NUM, 0))
            val all = RoomDataManger.getAllVodRecord(limit)
            if (HistoryMerge.isEnabled()) {
                // 历史合并:同一部剧只保留最新一条,被合并掉的旧记录直接清库(上游"历史合并"语义,见 HistoryMerge)
                val (kept, dropped) = HistoryMerge.dedupe(all) { it.name }
                dropped.forEach { RoomDataManger.deleteVodRecord(it.sourceKey, it) }
                items.value = kept
            } else {
                items.value = all
            }
            episodeTotals.value = EpisodeTotals.snapshot()
            playedPercents.value = PlaybackProgress.snapshot()
            resolveSourceNames()
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) refresh(scrollToTop = true)
        else if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) resolveSourceNames()
    }

    private var resolveJob: Job? = null

    fun resolveSourceNames() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch(Dispatchers.IO) {
            val list = items.value
            if (list.isEmpty()) return@launch
            val cache = KV.get(HawkConfig.SOURCE_NAME_CACHE, HashMap<String, String>())
            var cacheChanged = false
            var listChanged = false
            list.forEach { info ->
                val key = info.sourceKey
                val resolved = if (key.isNullOrEmpty()) {
                    ""
                } else {
                    val current = ApiConfig.get().getSource(key)?.name
                    if (!current.isNullOrEmpty()) {
                        if (cache[key] != current) {
                            cache[key] = current
                            cacheChanged = true
                        }
                        current
                    } else {
                        cache[key] ?: key
                    }
                }
                if (info.sourceName != resolved) {
                    info.sourceName = resolved
                    listChanged = true
                }
            }
            if (cacheChanged) KV.put(HawkConfig.SOURCE_NAME_CACHE, cache)
            if (listChanged) items.value = list.toList()
        }
    }

    fun deleteOne(item: VodInfo) {
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodRecord(item.sourceKey, item)
            refresh()
        }
    }

    fun deleteAll() {
        placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodRecordAll()
            refresh()
        }
    }

    companion object {
        fun key(item: VodInfo): String = item.sourceKey + "|" + item.id
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(vm: HistoryViewModel = viewModel(), bottomPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val episodeTotals by vm.episodeTotals.collectAsState()
    val playedPercents by vm.playedPercents.collectAsState()
    val placementAnim by vm.placementAnim.collectAsState()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VodInfo?>(null) }

    val listState = rememberLazyListState()

    LaunchedEffect(vm) {
        vm.scrollSignal.collect {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        AppBootstrap.state.collect { boot ->
            if (boot == AppBootstrap.Boot.Ready) vm.resolveSourceNames()
        }
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "历史",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {
            
            ManageActionIcon(
                iconRes = R.drawable.ic_delete,
                contentDescription = "清空历史",
                onClick = { showDeleteAllDialog = true },
            )
        },
    ) { topPad, _ ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
            }

            items.isEmpty() -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = "暂无观看历史",
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_empty_record,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topPad + 8.dp,
                    bottom = 8.dp + bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { HistoryViewModel.key(it) }) { item ->
                    HistoryRow(
                        item = item,
                        totalEpisodes = episodeTotals[EpisodeTotals.key(item.sourceKey, item.id)],
                        playedPercent = playedPercents[PlaybackProgress.key(item.sourceKey, item.id)],
                        modifier = Modifier.animateItem(
                            fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            placementSpec = if (placementAnim) {
                                spring(stiffness = Spring.StiffnessMediumLow)
                            } else {
                                null
                            },
                            fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        ),
                        onClick = {
                            context.jumpToDetail(item.id, item.sourceKey, item.name, item.pic)
                        },
                        onLongClick = { deleteTarget = item },
                    )
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = "清空历史",
            text = "将删除全部观看历史记录，此操作不可恢复",
            onConfirm = { vm.deleteAll() },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = "删除记录",
            text = "删除「${target.name ?: "未命名"}」的观看记录？",
            onConfirm = { vm.deleteOne(target) },
            onDismiss = { deleteTarget = null },
        )
    }
}

private const val PROGRESS_ENTER_DURATION_MS = 600

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    item: VodInfo,
    totalEpisodes: Int?,
    playedPercent: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var progressEntered by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.pic,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!item.sourceName.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                    }
                }
                Text(
                    text = if (item.playNote.isNullOrEmpty()) {
                        item.note ?: ""
                    } else {
                        "上次看到${item.playNote}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val numberedEpisode = item.playNote.isNullOrEmpty() || EpisodeTotals.isNumberedEpisode(item.playNote)
                val eps = if (numberedEpisode) {
                    totalEpisodes ?: parseEpisodeTotal(item.note) ?: parseEpisodeTotal(item.state)
                } else {
                    null
                }
                val episodeFraction = eps?.let { total ->
                    (item.playIndex + 1).coerceIn(1, total).toFloat() / total
                }
                val barProgress = playedPercent?.let { it / 100f } ?: episodeFraction
                if (barProgress != null) {
                    val barColor = MaterialTheme.colorScheme.primary
                    val progressAnim = remember {
                        Animatable(if (progressEntered) barProgress else 0f)
                    }
                    LaunchedEffect(barProgress) {
                        val spec = if (progressEntered) {
                            ProgressIndicatorDefaults.ProgressAnimationSpec
                        } else {
                            tween(PROGRESS_ENTER_DURATION_MS)
                        }
                        progressEntered = true
                        progressAnim.animateTo(barProgress, spec)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { progressAnim.value },
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp),
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            drawStopIndicator = {},
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (eps != null) {
                                "第${(item.playIndex + 1).coerceIn(1, eps)}集/共${eps}集"
                            } else {
                                "已看${(barProgress * 100).roundToInt()}%"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(5.dp))
                }
            }
        }
    }
}

private val EpisodeTotalRegex = Regex("(\\d+)\\s*[集期]")

private fun parseEpisodeTotal(note: String?): Int? {
    val total = note?.let { EpisodeTotalRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
    return total.takeIf { it in 2..1000 }
}

@Composable
internal fun ManageActionIcon(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(40.dp)
            .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
