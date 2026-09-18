@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.github.tvbox.osc.ui.page

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.activity.SearchActivity
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.HeroCarousel
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.PressableCard
import com.github.tvbox.osc.ui.components.SearchSettingsSheet
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SkeletonBox
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.theme.cardContainer
import com.kyant.capsule.ContinuousCapsule

@Composable
fun HomePage(vm: HomeViewModel, bottomPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsState()
    val sources by vm.sources.collectAsState()
    val rec by vm.rec.collectAsState()
    val partitions by vm.partitions.collectAsState()
    val vodMenu = rememberVodCardMenuState()

    val listState = rememberLazyListState()

    val pullState = rememberPullToRefreshState()

    val pageLoading by vm.pageLoading.collectAsState()
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    var showSourceSheet by remember { mutableStateOf(false) }
    var showSearchSettings by remember { mutableStateOf(false) }
    var policyTick by remember { mutableStateOf(0) }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.cardContainer)
                        .heightIn(min = 40.dp)
                        .clickable { showSourceSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val capsuleLogo = currentSource?.icon?.takeIf { it.isNotEmpty() }
                        ?: ApiConfig.get().configLogo.takeIf { it.isNotEmpty() }
                    if (!capsuleLogo.isNullOrEmpty()) {
                        AsyncImage(
                            model = capsuleLogo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(50)),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentSource?.name?.takeIf { it.isNotEmpty() } ?: "订阅源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .clickable { showSearchSettings = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = "搜索设置",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .clickable {
                        context.startActivity(Intent(context, SearchActivity::class.java))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { topPad, _ ->
        when {
            pageLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }
            }
            sources.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_empty_record),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "尚未配置订阅接口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ConfigManageActivity.start(context) }) {
                            Text("添加订阅")
                        }
                    }
                }
            }
            else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                contentPadding = PaddingValues(top = topPad + 8.dp, bottom = 88.dp + bottomPadding),
            ) {
                item(key = "hero") {
                    if (rec.state == HomeViewModel.PartitionState.Ready && rec.videos.isNotEmpty()) {
                        HeroCarousel(
                            videos = rec.videos.take(5),
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                        )
                    } else if (rec.state == HomeViewModel.PartitionState.Loading) {
                        SkeletonBox(
                            modifier = Modifier
                                .fillParentMaxWidth(0.78f)
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(24.dp)),
                            shape = RoundedCornerShape(24.dp),
                        )
                    } else if (rec.state == HomeViewModel.PartitionState.Error) {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth(0.78f)
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceBright),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadStateBox(
                                state = LoadState.Error(),
                                emptyText = "",
                                errorText = "首页加载超时，请检查网络后重试",
                                retryText = "重试",
                                onRetry = { vm.loadHome() },
                            )
                        }
                    }
                }
                if (rec.state != HomeViewModel.PartitionState.Empty &&
                    (rec.state == HomeViewModel.PartitionState.Loading || rec.videos.size > 5)
                ) {
                    item(key = "rec") {
                        PartitionSection(
                            title = "推荐",
                            state = rec.state,
                            videos = rec.videos.drop(5),
                            onLoadMore = {},
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                            onCardLongClick = { video -> vodMenu.show(video) },
                            cardWidth = 140.dp,
                        )
                    }
                }
                items(partitions, key = { it.sort.id }) { p ->
                    PartitionSection(
                        title = p.sort.name ?: "",
                        state = p.state,
                        videos = p.videos,
                        onLoadMore = { vm.loadMorePartition(p) },
                        onCardClick = { video -> handleCardClick(vm, video, context) },
                        onCardLongClick = { video -> vodMenu.show(video) },
                        onOpenAll = {
                            PartitionListActivity.startForPartition(context, p.sort)
                        },
                        onRetry = { vm.retryPartition(p) },
                    )
                }
            }
        }
        }

        if (sources.isNotEmpty()) {
            HomePullRefreshIndicator(
                state = pullState,
                isRefreshing = false,
                topPadding = topPad,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        FloatingActionButton(
            onClick = { context.startActivity(Intent(context, LivePlayActivity::class.java)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = bottomPadding),
        ) {
            Icon(painter = painterResource(R.drawable.ic_live_fab), contentDescription = "直播")
        }
    }

    if (showSourceSheet) {
        AVBoxBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            title = "订阅源",
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isScrollable = false,
        ) {
            val dismissAnimated = LocalSheetDismiss.current
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    SettingsGroup(title = "卡片点击状态:进入搜索 / 进入详情(按源自动判断,点标记可手动改)") {
                        sources.forEachIndexed { index, bean ->
                            val selected = bean.key == currentSource?.key
                            val policy = remember(bean.key, policyTick) { VodCardPolicy.policyOf(bean.key) }
                            val position = when {
                                sources.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == sources.size - 1 -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            }
                            SettingsCard(
                                position = position,
                                color = MaterialTheme.colorScheme.surfaceBright,
                            ) {
                                SettingsOptionRow(
                                    title = bean.name ?: bean.key,
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            vm.switchSource(bean)
                                        }
                                        dismissAnimated()
                                    },
                                    trailing = {
                                        CardPolicyPill(
                                            policy = policy,
                                            modifier = Modifier.padding(end = 6.dp),
                                        ) {
                                            VodCardPolicy.setPolicy(bean.key, policy.toggled())
                                            policyTick++
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsGroup(title = null) {
                        SettingsCard(
                            position = SettingsCardPosition.SINGLE,
                            color = MaterialTheme.colorScheme.surfaceBright,
                        ) {
                            SettingsRow(
                                title = "配置管理",
                                onClick = {
                                    dismissAnimated()
                                    ConfigManageActivity.start(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearchSettings) {
        SearchSettingsSheet(onDismiss = { showSearchSettings = false })
    }

    VodCardMenu(vodMenu)
}

@Composable
private fun HomePullRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .offset(y = topPadding)
            .size(48.dp),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        elevation = 0.dp,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier = Modifier
                        .size(48.dp)
                        .drawWithContent {
                            val progress = state.distanceFraction
                            if (progress > 1f) {
                                rotate(degrees = -(progress - 1f) * 180f) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                )
            }
        }
    }
}

private fun handleCardClick(vm: HomeViewModel, video: Movie.Video, context: android.content.Context) {
    context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
}

@Composable
private fun CardPolicyPill(policy: SourceCardPolicy, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = policy.label,
        style = MaterialTheme.typography.labelMedium,
        color = if (policy == SourceCardPolicy.DETAIL) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PartitionSection(
    title: String,
    state: HomeViewModel.PartitionState,
    videos: List<Movie.Video>,
    onLoadMore: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    cardWidth: Dp = 110.dp,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable(onClick = onOpenAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全部",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        when (state) {
            HomeViewModel.PartitionState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            HomeViewModel.PartitionState.Empty -> Text(
                text = "暂无内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HomeViewModel.PartitionState.Error -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "加载失败，请检查网络",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRetry?.invoke() }) {
                        Text(text = "重试")
                    }
                }
            }

            HomeViewModel.PartitionState.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(videos) { index, video ->
                    com.github.tvbox.osc.ui.components.VodCard(
                        video = video,
                        onClick = { onCardClick(video) },
                        onLongClick = { onCardLongClick(video) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
                item(key = "more_$title") {
                    LaunchedEffect(videos.size) { onLoadMore() }
                }
            }
        }
    }
}
