@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.LocalSheetHost
import com.github.tvbox.osc.ui.components.SheetHost
import com.github.tvbox.osc.ui.components.SheetHostState
import com.github.tvbox.osc.ui.currentWindowWidthClass
import com.github.tvbox.osc.ui.navbar.FloatingNavBar
import com.github.tvbox.osc.ui.navbar.GlassTabItem
import com.github.tvbox.osc.ui.navbar.NavAxis
import com.github.tvbox.osc.ui.navbar.NavMetrics
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.launch

private enum class AppTab(@StringRes val labelRes: Int, @DrawableRes val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    HISTORY(R.string.history_title, R.drawable.ic_tab_history),
    COLLECT(R.string.common_collect, R.drawable.ic_tab_collect),
    SETTINGS(R.string.settings_title, R.drawable.ic_tab_settings),
}

@Composable
fun MainScreen() {
    LaunchedEffect(Unit) { AppBootstrap.start() }
    val boot by AppBootstrap.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        MainContent()
        if (boot is AppBootstrap.Boot.Error) {
            BootErrorDialog((boot as AppBootstrap.Boot.Error).msg)
        }
    }
}

@Composable
private fun BootErrorDialog(msg: String) {
    AVBoxAlertDialog(
        onDismissRequest = {},
        // 启动失败必须重试/离线二选一,不允许点空白关掉(旧平台 Dialog 传空 onDismissRequest 就是这个效果;
        // 改成弹层后若走退场动画而不清状态,面板会隐身留场并把整屏触摸吃掉)
        dismissible = false,
        title = { Text(stringResource(R.string.config_load_failed)) },
        text = { Text(msg) },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { AppBootstrap.retry() } }) {
                Text(stringResource(R.string.common_retry))
            }
        },
        dismissButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { AppBootstrap.continueOffline() } }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun MainContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { AppTab.entries.size })
    val homeViewModel: HomeViewModel = viewModel()

    LaunchedEffect(Unit) {
        if (homeViewModel.defaultLiveLaunched) return@LaunchedEffect
        AppBootstrap.state.collect { boot ->
            if (boot is AppBootstrap.Boot.Ready && !homeViewModel.defaultLiveLaunched) {
                homeViewModel.defaultLiveLaunched = true
                // 上次启动被看门狗自动停用的源(有值才提示);默认源不会自动跳到别的源,需用户去配置管理重选
                val disabled = BootGuard.takeSafeDisabledNotice()
                if (disabled.isNotEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_source_auto_disabled), Toast.LENGTH_LONG).show()
                }
                if (KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                    context.startActivity(Intent(context, LivePlayActivity::class.java))
                }
            }
        }
    }

    // 冷启动后第一次切页,pager 滚动 → 页面测量 → 玻璃源层重录整条链路都是首次执行(ART 现场编译);
    // 先滚 1px 再滚回来走完同一套路径,位移不到 0.3dp,肉眼看不到
    LaunchedEffect(pagerState) {
        withFrameNanos { }
        withFrameNanos { }
        pagerState.scrollBy(1f)
        pagerState.scrollBy(-1f)
    }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - homeViewModel.lastBackTime < 2000) {
            AppManager.getInstance().finishAllActivity()
            ControlManager.get().stopServer()
            (context as? Activity)?.finishAffinity()
        } else {
            homeViewModel.lastBackTime = now
            Toast.makeText(context, context.getString(R.string.toast_press_again_to_exit), Toast.LENGTH_SHORT).show()
        }
    }

    val sheetHost = remember { SheetHostState() }
    var navAnimationEnabled by remember {
        mutableStateOf(!KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        navAnimationEnabled = !KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false)
    }

    val selectTab: (Int) -> Unit = { index ->
        scope.launch {
            if (navAnimationEnabled) {
                pagerState.animateScrollToPage(index)
            } else {
                pagerState.scrollToPage(index)
            }
        }
    }
    val liquidGlassConfig = LiquidGlassState.config
    val liquidGlassEnabled = liquidGlassConfig.navbarEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val liquidBackdropBgColor = MaterialTheme.colorScheme.surfaceContainer
    val liquidBackdropOnDraw: ContentDrawScope.() -> Unit =
        remember(liquidBackdropBgColor) {
            { drawRect(liquidBackdropBgColor); drawContent() }
        }
    val liquidBackdrop = rememberLayerBackdrop(onDraw = liquidBackdropOnDraw)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // 导航形态:Compact 用底部横条,Medium/Expanded 用侧边竖条(判据集中在 NavMetrics,见 spec §4.11)
    val navAxis = NavMetrics.axisFor(currentWindowWidthClass())
    // 形态由窗口档决定、玻璃由用户配置决定,两者正交:关掉玻璃是回退到 M3 surface 导航,不是取消竖条
    val railMode = navAxis == NavAxis.Vertical
    val surfaceNavVisible = !liquidGlassEnabled
    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBandExtent = NavMetrics.BAND_EXTENT_DP.dp
    val liquidBackdropBounds: (Size) -> Rect? = remember(density, navBandExtent, navAxis) {
        { size ->
            val extentPx = with(density) { navBandExtent.toPx() }
            if (navAxis == NavAxis.Horizontal) {
                Rect(0f, size.height - extentPx, size.width, size.height)
            } else {
                Rect(0f, 0f, extentPx, size.height)
            }
        }
    }
    // 作为"内容内边距"下发,不用容器 padding:页面必须保持全出血,
    // 否则背景被缩到导航栏之上,玻璃就取不到内容、退化成一块纯色
    val navReserve = NavMetrics.reserveDp(liquidGlassEnabled, navAxis).dp
    val pageContentPadding: PaddingValues = when {
        railMode -> PaddingValues(
            start = navReserve + navBarsPadding.calculateStartPadding(layoutDirection),
            bottom = navBarsPadding.calculateBottomPadding(),
        )

        liquidGlassEnabled -> PaddingValues(
            bottom = navBarsPadding.calculateBottomPadding() + navReserve,
        )

        else -> PaddingValues(0.dp)
    }
    val tabLabels = AppTab.entries.map { stringResource(it.labelRes) }
    val glassTabs = remember(tabLabels) {
        AppTab.entries.mapIndexed { index, tab -> GlassTabItem(tab.icon, tabLabels[index]) }
    }
    // 直播是动作不是目的地:插在导航栏正中,进独立 Activity,不占 pager 页也不参与选中态
    val liveActionLabel = stringResource(R.string.common_live)
    val liveActionItem = remember(liveActionLabel) { GlassTabItem(R.drawable.ic_live_fab, liveActionLabel) }
    val openLive: () -> Unit = remember(context) {
        { context.startActivity(Intent(context, LivePlayActivity::class.java)) }
    }
    CompositionLocalProvider(LocalSheetHost provides sheetHost) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (surfaceNavVisible && !railMode) {
                        ShortNavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            AppTab.entries.forEachIndexed { index, tab ->
                                // 动作槽插在中间,外观就是普通未选中项(不占 pager 页,故恒 selected = false)
                                if (index == NavMetrics.actionSlotFor(AppTab.entries.size)) {
                                    ShortNavigationBarItem(
                                        selected = false,
                                        onClick = openLive,
                                        icon = {
                                            Icon(
                                                painterResource(liveActionItem.iconRes),
                                                contentDescription = null,
                                            )
                                        },
                                        label = null,
                                    )
                                }
                                val selected = pagerState.targetPage == index
                                ShortNavigationBarItem(
                                    selected = selected,
                                    onClick = { selectTab(index) },
                                    icon = {
                                        Icon(
                                            painterResource(tab.icon),
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = if (selected) {
                                        { Text(stringResource(tab.labelRes)) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (liquidGlassEnabled) {
                                Modifier.layerBackdrop(liquidBackdrop, liquidBackdropBounds)
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = navAnimationEnabled,
                        beyondViewportPageCount = 3,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (liquidGlassEnabled) Modifier else Modifier.padding(innerPadding)),
                    ) { page ->
                        // 作为"内容内边距"下发,不用容器 padding:页面必须保持全出血,
                        // 否则背景被缩到导航栏之上,玻璃就取不到内容、退化成一块纯色
                        when (AppTab.entries[page]) {
                            AppTab.HOME -> HomePage(homeViewModel, pageContentPadding)
                            AppTab.HISTORY -> HistoryPage(contentPadding = pageContentPadding)
                            AppTab.COLLECT -> CollectPage(contentPadding = pageContentPadding)
                            AppTab.SETTINGS -> SettingsPage(contentPadding = pageContentPadding)
                        }
                    }
                }
            }
            if (liquidGlassEnabled) {
                // 遮罩要"贴屏幕边缘那侧不透明、往内容侧渐隐"。横向别照抄竖向的 0f/1f 顺序,
                // 反了会在内容侧糊出一块半透明白(真机确认过的回归)
                val scrimColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                // 不透明端必须贴屏幕边缘:横条贴底(终点)、竖条贴左(起点)。方向由 NavMetrics 决定,
                // 别再手写 0f/1f —— 抄错会在内容侧糊出一块半透明白(真机确认过的回归)
                val (scrimStart, scrimEnd) = if (NavMetrics.scrimOpaqueAtStart(navAxis)) {
                    scrimColor to Color.Transparent
                } else {
                    Color.Transparent to scrimColor
                }
                Box(
                    modifier = Modifier
                        .then(
                            if (navAxis == NavAxis.Horizontal) {
                                Modifier
                                    .fillMaxWidth()
                                    .height(navBandExtent)
                                    .align(Alignment.BottomCenter)
                            } else {
                                Modifier
                                    .fillMaxHeight()
                                    .width(navBandExtent)
                                    .align(Alignment.CenterStart)
                            }
                        )
                        .background(
                            if (navAxis == NavAxis.Horizontal) {
                                Brush.verticalGradient(0f to scrimStart, 1f to scrimEnd)
                            } else {
                                Brush.horizontalGradient(0f to scrimStart, 1f to scrimEnd)
                            }
                        ),
                )
                Box(
                    modifier = if (navAxis == NavAxis.Horizontal) {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = NavMetrics.MARGIN_DP.dp)
                    } else {
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            // 竖条是满高的,上下都要让:只用 navigationBars 会顶到状态栏里
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(vertical = 16.dp)
                            .padding(start = NavMetrics.MARGIN_DP.dp)
                    },
                ) {
                    FloatingNavBar(
                        backdrop = liquidBackdrop,
                        axis = navAxis,
                        selectedTabIndex = { pagerState.targetPage },
                        onTabSelected = selectTab,
                        tabs = glassTabs,
                        config = liquidGlassConfig,
                        interactive = { true },
                        isTabSwitching = { pagerState.currentPage != pagerState.targetPage },
                        actionItem = liveActionItem,
                        onActionClick = openLive,
                    )
                }
            }
            if (surfaceNavVisible && railMode) {
                // 关掉玻璃是回退到 M3 标准竖条(surface 模式),不是继续用悬浮胶囊
                NavigationRail(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    AppTab.entries.forEachIndexed { index, tab ->
                        if (index == NavMetrics.actionSlotFor(AppTab.entries.size)) {
                            NavigationRailItem(
                                selected = false,
                                onClick = openLive,
                                icon = {
                                    Icon(
                                        painterResource(liveActionItem.iconRes),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(liveActionItem.label) },
                            )
                        }
                        NavigationRailItem(
                            selected = pagerState.currentPage == index,
                            onClick = { selectTab(index) },
                            icon = {
                                Icon(
                                    painterResource(tab.icon),
                                    contentDescription = stringResource(tab.labelRes),
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
            SheetHost(sheetHost)
        }
    }
}
