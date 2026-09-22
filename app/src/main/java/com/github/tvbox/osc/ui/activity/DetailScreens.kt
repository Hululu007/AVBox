@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.player.ui.playerDim
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.player.PlayerTipBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val revision by vm.revision.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val toast by vm.toastEvent.collectAsState()
    val finish by vm.finishEvent.collectAsState()
    val vodMenu = rememberVodCardMenuState()

    val configuration = LocalConfiguration.current
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = if (rotating) isLandscapeNow else full
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewBoxHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))

    val container = remember { activity.ensurePlayContainer().also { vm.playContainerRef = it } }

    LaunchedEffect(container, playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        while (true) {
            delay(300)
            if (!activity.musicPlaybackDetected()) continue
            if (!activity.handOffToMusicPlayer()) continue
            musicArmed = false
            return@LaunchedEffect
        }
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(activity, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(finish) {
        if (finish) {
            vm.consumeFinish()
            activity.finish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = if (fullBox) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewBoxHeight)
                    .background(Color.Black)
            },
        ) {
            AndroidView(
                factory = { container },
                modifier = Modifier.fillMaxSize(),
            )
            if (pageState is DetailViewModel.PageState.Loading && !fullBox) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        indicatorColor = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            PlayerTipOverlay()
            if (!fullBox) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_expand),
                    contentDescription = stringResource(R.string.detail_fullscreen_play),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            // vs_30 太小时该式子会变负(Compose 直接抛 IllegalArgumentException)，钳到 0
                            bottom = (16.dp + playerDim(R.dimen.vs_30) / 2 - 20.dp).coerceAtLeast(0.dp),
                        )
                        .size(40.dp)
                        .clickable { vm.setFullScreen(true) }
                        .padding(9.dp),
                )
            }
        }

        if (!fullBox) {
            when (val state = pageState) {
                is DetailViewModel.PageState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }

                is DetailViewModel.PageState.Empty -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                            errorText = "",
                            retryText = "",
                            modifier = Modifier.weight(1f),
                        )
                        SourceSection(vm, currentSourceName = null, revision = revision)
                    }
                }

                is DetailViewModel.PageState.Ready -> {
                    DetailContent(activity, vm, revision, onCardLongClick = { vodMenu.show(it) })
                }
            }
        }
    }

    EpisodeSheet(vm, revision)
    VodCardMenu(vodMenu)
}

@Composable
private fun DetailContent(
    activity: DetailActivity,
    vm: DetailViewModel,
    revision: Int,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val info = vm.vodInfo ?: return
    @Suppress("UNUSED_EXPRESSION") revision

    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val qualityOptions by vm.qualityOptions.collectAsState()
    val qualitySelected by vm.qualitySelected.collectAsState()
    val collected by vm.collected.collectAsState()
    var descExpanded by rememberSaveable { mutableStateOf(false) }

    val currentSource = ApiConfig.get().getSource(vm.firstsourceKey)
    val displaySourceName = currentSource?.name ?: vm.firstsourceKey

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "header") {
            val desc = remember(info.des) { removeHtmlTag(info.des) }
            Column(
                modifier = Modifier
                    .padding(start = 6.dp, end = 6.dp, top = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.name ?: "TVBox",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { activity.openMusicPlayer() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_detail_music_player),
                            contentDescription = stringResource(R.string.detail_music_player),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(onClick = { activity.playContainer?.showCast() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_detail_cast),
                            contentDescription = stringResource(R.string.common_cast),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(onClick = { vm.toggleCollect() }) {
                        AnimatedContent(
                            targetState = collected,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith
                                        (scaleOut(targetScale = 0.6f) + fadeOut())
                            },
                            label = "collectIcon",
                        ) { isCollected ->
                            Icon(
                                painter = painterResource(
                                    if (isCollected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect
                                ),
                                contentDescription = stringResource(if (isCollected) R.string.detail_uncollect else R.string.detail_collect),
                                tint = if (isCollected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                val metaParts = listOfNotNull(
                    if (info.year > 0) info.year.toString() else null,
                    info.area?.takeIf { it.isNotBlank() },
                    info.type?.takeIf { it.isNotBlank() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.detail_source, displaySourceName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                    }
                }
                if (desc.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { descExpanded = !descExpanded },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { descExpanded = !descExpanded },
                        ) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = stringResource(if (descExpanded) R.string.detail_collapse else R.string.detail_expand),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (descExpanded) 180f else 0f),
                            )
                        }
                    }
                }
            }
        }

        if (qualityOptions.size > 1) {
            item(key = "quality") {
                ChipRow(title = stringResource(R.string.detail_quality)) {
                    itemsIndexed(qualityOptions) { index, option ->
                        FilterChip(
                            selected = index == qualitySelected,
                            onClick = { vm.onQualityClick(index) },
                            label = { Text(option) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
        }

        if (flags.size > 1) {
            item(key = "flags") {
                ChipRow(title = stringResource(R.string.detail_line)) {
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            label = { Text(flag.name ?: "") },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
        }

        if (episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeRow(vm, info, episodes, playIndex, currentFlag)
            }
        }

        item(key = "sources") {
            SourceSection(vm, currentSourceName = displaySourceName, revision = revision)
        }

        item(key = "related") {
            RelatedSection(activity, vm, onCardLongClick)
        }
    }
}

@Composable
private fun EpisodeRow(
    vm: DetailViewModel,
    info: VodInfo,
    episodes: List<VodInfo.VodSeries>,
    playIndex: Int,
    currentFlag: String?,
) {
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.detail_episodes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            PillAction(
                iconRes = R.drawable.ic_episode_reverse,
                text = stringResource(if (info.reverseSort) R.string.detail_order_asc else R.string.detail_order_desc),
                onClick = { vm.toggleReverse() },
            )
            Spacer(Modifier.width(8.dp))
            PillAction(
                iconRes = R.drawable.ic_episode_grid_all,
                text = stringResource(R.string.common_all),
                onClick = { vm.showEpisodeSheet() },
            )
        }
        val listState = rememberLazyListState()
        var prevReverseSort by remember { mutableStateOf(info.reverseSort) }
        LaunchedEffect(playIndex, currentFlag, episodes.size, info.reverseSort) {
            if (episodes.isEmpty()) return@LaunchedEffect
            val reverseChanged = info.reverseSort != prevReverseSort
            prevReverseSort = info.reverseSort
            if (reverseChanged) {
                listState.scrollToItem(0)
            } else if (playIndex >= 0) {
                listState.scrollToItem(minOf(playIndex, episodes.size - 1))
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(episodes) { index, ep ->
                FilterChip(
                    selected = index == playIndex,
                    onClick = { vm.onEpisodeClick(index) },
                    label = {
                        Text(
                            text = ep.name ?: (index + 1).toString(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PillAction(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SourceSection(vm: DetailViewModel, currentSourceName: String?, revision: Int) {
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsState()
    val sourcesSearching by vm.sourcesSearching.collectAsState()
    if (!sourcesSearching && sourceChips.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(currentSourceName) {
        if (currentSourceName != null) listState.scrollToItem(0)
    }
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.detail_switch_source),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (sourcesSearching) {
                Text(
                    text = stringResource(R.string.detail_finding_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (currentSourceName != null) {
                item(key = "current") {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(currentSourceName) },
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
            itemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                FilterChip(
                    selected = false,
                    onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                    label = { Text(chip.name) },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RelatedSection(
    activity: DetailActivity,
    vm: DetailViewModel,
    onCardLongClick: (Movie.Video) -> Unit = {},
) {
    val relatedVideos by vm.relatedVideos.collectAsState()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = stringResource(R.string.detail_recommend),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.openVodCardOrDetail(video) },
                    onLongClick = { onCardLongClick(video) },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

@Composable
private fun ChipRow(title: String, content: LazyListScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

private fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EpisodeSheet(vm: DetailViewModel, revision: Int) {
    @Suppress("UNUSED_EXPRESSION") revision
    val show by vm.episodeSheet.collectAsState()
    if (!show) return
    val info = vm.vodInfo ?: return
    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex

    val groupCount = when {
        episodes.size > 400 -> 120
        episodes.size > 100 -> 60
        else -> 20
    }
    val groups = if (episodes.size > groupCount) {
        val result = ArrayList<String>()
        var i = 0
        while (i < episodes.size) {
            val end = minOf(i + groupCount, episodes.size)
            result.add("${i + 1} - $end")
            i += groupCount
        }
        result
    } else {
        emptyList()
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val gridScope = rememberCoroutineScope()
    var selectedGroup by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(show, currentFlag, playIndex) {
        if (show && playIndex >= 0) {
            selectedGroup = playIndex / groupCount
            if (playIndex in episodes.indices) gridState.scrollToItem(playIndex)
        }
    }

    AVBoxBottomSheet(
        onDismissRequest = { vm.dismissEpisodeSheet() },
        title = if (info.name.isNullOrEmpty()) {
                stringResource(R.string.detail_episodes)
            } else {
                stringResource(R.string.detail_episodes_of, info.name.orEmpty())
            },
        isScrollable = false,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        Column(modifier = Modifier.fillMaxWidth()) {
            if (flags.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            label = { Text(flag.name ?: "") },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(groups) { index, label ->
                        FilterChip(
                            selected = index == selectedGroup,
                            onClick = {
                                selectedGroup = index
                                gridScope.launch { gridState.scrollToItem(index * groupCount) }
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            val maxNameLength = episodes.maxOfOrNull { it.name?.length ?: 0 } ?: 0
            val gridColumnCount = when {
                maxNameLength <= 4 -> 4
                maxNameLength <= 12 -> 2
                else -> 1
            }
            val rowCount = if (episodes.isEmpty()) 0 else (episodes.size + gridColumnCount - 1) / gridColumnCount
            val gridContentHeight = (rowCount * 40).dp + (((rowCount - 1).coerceAtLeast(0)) * 8).dp
            val gridHeight = minOf(560.dp, gridContentHeight)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = gridState,
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumnCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = gridHeight),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(episodes) { index, ep ->
                    FilterChip(
                        selected = index == playIndex,
                        onClick = {
                            vm.onEpisodeClick(index)
                            dismissAnimated()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        label = {
                            Text(
                                text = ep.name ?: (index + 1).toString(),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTipOverlay() {
    val tip = PlayerTipBridge.state
    if (!tip.loading && !tip.err) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (tip.loading) {
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_error),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (tip.msg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = tip.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}
