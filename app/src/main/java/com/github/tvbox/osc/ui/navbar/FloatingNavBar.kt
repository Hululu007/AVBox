package com.github.tvbox.osc.ui.navbar

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.github.tvbox.osc.ui.theme.LiquidGlassConfig
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** 悬浮导航栏的轴向:Compact 用底部横条,Medium/Expanded 用侧边竖条(见 spec §4.11) */
enum class NavAxis { Horizontal, Vertical }

data class GlassTabItem(
    val iconRes: Int,
    val label: String
)

private val LocalNavTabScale = staticCompositionLocalOf { { 1f } }

/** 交叉轴长度:横条的交叉轴是高度,竖条是宽度 */
private fun Modifier.crossAxisSize(axis: NavAxis, length: Dp): Modifier =
    if (axis == NavAxis.Horizontal) height(length) else width(length)

/** 主轴长度:横条的主轴是宽度,竖条是高度 */
private fun Modifier.mainAxisLength(axis: NavAxis, length: Dp): Modifier =
    if (axis == NavAxis.Horizontal) width(length) else height(length)

/** 主轴铺满:横条铺宽,竖条铺高 */
private fun Modifier.mainAxisFill(axis: NavAxis): Modifier =
    if (axis == NavAxis.Horizontal) fillMaxWidth() else fillMaxHeight()

/** 主轴方向的内边距:横条用 horizontal,竖条用 vertical */
private fun Modifier.mainAxisPadding(axis: NavAxis, value: Dp): Modifier =
    if (axis == NavAxis.Horizontal) padding(horizontal = value) else padding(vertical = value)

/** 沿主轴平移:横条用 translationX,竖条用 translationY */
private fun GraphicsLayerScope.setMainAxisTranslation(axis: NavAxis, value: Float) {
    if (axis == NavAxis.Horizontal) translationX = value else translationY = value
}

/**
 * 轴向无关的容器:横向走 Row、竖向走 Column,内容由两个作用域各自的 lambda 提供
 * (等宽分发要用 `weight`,而 `RowScope.weight` 与 `ColumnScope.weight` 不是同一个函数)
 */
@Composable
private fun NavContainer(
    axis: NavAxis,
    modifier: Modifier,
    horizontalContent: @Composable RowScope.() -> Unit,
    verticalContent: @Composable ColumnScope.() -> Unit,
) {
    if (axis == NavAxis.Horizontal) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            content = horizontalContent,
        )
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = verticalContent,
        )
    }
}

@Composable
fun FloatingNavBar(
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    axis: NavAxis,
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    tabs: List<GlassTabItem>,
    config: LiquidGlassConfig,
    interactive: () -> Boolean = { true },
    isTabSwitching: () -> Boolean = { false }
) {
    val tabsCount = tabs.size
    val isLightTheme = !isSystemInDarkTheme()
    val isBlurEnabled = config.navbarEnabled
    val supportsLens = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val isHorizontal = axis == NavAxis.Horizontal

    val accentColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(
        alpha = if (isBlurEnabled) 0.4f else 1f
    )

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = if (isHorizontal) Alignment.CenterStart else Alignment.TopCenter
    ) {
        // 主轴长度取组合期的约束值(主轴都是 fillMax* ⇒ 约束即实测尺寸),别退回 onGloballyPositioned:
        // 那是布局回调,选中胶囊要等第二帧才拿到 stride ⇒ 冷启动首帧"首页位置闪一下"
        val mainAxisConstraint = if (isHorizontal) constraints.maxWidth else constraints.maxHeight
        val totalStridePx =
            if (mainAxisConstraint == Constraints.Infinity) 0f else mainAxisConstraint.toFloat()
        val tabStridePx =
            if (totalStridePx > 0f) (totalStridePx - with(density) { 8f.dp.toPx() }) / tabsCount else 0f

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, totalStridePx) {
            derivedStateOf {
                if (totalStridePx == 0f) {
                    0f
                } else {
                    val fraction = (offsetAnimation.value / totalStridePx).fastCoerceIn(-1f, 1f)
                    with(density) {
                        4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                    }
                }
            }
        }

        var currentIndex by remember(selectedTabIndex) { mutableIntStateOf(selectedTabIndex()) }

        val currentOnTabSelected by rememberUpdatedState(onTabSelected)
        val currentInteractive by rememberUpdatedState(interactive)

        val dampedDragAnimation = remember(animationScope, tabsCount, density, tabStridePx) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..maxOf(tabsCount - 1, 0).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    currentOnTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (tabStridePx > 0f) {
                        val dragAlongAxis = if (isHorizontal) dragAmount.x else dragAmount.y
                        val direction = if (isHorizontal && !isLtr) -1f else 1f
                        updateValue(
                            (targetValue + dragAlongAxis / tabStridePx * direction)
                                .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAlongAxis)
                        }
                    }
                },
                enabled = { currentInteractive() }
            )
        }

        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }.collectLatest { index ->
                currentIndex = index
            }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
        }

        val interactiveHighlight =
            if (isBlurEnabled && supportsLens && tabStridePx > 0f) {
                remember(animationScope, tabStridePx) {
                    InteractiveHighlight(
                        animationScope = animationScope,
                        enabled = { currentInteractive() },
                        position = { size, _ ->
                            val stride = (dampedDragAnimation.value + 0.5f) * tabStridePx + panelOffset
                            if (isHorizontal) {
                                Offset(
                                    if (isLtr) stride else size.width - stride,
                                    size.height / 2f
                                )
                            } else {
                                Offset(size.width / 2f, stride)
                            }
                        }
                    )
                }
            } else {
                null
            }

        NavContainer(
            axis = axis,
            modifier = Modifier
                .graphicsLayer { setMainAxisTranslation(axis, panelOffset) }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        if (isBlurEnabled) {
                            val switching = isTabSwitching()
                            if (!switching) {
                                vibrancy()
                                blur(config.blurDp.dp.toPx())
                                if (supportsLens) {
                                    val refraction = min(
                                        config.distortionDp.dp.toPx(),
                                        size.minDimension / 2f
                                    )
                                    lens(refraction, refraction)
                                }
                            } else {
                                blur(config.blurDp.dp.toPx())
                            }
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = if (isBlurEnabled) 1f else 0f)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(if (isLightTheme) 0.1f else 0.2f)
                        )
                    },
                    innerShadow = {
                        InnerShadow(radius = 4.dp, alpha = 0.1f)
                    },
                    layerBlock = {
                        if (isBlurEnabled) {
                            val progress = dampedDragAnimation.pressProgress
                            // 按压时沿主轴鼓出:横条按宽度算,竖条按高度算
                            val mainAxisExtent = if (isHorizontal) size.width else size.height
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / mainAxisExtent, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight?.modifier ?: Modifier)
                .crossAxisSize(axis, 64.dp)
                .mainAxisFill(axis)
                .padding(4.dp),
            horizontalContent = {
                NavTabsRow(tabs, selectedTabIndex, onTabSelected, interactive)
            },
            verticalContent = {
                NavTabsColumn(tabs, selectedTabIndex, onTabSelected, interactive)
            },
        )

        CompositionLocalProvider(
            LocalNavTabScale provides {
                if (isBlurEnabled) lerp(1f, 1.2f, dampedDragAnimation.pressProgress) else 1f
            }
        ) {
            NavContainer(
                axis = axis,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { setMainAxisTranslation(axis, panelOffset) }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            if (isBlurEnabled && !isTabSwitching()) {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(config.blurDp.dp.toPx())
                                if (supportsLens) {
                                    val refraction = min(
                                        config.distortionDp.dp.toPx(),
                                        size.minDimension / 2f
                                    )
                                    lens(
                                        refraction * progress,
                                        refraction * progress
                                    )
                                }
                            }
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(
                                alpha = if (isBlurEnabled && !isTabSwitching()) progress else 0f
                            )
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight?.modifier ?: Modifier)
                    .crossAxisSize(axis, 56.dp)
                    .mainAxisFill(axis)
                    .mainAxisPadding(axis, 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                horizontalContent = {
                    NavTabsRow(tabs, selectedTabIndex, onTabSelected, interactive)
                },
                verticalContent = {
                    NavTabsColumn(tabs, selectedTabIndex, onTabSelected, interactive)
                },
            )
        }

        if (tabStridePx > 0f) {
        Box(
            Modifier
                .mainAxisPadding(axis, 4.dp)
                .graphicsLayer {
                    val contentStride = totalStridePx - with(density) { 8f.dp.toPx() }
                    val singleTabStride = contentStride / tabsCount
                    val progressOffset = dampedDragAnimation.value * singleTabStride
                    val rtlFlipped = isHorizontal && !isLtr
                    setMainAxisTranslation(
                        axis,
                        if (rtlFlipped) -progressOffset + panelOffset else progressOffset + panelOffset
                    )
                }
                .then(interactiveHighlight?.gestureModifier ?: Modifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { ContinuousCapsule },
                    effects = {
                        if (isBlurEnabled && supportsLens && !isTabSwitching()) {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = if (isBlurEnabled && !isTabSwitching()) progress else 0f)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = if (isBlurEnabled && !isTabSwitching()) progress else 0f)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8.dp * progress,
                            alpha = if (isBlurEnabled && !isTabSwitching()) progress else 0f
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        // 甩动拉伸沿拖动方向:横条拉 x 压 y,竖条拉 y 压 x
                        val stretch = 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        val squash = 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        if (isHorizontal) {
                            scaleX /= stretch
                            scaleY *= squash
                        } else {
                            scaleY /= stretch
                            scaleX *= squash
                        }
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .crossAxisSize(axis, 56.dp)
                .mainAxisLength(
                    axis,
                    with(density) { ((totalStridePx - 8f.dp.toPx()) / tabsCount).toDp() }
                )
        )
        }
    }
}

@Composable
private fun RowScope.NavTabsRow(
    tabs: List<GlassTabItem>,
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    interactive: () -> Boolean,
) {
    val currentIndex = selectedTabIndex()
    val enabled = interactive()
    tabs.forEachIndexed { index, tab ->
        NavTabItem(
            tab = tab,
            selected = index == currentIndex,
            enabled = enabled,
            onClick = { onTabSelected(index) },
            modifier = Modifier.fillMaxHeight().weight(1f),
        )
    }
}

@Composable
private fun ColumnScope.NavTabsColumn(
    tabs: List<GlassTabItem>,
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    interactive: () -> Boolean,
) {
    val currentIndex = selectedTabIndex()
    val enabled = interactive()
    tabs.forEachIndexed { index, tab ->
        NavTabItem(
            tab = tab,
            selected = index == currentIndex,
            enabled = enabled,
            onClick = { onTabSelected(index) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun NavTabItem(
    tab: GlassTabItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scale = LocalNavTabScale.current
    val iconColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "tabIconColor"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "tabTextColor"
    )
    Column(
        modifier = modifier
            .clip(ContinuousCapsule)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(tab.iconRes),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1
        )
    }
}
