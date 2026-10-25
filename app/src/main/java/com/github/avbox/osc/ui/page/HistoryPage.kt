@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.PlaybackProgress
import com.github.avbox.osc.ui.WindowSize
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.LoadStateBox
import kotlinx.coroutines.delay

private data class HistoryContent(
    val items: List<VodInfo>,
    val episodeTotals: Map<String, Int>,
    val playedPercents: Map<String, Int>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryTab(
    vm: HistoryViewModel,
    listState: LazyGridState,
    editMode: Boolean,
    selected: Set<String>,
    onToggleSelected: (String) -> Unit,
    onRequestDelete: (VodInfo) -> Unit,
    navStart: Dp,
    navBottom: Dp,
) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val episodeTotals by vm.episodeTotals.collectAsStateWithLifecycle()
    val playedPercents by vm.playedPercents.collectAsStateWithLifecycle()
    val placementAnim by vm.placementAnim.collectAsStateWithLifecycle()
    val incognito by vm.incognito.collectAsStateWithLifecycle()
    val nowMsState = rememberMinuteTickState()

    when {
        incognito -> LoadStateBox(
            state = LoadState.Empty,
            emptyText = stringResource(R.string.history_incognito),
            errorText = "",
            retryText = "",
            emptyIconRes = R.drawable.ic_empty_record,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = navBottom),
        )

        loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = navBottom),
            contentAlignment = Alignment.Center,
        ) {
            ContainedLoadingIndicator(Modifier.size(64.dp))
        }

        else -> AnimatedContent(
            targetState = HistoryContent(items, episodeTotals, playedPercents),
            contentKey = { it.items.isEmpty() },
            transitionSpec = {
                fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            },
            label = "historyContent",
        ) { content ->
            if (content.items.isEmpty()) {
                LoadStateBox(
                    state = LoadState.Empty,
                    emptyText = stringResource(R.string.history_empty),
                    errorText = "",
                    retryText = "",
                    emptyIconRes = R.drawable.ic_empty_record,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = navBottom),
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val columns = WindowSize.rowColumns((maxWidth - 32.dp - navStart).value.toInt(), 3)
                    LazyVerticalGrid(
                        state = listState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp + navStart,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 8.dp + navBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(content.items, key = { HistoryViewModel.key(it) }) { item ->
                            val itemKey = HistoryViewModel.key(item)
                            HistoryRow(
                                item = item,
                                totalEpisodes = content.episodeTotals[
                                    EpisodeTotals.key(item.sourceKey, item.id),
                                ],
                                playedPercent = content.playedPercents[
                                    PlaybackProgress.key(item.sourceKey, item.id),
                                ],
                                nowMs = nowMsState,
                                editMode = editMode,
                                selected = itemKey in selected,
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
                                    if (editMode) {
                                        onToggleSelected(itemKey)
                                    } else {
                                        context.jumpToDetail(item.id, item.sourceKey, item.name, item.pic)
                                    }
                                },
                                onLongClick = {
                                    if (editMode) onToggleSelected(itemKey) else onRequestDelete(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMinuteTickState(): State<Long> {
    val nowMs = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            nowMs.value = System.currentTimeMillis()
        }
    }
    return nowMs
}

private const val MINUTE_MS = 60_000L
