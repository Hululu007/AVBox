@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.ui.components.FilterSheet
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.WindowSize
import com.github.tvbox.osc.ui.page.PartitionListVM
import com.github.tvbox.osc.ui.page.dispatchVodCardClick
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.page.shouldPrefetchNextPage
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

class PartitionListActivity : BaseActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SORT = "sort_json"
        private const val EXTRA_VIDEOS = "videos_json"
        const val MODE_PARTITION = "partition"
        const val MODE_SEARCH = "search"
        const val MODE_FOLDER = "folder"

        fun startForPartition(context: Context, sort: MovieSort.SortData) {
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_PARTITION)
                putExtra(EXTRA_TITLE, sort.name ?: "")
                putExtra(EXTRA_SORT, Gson().toJson(sort))
            })
        }

        fun startForFolder(context: Context, folderId: String, folderName: String) {
            val sort = MovieSort.SortData(folderId, folderName)
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_FOLDER)
                putExtra(EXTRA_TITLE, folderName)
                putExtra(EXTRA_SORT, Gson().toJson(sort))
            })
        }

        fun startForSearch(context: Context, videos: List<Movie.Video>, title: String) {
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SEARCH)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_VIDEOS, Gson().toJson(videos))
            })
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                // 独立 Activity 页面:套窗口根槽位,弹层无论写在哪都能全屏弹出(见 SheetHostScaffold)
                SheetHostScaffold {
                    PartitionListScreen(
                        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARTITION,
                        title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                        sortJson = intent.getStringExtra(EXTRA_SORT),
                        videosJson = intent.getStringExtra(EXTRA_VIDEOS),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartitionListScreen(mode: String, title: String, sortJson: String?, videosJson: String?) {
    val context = LocalContext.current
    val vm: PartitionListVM = viewModel()
    var filterOpen by remember { mutableStateOf(false) }
    val ui by vm.ui.collectAsState()
    val vodMenu = rememberVodCardMenuState()

    LaunchedEffect(sortJson) {
        if (mode != PartitionListActivity.MODE_SEARCH && sortJson != null) {
            vm.initIfNeed(Gson().fromJson(sortJson, MovieSort.SortData::class.java))
        }
    }
    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    val searchVideos = remember(videosJson) {
        if (videosJson == null) {
            emptyList()
        } else {
            val type = object : TypeToken<List<Movie.Video>>() {}.type
            Gson().fromJson<List<Movie.Video>>(videosJson, type)
        }
    }

    AppTopBarScaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            BarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back)) {
                (context as? Activity)?.finish()
            }
        },
        actions = {
            if (mode == PartitionListActivity.MODE_PARTITION) {
                val selectedCount = vm.sort?.filterSelectCount() ?: 0
                BarActionBox(
                    R.drawable.ic_filter,
                    stringResource(R.string.common_filter),
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ) { filterOpen = true }
            }
        },
    ) { topPad, _ ->
        when {
            mode == PartitionListActivity.MODE_SEARCH -> VideoGrid(
                videos = searchVideos,
                onCardClick = { video -> context.openVodCardOrDetail(video) },
                onCardLongClick = { video -> vodMenu.show(video) },
                onLoadMore = {},
                topPadding = topPad - 20.dp,
            )

            ui.state == PartitionListVM.State.Loading -> LoadStateBox(
                state = LoadState.Loading,
                emptyText = "",
                errorText = "",
                retryText = "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            ui.state == PartitionListVM.State.Empty -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = stringResource(R.string.common_empty_content),
                errorText = "",
                retryText = "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> VideoGrid(
                videos = ui.videos,
                onCardClick = { video -> context.dispatchVodCardClick(video, onAction = { vm.runAction(it) }) },
                onCardLongClick = { video -> vodMenu.show(video) },
                onLoadMore = { vm.loadMore() },
                enableLoadMore = true,
                topPadding = topPad - 20.dp,
            )
        }
    }

    VodCardMenu(vodMenu)

    if (filterOpen) {
        vm.sort?.let { sort ->
            FilterSheet(
                sort = sort,
                onDismiss = { filterOpen = false },
                onConfirm = { selection ->
                    vm.applyFilter(selection)
                },
            )
        }
    }
}

@Composable
private fun BarActionBox(
    iconRes: Int,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun VideoGrid(
    videos: List<Movie.Video>,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onLoadMore: () -> Unit = {},
    enableLoadMore: Boolean = false,
    topPadding: Dp = 0.dp,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val gridColumns = WindowSize.gridColumns(
        availableWidthDp = (maxWidth - 32.dp).value.toInt(),
        minColumns = 3,
    )
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding + 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(videos, key = { i, video -> "${i}_${video.id}_${video.name}" }) { _, video ->
            VodCard(
                video = video,
                onClick = { onCardClick(video) },
                onLongClick = { onCardLongClick(video) },
            )
        }
        if (enableLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(videos.size) { onLoadMore() }
            }
        }
    }
    if (enableLoadMore) {
        LaunchedEffect(gridState, videos.size) {
            snapshotFlow { gridState.layoutInfo }
                .first { info ->
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@first false
                    shouldPrefetchNextPage(last, info.totalItemsCount, gridColumns)
                }
            onLoadMore()
        }
    }
    }
}
