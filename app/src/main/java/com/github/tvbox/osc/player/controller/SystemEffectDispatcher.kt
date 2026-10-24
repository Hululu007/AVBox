package com.github.tvbox.osc.player.controller

import android.app.Activity
import android.media.AudioManager

sealed interface SystemEffect {
    data class Brightness(val value: Float) : SystemEffect
    data class Volume(val index: Int) : SystemEffect
}

class SystemEffectDispatcher(
    private val activity: () -> Activity?,
    private val audio: () -> AudioManager?,
) {
    fun dispatch(effect: SystemEffect) {
        when (effect) {
            is SystemEffect.Brightness -> applyBrightness(effect.value)
            is SystemEffect.Volume -> applyVolume(effect.index)
        }
    }

    private fun applyBrightness(value: Float) {
        val window = activity()?.window ?: return
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    private fun applyVolume(index: Int) {
        audio()?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }
}
