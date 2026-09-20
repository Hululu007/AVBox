package com.github.tvbox.osc.ui.components

import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 扇贝花边形（13 瓣，音乐播放器跳播键与播放器上一/下一集共用）：
 * 半径按 `r = R * (1 + depth * cos(lobes * angle))` 波动，外接尺寸与同尺寸圆一致。
 */
class ScallopShape(
    private val lobes: Int = 13,
    private val depth: Float = 0.07f,
    private val stepsPerLobe: Int = 24,
) : Shape {

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy) / (1f + depth)
        val total = lobes * stepsPerLobe
        for (i in 0..total) {
            val angle = i.toFloat() / total * TWO_PI
            val r = radius * (1f + depth * cos(lobes * angle))
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

private val TWO_PI = (2f * PI).toFloat()
