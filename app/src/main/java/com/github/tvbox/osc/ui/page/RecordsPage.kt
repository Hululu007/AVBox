package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.api.AppBootstrap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.VodCollect
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SegmentStyle
import kotlinx.coroutines.flow.drop

private enum class RecordsMode {
    History,
    Collect,
}

private sealed interface RecordsDialog {
    data object ClearHistory : RecordsDialog
    data object ClearCollect : RecordsDialog
    data object DeleteSelectedHistory : RecordsDialog
    data object DeleteSelectedCollect : RecordsDialog
    data class DeleteHistoryItem(val item: VodInfo) : RecordsDialog
    data class DeleteCollectItem(val item: VodCollect) : RecordsDialog
}

@Composable
fun RecordsPage(
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val historyVm: HistoryViewModel = viewModel()
    val collectVm: CollectViewModel = viewModel()

    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()

    val historyItems by historyVm.items.collectAsStateWithLifecycle()
    val historyIncognito by historyVm.incognito.collectAsStateWithLifecycle()
    val collectItems by collectVm.items.collectAsStateWithLifecycle()

    var mode by rememberSaveable { mutableStateOf(RecordsMode.History) }
    val historyEdit by historyVm.editMode.collectAsStateWithLifecycle()
    val historySelected by historyVm.selected.collectAsStateWithLifecycle()
    val collectEdit by collectVm.editMode.collectAsStateWithLifecycle()
    val collectSelected by collectVm.selected.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<RecordsDialog?>(null) }

    val historyListState = rememberLazyGridState()
    val collectListState = rememberLazyGridState()

    val isHistory = mode == RecordsMode.History
    val editing = if (isHistory) historyEdit else collectEdit
    val canEdit = if (isHistory) {
        !historyIncognito && historyItems.isNotEmpty()
    } else {
        collectItems.isNotEmpty()
    }
    val selectedCount = if (isHistory) historySelected.size else collectSelected.size

    fun exitEdit() {
        historyVm.exitEdit()
        collectVm.exitEdit()
    }

    BackHandler(enabled = editing) { exitEdit() }

    LaunchedEffect(mode) {
        exitEdit()
        dialog = null
    }

    LaunchedEffect(historyItems) {
        historyVm.pruneSelection(historyItems.map { HistoryViewModel.key(it) }.toSet())
    }

    LaunchedEffect(collectItems) {
        collectVm.pruneSelection(collectItems.map { it.id }.toSet())
    }

    var historyScrollPending by remember { mutableStateOf(false) }

    LaunchedEffect(historyVm) {
        historyVm.scrollSignal.drop(1).collect { historyScrollPending = true }
    }
    LaunchedEffect(mode, historyScrollPending) {
        if (mode == RecordsMode.History && historyScrollPending) {
            historyScrollPending = false
            if (historyVm.items.value.isNotEmpty()) historyListState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        AppBootstrap.state.collect { boot ->
            if (boot is AppBootstrap.Boot.Ready) historyVm.resolveSourceNames()
        }
    }

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {
            Text(
                text = stringResource(R.string.tab_records),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {
            AnimatedContent(
                targetState = editing && canEdit,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "recordsTopAction",
            ) { managing ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (managing) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_check,
                            contentDescription = stringResource(R.string.common_done),
                            onClick = { exitEdit() },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.common_delete_selected),
                            enabled = selectedCount > 0,
                            onClick = {
                                dialog = if (isHistory) {
                                    RecordsDialog.DeleteSelectedHistory
                                } else {
                                    RecordsDialog.DeleteSelectedCollect
                                }
                            },
                        )
                    } else {
                        if (canEdit) {
                            ManageActionIcon(
                                iconRes = R.drawable.ic_edit,
                                contentDescription = stringResource(R.string.common_edit),
                                onClick = {
                                    if (isHistory) historyVm.enterEdit() else collectVm.enterEdit()
                                },
                            )
                        }
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = if (isHistory) {
                                stringResource(R.string.history_clear)
                            } else {
                                stringResource(R.string.collect_clear)
                            },
                            onClick = {
                                dialog = if (isHistory) {
                                    RecordsDialog.ClearHistory
                                } else {
                                    RecordsDialog.ClearCollect
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { topPad, _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(
                        label = stringResource(R.string.history_title),
                        value = RecordsMode.History,
                        iconPainter = painterResource(R.drawable.ic_tab_history),
                    ),
                    SegmentOption(
                        label = stringResource(R.string.common_collect),
                        value = RecordsMode.Collect,
                        iconPainter = painterResource(R.drawable.ic_tab_collect),
                    ),
                ),
                selectedValue = mode,
                onOptionSelected = { mode = it },
                style = SegmentStyle.Track,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                pressMorph = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp + navStart, end = 16.dp, top = topPad + 8.dp),
            )
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val toRight = targetState == RecordsMode.Collect
                    (
                        slideInHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) full / 4 else -full / 4
                        } + fadeIn(spring(stiffness = Spring.StiffnessMedium))
                        ).togetherWith(
                        slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) -full / 4 else full / 4
                        } + fadeOut(spring(stiffness = Spring.StiffnessMedium))
                    )
                },
                label = "recordsSegment",
            ) { m ->
                when (m) {
                    RecordsMode.History -> HistoryTab(
                        vm = historyVm,
                        listState = historyListState,
                        editMode = historyEdit,
                        selected = historySelected,
                        onToggleSelected = { historyVm.toggleSelected(it) },
                        onRequestDelete = { dialog = RecordsDialog.DeleteHistoryItem(it) },
                        navStart = navStart,
                        navBottom = navBottom,
                    )

                    RecordsMode.Collect -> CollectTab(
                        vm = collectVm,
                        listState = collectListState,
                        editMode = collectEdit,
                        selected = collectSelected,
                        onToggleSelected = { collectVm.toggleSelected(it) },
                        onRequestDelete = { dialog = RecordsDialog.DeleteCollectItem(it) },
                        navStart = navStart,
                        navBottom = navBottom,
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit

        RecordsDialog.ClearHistory -> ConfirmDeleteDialog(
            title = stringResource(R.string.history_clear),
            text = stringResource(R.string.history_clear_message),
            onConfirm = { historyVm.deleteAll() },
            onDismiss = { dialog = null },
        )

        RecordsDialog.ClearCollect -> ConfirmDeleteDialog(
            title = stringResource(R.string.collect_clear),
            text = stringResource(R.string.collect_clear_message),
            onConfirm = { collectVm.deleteAll() },
            onDismiss = { dialog = null },
        )

        RecordsDialog.DeleteSelectedHistory -> ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_selected),
            text = stringResource(R.string.history_delete_selected_message, historySelected.size),
            onConfirm = {
                val targets = historyItems.filter { HistoryViewModel.key(it) in historySelected }
                historyVm.deleteSelected(targets)
                exitEdit()
            },
            onDismiss = { dialog = null },
        )

        RecordsDialog.DeleteSelectedCollect -> ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_selected),
            text = stringResource(R.string.collect_delete_selected_message, collectSelected.size),
            onConfirm = {
                val targets = collectItems.filter { it.id in collectSelected }
                collectVm.deleteSelected(targets)
                exitEdit()
            },
            onDismiss = { dialog = null },
        )

        is RecordsDialog.DeleteHistoryItem -> ConfirmDeleteDialog(
            title = stringResource(R.string.history_delete_title),
            text = stringResource(
                R.string.history_delete_message,
                current.item.name ?: stringResource(R.string.common_unnamed),
            ),
            onConfirm = { historyVm.deleteSelected(listOf(current.item)) },
            onDismiss = { dialog = null },
        )

        is RecordsDialog.DeleteCollectItem -> ConfirmDeleteDialog(
            title = stringResource(R.string.detail_uncollect),
            text = stringResource(
                R.string.collect_uncollect_message,
                current.item.name ?: stringResource(R.string.common_unnamed),
            ),
            onConfirm = { collectVm.deleteSelected(listOf(current.item)) },
            onDismiss = { dialog = null },
        )
    }
}
