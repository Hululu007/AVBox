package com.github.avbox.osc.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

internal const val PLAYER_IN_MS = 120
internal const val PLAYER_OUT_MS = 120
internal const val PLAYER_HINT_IN_MS = 120
internal const val PLAYER_HINT_OUT_MS = 120
internal const val PLAYER_SIDE_IN_MS = 120
internal const val PLAYER_SIDE_OUT_MS = 120
internal const val PLAYER_OSD_IN_MS = 120
internal const val PLAYER_OSD_OUT_MS = 120

internal const val PLAYER_HINT_SLIDE_DP = 8
internal const val PLAYER_SIDE_SCALE = 0.88f

private const val PLAYER_SLIDE_MARGIN_PX = 24

internal val PLAYER_ENTER_EASING = LinearOutSlowInEasing
internal val PLAYER_EXIT_EASING = FastOutLinearInEasing

@Composable
internal fun PlayerSlideVisibility(
    visible: Boolean,
    fromTop: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var contentHeightPx by remember { mutableIntStateOf(0) }
    val hiddenOffsetPx = if (fromTop) {
        -(contentHeightPx + PLAYER_SLIDE_MARGIN_PX)
    } else {
        contentHeightPx + PLAYER_SLIDE_MARGIN_PX
    }
    val offsetY by animateIntAsState(
        targetValue = if (visible) 0 else hiddenOffsetPx,
        animationSpec = tween(
            durationMillis = if (visible) PLAYER_IN_MS else PLAYER_OUT_MS,
            easing = if (visible) PLAYER_ENTER_EASING else PLAYER_EXIT_EASING,
        ),
        label = "playerSlideOffset",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) PLAYER_IN_MS else PLAYER_OUT_MS,
            easing = if (visible) PLAYER_ENTER_EASING else PLAYER_EXIT_EASING,
        ),
        label = "playerSlideAlpha",
    )
    Box(
        modifier
            .onSizeChanged { contentHeightPx = it.height }
            .offset { IntOffset(0, offsetY) }
            .graphicsLayer { this.alpha = alpha },
    ) {
        content()
    }
}

@Composable
internal fun playerHintSlidePx(): Int =
    with(LocalDensity.current) { PLAYER_HINT_SLIDE_DP.dp.toPx().toInt() }

internal fun playerHintEnter(slidePx: Int): EnterTransition =
    slideInVertically(
        animationSpec = tween(PLAYER_HINT_IN_MS, easing = PLAYER_ENTER_EASING),
        initialOffsetY = { -slidePx },
    ) + fadeIn(animationSpec = tween(PLAYER_HINT_IN_MS, easing = PLAYER_ENTER_EASING))

internal fun playerHintExit(): ExitTransition =
    fadeOut(animationSpec = tween(PLAYER_HINT_OUT_MS, easing = PLAYER_EXIT_EASING))

@Composable
internal fun playerSideAlpha(visible: Boolean): Float = animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = tween(
        durationMillis = if (visible) PLAYER_SIDE_IN_MS else PLAYER_SIDE_OUT_MS,
        easing = if (visible) PLAYER_ENTER_EASING else PLAYER_EXIT_EASING,
    ),
    label = "playerSideAlpha",
).value

@Composable
internal fun playerSideScale(visible: Boolean): Float = animateFloatAsState(
    targetValue = if (visible) 1f else PLAYER_SIDE_SCALE,
    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
    label = "playerSideScale",
).value

internal fun Modifier.playerSideEffect(alpha: Float, scale: Float): Modifier = graphicsLayer {
    this.alpha = alpha
    scaleX = scale
    scaleY = scale
}

@Composable
internal fun PlayerHintVisibility(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = playerHintEnter(playerHintSlidePx()),
        exit = playerHintExit(),
    ) {
        content()
    }
}

@Composable
internal fun PlayerFadeVisibility(
    visible: Boolean,
    enterMs: Int,
    exitMs: Int,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(enterMs, easing = PLAYER_ENTER_EASING)),
        exit = fadeOut(animationSpec = tween(exitMs, easing = PLAYER_EXIT_EASING)),
    ) {
        content()
    }
}
