@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.avbox.osc.R
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.PlaybackProgress
import com.github.avbox.osc.ui.WindowSize
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.ProvideTmdbPosterLookup
import com.github.avbox.osc.ui.components.WeekdayChip
import com.github.avbox.osc.ui.components.WeekdayRes
import com.github.avbox.osc.util.SubscribeList
import kotlinx.coroutines.launch

@Composable
fun FollowingPage(
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: FollowingViewModel = viewModel()
    val context = LocalContext.current
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()

    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val editMode by vm.editMode.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val episodeTotals by vm.episodeTotals.collectAsStateWithLifecycle()
    val playedPercents by vm.playedPercents.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()

    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var deleteDialog by remember { mutableStateOf(false) }

    val follows = remember(items) { items.map { it.follow } }
    val visible = remember(items, selectedDay) { FollowListRules.visible(items, selectedDay) }
    val dayLabels = WeekdayRes.map { stringResource(it) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    BackHandler(enabled = editMode) { vm.exitEdit() }
    LaunchedEffect(follows) { vm.pruneSelection(follows.map { it.id }.toSet()) }
    LaunchedEffect(selectedDay) { if (visible.isNotEmpty()) listState.scrollToItem(0) }

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {
            Text(
                text = stringResource(R.string.tab_following),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {
            AnimatedContent(
                targetState = editMode,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "followingTopAction",
            ) { managing ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (managing) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_check,
                            contentDescription = stringResource(R.string.common_done),
                            onClick = { vm.exitEdit() },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.common_delete_selected),
                            enabled = selected.isNotEmpty(),
                            onClick = { deleteDialog = true },
                        )
                    } else if (items.isNotEmpty()) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_edit,
                            contentDescription = stringResource(R.string.common_edit),
                            onClick = { vm.enterEdit() },
                        )
                    }
                }
            }
        },
    ) { topPad, _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (items.isNotEmpty()) {
                WeekdayFilterBar(
                    topPad = topPad,
                    navStart = navStart,
                    items = items,
                    today = today,
                    selectedDay = selectedDay,
                    onSelectDay = { vm.selectDay(it) },
                )
                val currentDay = selectedDay
                Text(
                    text = if (currentDay == null) {
                        stringResource(R.string.following_section_week, visible.size)
                    } else {
                        stringResource(R.string.following_section_day, dayLabels[currentDay], visible.size)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp + navStart, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }

                items.isEmpty() -> LoadStateBox(
                    state = LoadState.Empty,
                    emptyText = stringResource(R.string.following_empty),
                    errorText = "",
                    retryText = "",
                    emptyIconRes = R.drawable.ic_tab_following,
                    modifier = Modifier.fillMaxSize(),
                )

                visible.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.following_day_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.selectDay(null) }) {
                        Text(stringResource(R.string.following_view_week))
                    }
                }

                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val columns = WindowSize.rowColumns((maxWidth - 32.dp - navStart).value.toInt(), 3)
                    ProvideTmdbPosterLookup(vm) {
                        LazyVerticalGrid(
                            state = listState,
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp + navStart,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 8.dp + navBottom,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visible, key = { it.follow.id }) { entry ->
                                val follow = entry.follow
                                FollowRow(
                                    follow = follow,
                                    history = entry.history,
                                    totalEpisodes = episodeTotals[EpisodeTotals.key(follow.sourceKey, follow.vodId)],
                                    playedPercent = playedPercents[PlaybackProgress.key(follow.sourceKey, follow.vodId)],
                                    watched = FollowListRules.isWatched(entry, selectedDay),
                                    editMode = editMode,
                                    selected = follow.id in selected,
                                    selectedDay = selectedDay,
                                    today = today,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    ),
                                    onClick = {
                                        if (editMode) {
                                            vm.toggleSelected(follow.id)
                                        } else {
                                            val cid = follow.cid.orEmpty()
                                            when {
                                                cid.isEmpty() || cid == AppGraph.followRepository.currentCid() ->
                                                    context.jumpToDetail(
                                                        follow.vodId, follow.sourceKey, follow.name, follow.pic,
                                                    )

                                                SubscribeList.vodUrls().contains(cid) ->
                                                    scope.launch {
                                                        reopenViaSubscription(
                                                            context = context,
                                                            cid = cid,
                                                            sourceKey = follow.sourceKey,
                                                            vodId = follow.vodId,
                                                            name = follow.name,
                                                            pic = follow.pic,
                                                        )
                                                    }

                                                else -> context.jumpToSearch(follow.name.orEmpty())
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (editMode) vm.toggleSelected(follow.id) else vm.enterEdit(follow.id)
                                    },
                                    onWatchedChange = { vm.setWatched(follow.id, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_selected),
            text = stringResource(R.string.following_delete_selected_message, selected.size),
            onConfirm = { vm.deleteSelected() },
            onDismiss = { deleteDialog = false },
        )
    }
}

@Composable
private fun WeekdayFilterBar(
    topPad: Dp,
    navStart: Dp,
    items: List<FollowEntry>,
    today: Int,
    selectedDay: Int?,
    onSelectDay: (Int?) -> Unit,
) {
    val counts = remember(items) { FollowListRules.dayCounts(items) }
    val labels = WeekdayRes.map { stringResource(it) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + navStart, end = 16.dp, top = topPad + 8.dp),
    ) {
        WeekdayChip(
            label = stringResource(R.string.following_week),
            selected = selectedDay == null,
            onClick = { onSelectDay(null) },
            modifier = Modifier.weight(1f),
        )
        labels.forEachIndexed { index, label ->
            WeekdayChip(
                label = label,
                selected = selectedDay == index,
                badgeCount = counts[index] ?: 0,
                todayOutline = index == today,
                onClick = { onSelectDay(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
