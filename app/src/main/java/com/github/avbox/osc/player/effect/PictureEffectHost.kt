package com.github.avbox.osc.player.effect

import androidx.media3.common.Effect

interface PictureEffectHost {

    val isPlaying: Boolean

    fun isPictureEffectsActive(): Boolean

    fun isPictureHdrSource(): Boolean

    fun redrawVideoFrame()

    fun applyVideoEffects(effects: List<Effect>)
}
