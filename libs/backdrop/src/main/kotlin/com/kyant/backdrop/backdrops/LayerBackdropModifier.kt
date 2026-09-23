package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.internal.recordLayer
import kotlin.math.ceil
import kotlin.math.floor

fun Modifier.layerBackdrop(
    backdrop: LayerBackdrop,
    recordBounds: ((Size) -> Rect?)? = null,
    pauseRecording: () -> Boolean = { false }
): Modifier =
    this then LayerBackdropElement(backdrop, recordBounds, pauseRecording)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val recordBounds: ((Size) -> Rect?)?,
    val pauseRecording: () -> Boolean
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, recordBounds, pauseRecording)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.recordBounds = recordBounds
        node.pauseRecording = pauseRecording
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
        properties["recordBounds"] = recordBounds
        properties["pauseRecording"] = pauseRecording
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (recordBounds != other.recordBounds) return false
        if (pauseRecording != other.pauseRecording) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + (recordBounds?.hashCode() ?: 0)
        result = 31 * result + pauseRecording.hashCode()
        return result
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var recordBounds: ((Size) -> Rect?)?,
    var pauseRecording: () -> Boolean
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        drawContent()

        val layer = backdrop.graphicsLayer
        val bounds = recordBounds?.invoke(size)?.let { clipToSize(it, size) }

        if (pauseRecording()) {
            if (bounds != null) layer.topLeft = bounds.first
            return
        }

        if (bounds == null) {
            layer.topLeft = IntOffset.Zero
            recordLayer(this@LayerBackdropNode, layer) { backdrop.onDraw(this@draw) }
            return
        }

        val offset = bounds.first
        recordLayer(this@LayerBackdropNode, layer, size = bounds.second) {
            translate(-offset.x.toFloat(), -offset.y.toFloat()) {
                backdrop.onDraw(this@draw)
            }
        }
        layer.topLeft = offset
    }

    private fun clipToSize(rect: Rect, size: Size): Pair<IntOffset, IntSize>? {
        if (rect.isEmpty) return null

        val left = floor(rect.left).toInt().coerceAtLeast(0)
        val top = floor(rect.top).toInt().coerceAtLeast(0)
        val right = ceil(rect.right).toInt().coerceAtMost(size.width.toInt())
        val bottom = ceil(rect.bottom).toInt().coerceAtMost(size.height.toInt())
        if (right - left <= 0 || bottom - top <= 0) return null

        return IntOffset(left, top) to IntSize(right - left, bottom - top)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
