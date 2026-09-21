package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ThemeColorPickerSheet(
    title: String,
    initialColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hsv by remember { mutableStateOf(initialHsv.copyOf()) }
    val currentColor = remember(hsv) { android.graphics.Color.HSVToColor(hsv) }

    AVBoxBottomSheet(onDismissRequest = onDismiss, title = title) {
        val dismissAnimated = LocalSheetDismiss.current
        var accepted by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HueSatWheelPicker(hsv = hsv, onHsvChanged = { hsv = it })
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.theme_brightness_value, (hsv[2] * 100f).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = hsv[2],
                onValueChange = { v -> hsv = floatArrayOf(hsv[0], hsv[1], v) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            ColorCompareRow(initialColor = initialColor, currentColor = currentColor)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    if (!accepted) {
                        accepted = true
                        dismissAnimated()
                    }
                }) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (!accepted) {
                        accepted = true
                        onConfirm(currentColor)
                        dismissAnimated()
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            }
        }
    }
}

@Composable
private fun ColorCompareRow(initialColor: Int, currentColor: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorPreviewColumn(label = stringResource(R.string.theme_initial_color), color = initialColor)
        ColorPreviewColumn(label = stringResource(R.string.theme_current_color), color = currentColor)
    }
}

@Composable
private fun ColorPreviewColumn(label: String, color: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(color), MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun HueSatWheelPicker(
    hsv: FloatArray,
    onHsvChanged: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wheelSize = 240.dp
    val density = LocalDensity.current
    val wheelSizePx = with(density) { wheelSize.toPx() }
    val radiusPx = wheelSizePx / 2f

    val indicatorOffset = remember(hsv) {
        val angleRad = Math.toRadians(hsv[0].toDouble())
        val r = hsv[1] * radiusPx
        Offset(
            (radiusPx + r * cos(angleRad)).toFloat(),
            (radiusPx + r * sin(angleRad)).toFloat(),
        )
    }

    Canvas(
        modifier = modifier
            .size(wheelSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    updateHsvFromTouch(down.position, radiusPx, hsv, onHsvChanged)
                    drag(down.id) { change ->
                        updateHsvFromTouch(change.position, radiusPx, hsv, onHsvChanged)
                        change.consume()
                    }
                }
            },
    ) {
        val center = Offset(radiusPx, radiusPx)
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Red,
                    Color.Yellow,
                    Color.Green,
                    Color.Cyan,
                    Color.Blue,
                    Color.Magenta,
                    Color.Red,
                ),
                center = center,
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                center = center,
                radius = radiusPx,
            ),
        )
        drawCircle(color = Color.White, radius = 10f, center = indicatorOffset, style = Stroke(width = 3f))
        drawCircle(color = Color.Black, radius = 10f, center = indicatorOffset, style = Stroke(width = 1f))
    }
}

private fun updateHsvFromTouch(
    offset: Offset,
    radiusPx: Float,
    currentHsv: FloatArray,
    onHsvChanged: (FloatArray) -> Unit,
) {
    val cx = offset.x - radiusPx
    val cy = offset.y - radiusPx
    val r = sqrt(cx * cx + cy * cy).coerceAtMost(radiusPx)
    val s = (r / radiusPx).coerceIn(0f, 1f)
    var h = Math.toDegrees(atan2(cy.toDouble(), cx.toDouble())).toFloat()
    if (h < 0f) h += 360f
    val v = if (currentHsv[2] <= 0f) 1f else currentHsv[2]
    onHsvChanged(floatArrayOf(h, s, v))
}
