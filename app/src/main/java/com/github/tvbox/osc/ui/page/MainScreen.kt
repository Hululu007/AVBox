@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.api.AppBootstrap
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.LocalTopBarGlassPauseRecording
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
import com.github.tvbox.osc.util.HomeSettings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.launch

private enum class AppTab(@StringRes val labelRes: Int, @DrawableRes val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    RECORDS(R.string.tab_records, R.drawable.ic_tab_records),
    FOLLOWING(R.string.tab_following, R.drawable.ic_tab_following),
    SETTINGS(R.string.tab_mine, R.drawable.ic_tab_mine),
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
                homeViewModel.markDefaultLiveLaunched()
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

    LaunchedEffect(pagerState) {
        withFrameNanos { }
        withFrameNanos { }
        pagerState.scrollBy(1f)
        pagerState.scrollBy(-1f)
    }

    BackHandler {
        if (homeViewModel.onBackPressedOnce()) {
            AppManager.getInstance().finishAllActivity()
            ControlManager.get().stopServer()
            (context as? Activity)?.finishAffinity()
        } else {
            Toast.makeText(context, context.getString(R.string.toast_press_again_to_exit), Toast.LENGTH_SHORT).show()
        }
    }

    val sheetHost = remember { SheetHostState() }
    var navAnimationEnabled by remember {
        mutableStateOf(!KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false))
    }
    var navLiveHidden by remember {
        mutableStateOf(KV.get(HawkConfig.NAV_LIVE_HIDDEN, false))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        navAnimationEnabled = !KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false)
        navLiveHidden = KV.get(HawkConfig.NAV_LIVE_HIDDEN, false)
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
    val pauseGlassRecording: () -> Boolean = remember(pagerState, sheetHost) {
        { pagerState.isScrollInProgress || sheetHost.request != null }
    }
    val pauseTopBarRecording: () -> Boolean = remember(pagerState) {
        { pagerState.isScrollInProgress }
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val navAxis = NavMetrics.axisFor(currentWindowWidthClass())
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
    val liveActionLabel = stringResource(R.string.common_live)
    val liveActionItem = remember(liveActionLabel) { GlassTabItem(R.drawable.ic_live_fab, liveActionLabel) }
    val openLive: () -> Unit = remember(context) {
        { context.startActivity(Intent(context, LivePlayActivity::class.java)) }
    }
    CompositionLocalProvider(
        LocalSheetHost provides sheetHost,
        LocalTopBarGlassPauseRecording provides pauseTopBarRecording,
    ) {
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
                                if (!navLiveHidden && index == NavMetrics.actionSlotFor(AppTab.entries.size)) {
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
                                Modifier.layerBackdrop(liquidBackdrop, liquidBackdropBounds, pauseGlassRecording)
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
                        val pageLifecycleOwner = rememberLifecycleOwner(
                            maxLifecycle = if (page == pagerState.targetPage) {
                                Lifecycle.State.RESUMED
                            } else {
                                Lifecycle.State.STARTED
                            }
                        )
                        CompositionLocalProvider(LocalLifecycleOwner provides pageLifecycleOwner) {
                            when (AppTab.entries[page]) {
                                AppTab.HOME -> HomePage(homeViewModel, pageContentPadding)
                                AppTab.RECORDS -> RecordsPage(contentPadding = pageContentPadding)
                                AppTab.FOLLOWING -> FollowingPage(contentPadding = pageContentPadding)
                                AppTab.SETTINGS -> SettingsPage(contentPadding = pageContentPadding)
                            }
                        }
                    }
                }
            }
            if (liquidGlassEnabled) {
                val homeHorizontal = HomeSettings.layoutFlow.collectAsStateWithLifecycle().value ==
                    HomeSettings.HomeLayout.Horizontal
                if (navAxis == NavAxis.Horizontal && !homeHorizontal) {
                    val scrimColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(navBandExtent)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to scrimColor)),
                    )
                }
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
                        actionItem = if (navLiveHidden) null else liveActionItem,
                        onActionClick = openLive,
                    )
                }
            }
            if (surfaceNavVisible && railMode) {
                NavigationRail(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    AppTab.entries.forEachIndexed { index, tab ->
                        if (!navLiveHidden && index == NavMetrics.actionSlotFor(AppTab.entries.size)) {
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
