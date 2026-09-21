@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.tvbox.osc.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.WindowSize
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class CollectViewModel : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodCollect>>(emptyList())

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
            items.value = RoomDataManger.getAllVodCollect()
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_COLLECT_REFRESH) refresh(scrollToTop = true)
    }

    fun deleteOne(item: VodCollect) {
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodCollect(item.id)
            refresh()
        }
    }

  
    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodCollectAll()
            refresh()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectPage(
    vm: CollectViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // 页面保持全出血(背景延伸到导航栏之下,玻璃才有内容可取),只把内容让开
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val placementAnim by vm.placementAnim.collectAsState()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VodCollect?>(null) }

    val listState = rememberLazyGridState()

    LaunchedEffect(vm) {
        vm.scrollSignal.collect {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {
            Text(
                text = stringResource(R.string.common_collect),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {

            ManageActionIcon(
                iconRes = R.drawable.ic_delete,
                contentDescription = stringResource(R.string.collect_clear),
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
                state = com.github.tvbox.osc.ui.components.LoadState.Empty,
                emptyText = stringResource(R.string.collect_empty),
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_empty_record,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val gridColumns = WindowSize.gridColumns(
                availableWidthDp = (maxWidth - 32.dp - navStart).value.toInt(),
                minColumns = 2,
            )
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier.fillMaxSize(),
                
                contentPadding = PaddingValues(
                    start = 16.dp + navStart,
                    end = 16.dp,
                    top = topPad + 8.dp,
                    
                    bottom = 8.dp + navBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    CollectCard(
                        item = item,
                    
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
                            context.jumpToDetail(item.vodId, item.sourceKey, item.name, item.pic, collect = true)
                        },
                        onLongClick = { deleteTarget = item },
                    )
                }
            }
            }
        }
    }

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.collect_clear),
            text = stringResource(R.string.collect_clear_message),
            onConfirm = { vm.deleteAll() },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.detail_uncollect),
            text = stringResource(
                R.string.collect_uncollect_message,
                target.name ?: stringResource(R.string.common_unnamed),
            ),
            onConfirm = { vm.deleteOne(target) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectCard(
    item: VodCollect,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = item.pic,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f),
                    )
                ),
        )
        Text(
            text = item.name ?: "",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        )
    }
}
