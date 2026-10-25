package com.github.avbox.osc.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.github.avbox.osc.ui.theme.cardContainer
import kotlin.math.hypot

fun Modifier.shimmer(
    base: Color? = null,
    highlight: Color? = null,
): Modifier = composed {
    val baseColor = base ?: MaterialTheme.colorScheme.cardContainer
    val shineColor = highlight ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
    drawWithCache {
        val startX = shimmerStartX(size.width, size.height, progress)
        val brush = Brush.linearGradient(
            colors = listOf(baseColor, shineColor, baseColor),
            start = Offset(startX, 0f),
            end = Offset(startX + size.width, size.height),
        )
        onDrawBehind { drawRect(brush) }
    }
}

internal fun shimmerStartX(width: Float, height: Float, progress: Float): Float {
    val diagonal = hypot(width, height)
    if (width <= 0f || diagonal <= 0f) return 0f
    val travel = diagonal * diagonal / width
    return -travel + 2f * travel * progress
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.cardContainer)
            .shimmer(),
    )
}
