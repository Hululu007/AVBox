package com.github.avbox.osc.player.host

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.github.avbox.osc.player.core.KernelPlayer

interface PlayerRenderView {

    fun attachToPlayer(player: KernelPlayer)

    fun setVideoSize(videoWidth: Int, videoHeight: Int)

    fun setVideoRotation(degree: Int)

    fun setScaleType(scaleType: Int)

    fun getView(): View

    fun doScreenShot(): Bitmap?

    fun release()
}

abstract class PlayerRenderViewFactory {

    abstract fun createRenderView(context: Context): PlayerRenderView
}
