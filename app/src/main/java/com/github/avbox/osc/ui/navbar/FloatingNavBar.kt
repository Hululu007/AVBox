package com.github.avbox.osc.ui.navbar

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
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
import com.github.avbox.osc.ui.theme.LiquidGlassConfig
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

enum class NavAxis { Horizontal, Vertical }

data class GlassTabItem(
    val iconRes: Int,
    val label: String
)

private val LocalNavTabScale = staticCompositionLocalOf { { 1f } }

private fun Modifier.crossAxisSize(axis: NavAxis, length: Dp): Modifier =
    if (axis == NavAxis.Horizontal) height(length) else width(length)

private fun Modifier.mainAxisLength(axis: NavAxis, length: Dp): Modifier =
    if (axis == NavAxis.Horizontal) width(length) else height(length)

private fun Modifier.mainAxisStretch(axis: NavAxis): Modifier =
    if (axis == NavAxis.Horizontal) fillMaxWidth() else this

private fun Modifier.mainAxisPadding(axis: NavAxis, value: Dp): Modifier =
    if (axis == NavAxis.Horizontal) padding(horizontal = value) else padding(vertical = value)

private fun GraphicsLayerScope.setMainAxisTranslation(axis: NavAxis, value: Float) {
    if (axis == NavAxis.Horizontal) translationX = value else translationY = value
}

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
    onReselected: (Int) -> Unit = {},
    tabs: List<GlassTabItem>,
    config: LiquidGlassConfig,
    interactive: () -> Boolean = { true },
    actionItem: GlassTabItem? = null,
    onActionClick: () -> Unit = {},
) {
    val tabsCount = tabs.size
    val actionSlot = actionItem?.let { NavMetrics.actionSlotFor(tabsCount) }
    val slotCount = tabsCount + if (actionItem != null) 1 else 0
    val isLightTheme = !isSystemInDarkTheme()
    val isBlurEnabled = config.navbarEnabled
    val isHorizontal = axis == NavAxis.Horizontal

    val accentColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(
        alpha = if (isBlurEnabled) 0.4f * config.containerAlphaScale else 1f
    )

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = if (isHorizontal) Alignment.CenterStart else Alignment.TopCenter
    ) {
        val totalStridePx = if (isHorizontal) {
            if (constraints.maxWidth == Constraints.Infinity) 0f else constraints.maxWidth.toFloat()
        } else {
            with(density) { (NavMetrics.SLOT_MAIN_DP * slotCount + 8).dp.toPx() }
        }
        val slotStridePx =
            if (totalStridePx > 0f) (totalStridePx - with(density) { 8f.dp.toPx() }) / slotCount else 0f
        val maxSlotPosition = (slotCount - 1).toFloat()

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
        val currentOnReselected by rememberUpdatedState(onReselected)
        val currentInteractive by rememberUpdatedState(interactive)

        class DampedDragAnimationHolder {
            var instance: DampedDragAnimation? = null
        }
        val dampedDragAnimationHolder = remember { DampedDragAnimationHolder() }

        val dampedDragAnimation = remember(
            animationScope, tabsCount, slotCount, density, slotStridePx, isHorizontal, isLtr
        ) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = NavMetrics.slotIndexOfTab(selectedTabIndex(), actionSlot).toFloat(),
                valueRange = 0f..maxOf(slotCount - 1, 0).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                canDrag = { layerOffset ->
                    val animation = dampedDragAnimationHolder.instance
                        ?: return@DampedDragAnimation true
                    if (slotStridePx <= 0f) return@DampedDragAnimation false
                    val posInLayer = if (isHorizontal) layerOffset.x else layerOffset.y
                    val contentExtent = slotStridePx * slotCount
                    val rtl = isHorizontal && !isLtr
                    val base = if (rtl) contentExtent - slotStridePx else 0f
                    val translation =
                        animation.value.coerceIn(0f, maxSlotPosition) * slotStridePx *
                            (if (rtl) -1f else 1f)
                    base + translation + posInLayer in 0f..contentExtent
                },
                onDragStarted = {},
                onDragStopped = {
                    val slot = targetValue.fastRoundToInt().fastCoerceIn(0, slotCount - 1)
                    val page = NavMetrics.tabIndexOfSlot(slot, actionSlot)
                    currentIndex = page
                    animateToValue(NavMetrics.slotIndexOfTab(page, actionSlot).toFloat())
                    if (page != selectedTabIndex()) {
                        currentOnTabSelected(page)
                    } else {
                        currentOnReselected(page)
                    }
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (slotStridePx > 0f) {
                        val dragAlongAxis = if (isHorizontal) dragAmount.x else dragAmount.y
                        val direction = if (isHorizontal && !isLtr) -1f else 1f
                        updateValue(
                            (targetValue + dragAlongAxis / slotStridePx * direction)
                                .fastCoerceIn(0f, (slotCount - 1).toFloat())
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAlongAxis)
                        }
                    }
                },
                enabled = { currentInteractive() }
            ).also { dampedDragAnimationHolder.instance = it }
        }

        LaunchedEffect(selectedTabIndex, dampedDragAnimation) {
            snapshotFlow { selectedTabIndex() }.collectLatest { index ->
                currentIndex = index
                dampedDragAnimation.animateToValue(
                    NavMetrics.slotIndexOfTab(index, actionSlot).toFloat()
                )
            }
        }

        val interactiveHighlight =
            if (isBlurEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                slotStridePx > 0f
            ) {
                remember(animationScope, slotStridePx) {
                    InteractiveHighlight(
                        animationScope = animationScope,
                        enabled = { currentInteractive() },
                        position = { size, _ ->
                            val stride =
                                (dampedDragAnimation.value.coerceIn(0f, maxSlotPosition) + 0.5f) *
                                    slotStridePx + panelOffset
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        if (isBlurEnabled) {
                            colorControls(
                                brightness = config.contentBrightness,
                                contrast = config.contentContrast,
                                saturation = config.contentSaturation,
                            )
                            blur(config.blurDp.dp.toPx())
                            lens(
                                config.distortionDp.dp.toPx(),
                                config.distortionDp.dp.toPx(),
                                chromaticAberration = config.dispersion,
                            )
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
                    layerBlock = {
                        if (isBlurEnabled) {
                            val progress = dampedDragAnimation.pressProgress
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
                .mainAxisStretch(axis)
                .padding(4.dp),
            horizontalContent = {
                NavSlotsRow(
                    tabs = tabs,
                    actionItem = actionItem,
                    actionSlot = actionSlot,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = onTabSelected,
                    onActionClick = onActionClick,
                    interactive = interactive,
                )
            },
            verticalContent = {
                NavSlotsColumn(
                    tabs = tabs,
                    actionItem = actionItem,
                    actionSlot = actionSlot,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = onTabSelected,
                    onActionClick = onActionClick,
                    interactive = interactive,
                )
            },
        )

        CompositionLocalProvider(
            LocalNavTabScale provides {
                if (isBlurEnabled) {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                } else {
                    1f
                }
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
                            if (isBlurEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                colorControls(
                                    brightness = config.contentBrightness,
                                    contrast = config.contentContrast,
                                    saturation = config.contentSaturation,
                                )
                                blur(config.blurDp.dp.toPx())
                                lens(
                                    config.distortionDp.dp.toPx() * progress,
                                    config.distortionDp.dp.toPx() * progress
                                )
                            }
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = if (isBlurEnabled) progress else 0f)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight?.modifier ?: Modifier)
                    .crossAxisSize(axis, 56.dp)
                    .mainAxisStretch(axis)
                    .mainAxisPadding(axis, 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                horizontalContent = {
                    NavSlotsRow(
                        tabs = tabs,
                        actionItem = actionItem,
                        actionSlot = actionSlot,
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = onTabSelected,
                        onActionClick = onActionClick,
                        interactive = interactive,
                    )
                },
                verticalContent = {
                    NavSlotsColumn(
                        tabs = tabs,
                        actionItem = actionItem,
                        actionSlot = actionSlot,
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = onTabSelected,
                        onActionClick = onActionClick,
                        interactive = interactive,
                    )
                },
            )
        }

        if (slotStridePx > 0f) {
        Box(
            Modifier
                .mainAxisPadding(axis, 4.dp)
                .graphicsLayer {
                    val contentStride = totalStridePx - with(density) { 8f.dp.toPx() }
                    val singleTabStride = contentStride / slotCount
                    val progressOffset =
                        dampedDragAnimation.value.coerceIn(0f, maxSlotPosition) * singleTabStride
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
                        if (isBlurEnabled) {
                            val progress = dampedDragAnimation.pressProgress
                            lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, true)
                        }
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = if (isBlurEnabled) progress else 0f)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = if (isBlurEnabled) progress else 0f)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = if (isBlurEnabled) progress else 0f
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
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
                        val progress = if (isBlurEnabled) dampedDragAnimation.pressProgress else 0f
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
                    with(density) { ((totalStridePx - 8f.dp.toPx()) / slotCount).toDp() }
                )
        )
        }
    }
}

@Composable
private fun RowScope.NavSlotsRow(
    tabs: List<GlassTabItem>,
    actionItem: GlassTabItem?,
    actionSlot: Int?,
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    onActionClick: () -> Unit,
    interactive: () -> Boolean,
) {
    val currentIndex = selectedTabIndex()
    val enabled = interactive()
    repeat(tabs.size + if (actionItem != null) 1 else 0) { slot ->
        if (actionItem != null && slot == actionSlot) {
            NavTabItem(
                tab = actionItem,
                selected = false,
                enabled = enabled,
                onClick = onActionClick,
                role = Role.Button,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        } else {
            val index = NavMetrics.tabIndexOfSlot(slot, actionSlot)
            NavTabItem(
                tab = tabs[index],
                selected = index == currentIndex,
                enabled = enabled,
                onClick = { onTabSelected(index) },
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        }
    }
}

@Composable
private fun ColumnScope.NavSlotsColumn(
    tabs: List<GlassTabItem>,
    actionItem: GlassTabItem?,
    actionSlot: Int?,
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    onActionClick: () -> Unit,
    interactive: () -> Boolean,
) {
    val currentIndex = selectedTabIndex()
    val enabled = interactive()
    repeat(tabs.size + if (actionItem != null) 1 else 0) { slot ->
        if (actionItem != null && slot == actionSlot) {
            NavTabItem(
                tab = actionItem,
                selected = false,
                enabled = enabled,
                onClick = onActionClick,
                role = Role.Button,
                modifier = Modifier.fillMaxWidth().height(NavMetrics.SLOT_MAIN_DP.dp),
            )
        } else {
            val index = NavMetrics.tabIndexOfSlot(slot, actionSlot)
            NavTabItem(
                tab = tabs[index],
                selected = index == currentIndex,
                enabled = enabled,
                onClick = { onTabSelected(index) },
                modifier = Modifier.fillMaxWidth().height(NavMetrics.SLOT_MAIN_DP.dp),
            )
        }
    }
}

@Composable
private fun NavTabItem(
    tab: GlassTabItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    role: Role = Role.Tab,
) {
    val scale = LocalNavTabScale.current
    val iconColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(ContinuousCapsule)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = role,
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
        if (selected) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1
            )
        }
    }
}
