@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal val PressMorphCornerRadius = 8.dp

internal val PressMorphButtonShapes = ButtonShapes(
    shape = RoundedCornerShape(percent = 50),
    pressedShape = RoundedCornerShape(PressMorphCornerRadius),
)

private const val PressScaleDamping = 0.6f

@Composable
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.94f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = PressScaleDamping,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
