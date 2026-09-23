package com.kyant.backdrop.internal

import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.BackdropEffectScopeImpl
import com.kyant.backdrop.RuntimeShader

internal class RenderEffectCache {

    private class Slot {
        var key: String? = null
        var effect: RenderEffect? = null
    }

    private val slots = ArrayList<Slot>(4)
    private var cursor = 0

    fun begin() {
        cursor = 0
    }

    fun clear() {
        slots.clear()
        cursor = 0
    }

    private inline fun obtain(key: String, create: () -> RenderEffect): RenderEffect {
        if (cursor == slots.size) slots.add(Slot())
        val slot = slots[cursor++]
        val cached = slot.effect
        if (cached != null && slot.key == key) return cached
        return create().also {
            slot.key = key
            slot.effect = it
        }
    }

    fun chain(inner: RenderEffect?, outer: RenderEffect): RenderEffect =
        if (inner == null) {
            outer
        } else {
            obtain("chain:${inner.id}:${outer.id}") { inner.chain(outer) }
        }

    fun runtimeShaderEffect(
        runtimeShader: RuntimeShader,
        uniformName: String,
        uniformSignature: String
    ): RenderEffect =
        obtain("shader:${System.identityHashCode(runtimeShader)}:$uniformName:$uniformSignature") {
            RuntimeShaderEffect(runtimeShader, uniformName)
        }

    fun blur(inner: RenderEffect?, radiusX: Float, radiusY: Float, tileMode: TileMode): RenderEffect =
        obtain("blur:${inner.id}:$radiusX:$radiusY:$tileMode") {
            BlurEffect(inner, radiusX, radiusY, tileMode)
        }

    fun colorFilter(inner: RenderEffect?, colorFilter: ColorFilter, signature: String?): RenderEffect =
        obtain("filter:${inner.id}:${signature ?: "obj:${System.identityHashCode(colorFilter)}"}") {
            ColorFilterEffect(inner, colorFilter)
        }

    private val RenderEffect?.id: Int
        get() = if (this == null) 0 else System.identityHashCode(this)
}

internal fun BackdropEffectScope.effectCacheOrNull(): RenderEffectCache? =
    (this as? BackdropEffectScopeImpl)?.effectCache
