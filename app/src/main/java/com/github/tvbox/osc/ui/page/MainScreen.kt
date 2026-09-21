@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.GLASS_BACKDROP_BAND_MARGIN_DP
import com.github.tvbox.osc.ui.components.LocalSheetHost
import com.github.tvbox.osc.ui.components.SheetHost
import com.github.tvbox.osc.ui.components.SheetHostState
import com.github.tvbox.osc.ui.navbar.FloatingBottomBar
import com.github.tvbox.osc.ui.navbar.GlassTabItem
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.launch

private enum class AppTab(val label: String, @DrawableRes val icon: Int) {
    HOME("首页", R.drawable.ic_tab_home),
    HISTORY("历史", R.drawable.ic_tab_history),
    COLLECT("收藏", R.drawable.ic_tab_collect),
    SETTINGS("设置", R.drawable.ic_tab_settings),
}

private const val FLOATING_NAV_BOTTOM_MARGIN_DP = 12
private const val FLOATING_NAV_OVERLAY_DP = 64 + FLOATING_NAV_BOTTOM_MARGIN_DP

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
    AlertDialog(
        onDismissRequest = {},
        title = { Text("配置加载失败") },
        text = { Text(msg) },
        confirmButton = {
            TextButton(onClick = { AppBootstrap.retry() }) { Text("重试") }
        },
        dismissButton = {
            TextButton(onClick = { AppBootstrap.continueOffline() }) { Text("取消") }
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
                    Toast.makeText(context, "该源无法使用，会导致崩溃，已自动停用（可在配置管理中重新启用）", Toast.LENGTH_LONG).show()
                }
                if (KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                    context.startActivity(Intent(context, LivePlayActivity::class.java))
                }
            }
        }
    }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - homeViewModel.lastBackTime < 2000) {
            AppManager.getInstance().finishAllActivity()
            ControlManager.get().stopServer()
            (context as? Activity)?.finishAffinity()
        } else {
            homeViewModel.lastBackTime = now
            Toast.makeText(context, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
        }
    }

    val sheetHost = remember { SheetHostState() }
    var navAnimationEnabled by remember {
        mutableStateOf(!KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        navAnimationEnabled = !KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false)
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
    val liquidBackdropBandHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp() +
            (FLOATING_NAV_OVERLAY_DP + GLASS_BACKDROP_BAND_MARGIN_DP).dp
    }
    val liquidBackdropBounds: (Size) -> Rect? = remember(density, liquidBackdropBandHeight) {
        { size ->
            Rect(
                0f,
                size.height - with(density) { liquidBackdropBandHeight.toPx() },
                size.width,
                size.height
            )
        }
    }
    val glassTabs = remember { AppTab.entries.map { GlassTabItem(it.icon, it.label) } }
    CompositionLocalProvider(LocalSheetHost provides sheetHost) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!liquidGlassEnabled) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            AppTab.entries.forEachIndexed { index, tab ->
                                val selected = pagerState.currentPage == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        scope.launch {
                                            if (navAnimationEnabled) {
                                                pagerState.animateScrollToPage(index)
                                            } else {
                                                pagerState.scrollToPage(index)
                                            }
                                        }
                                    },
                                    icon = { Icon(painterResource(tab.icon), contentDescription = tab.label) },
                                    label = { Text(tab.label) },
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
                        val pageBottomPadding = if (liquidGlassEnabled) {
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                FLOATING_NAV_OVERLAY_DP.dp
                        } else {
                            0.dp
                        }
                        when (AppTab.entries[page]) {
                            AppTab.HOME -> HomePage(homeViewModel, pageBottomPadding)
                            AppTab.HISTORY -> HistoryPage(bottomPadding = pageBottomPadding)
                            AppTab.COLLECT -> CollectPage(bottomPadding = pageBottomPadding)
                            AppTab.SETTINGS -> SettingsPage(bottomPadding = pageBottomPadding)
                        }
                    }
                }
            }
            if (liquidGlassEnabled) {
                val gradientHeight = with(density) {
                    WindowInsets.navigationBars.getBottom(density).toDp() + FLOATING_NAV_OVERLAY_DP.dp
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gradientHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
                            )
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = FLOATING_NAV_BOTTOM_MARGIN_DP.dp),
                ) {
                    FloatingBottomBar(
                        backdrop = liquidBackdrop,
                        selectedTabIndex = { pagerState.targetPage },
                        onTabSelected = { index ->
                            scope.launch {
                                if (navAnimationEnabled) {
                                    pagerState.animateScrollToPage(index)
                                } else {
                                    pagerState.scrollToPage(index)
                                }
                            }
                        },
                        tabs = glassTabs,
                        config = liquidGlassConfig,
                        interactive = { true },
                        isTabSwitching = { pagerState.currentPage != pagerState.targetPage },
                    )
                }
            }
            SheetHost(sheetHost)
        }
    }
}
