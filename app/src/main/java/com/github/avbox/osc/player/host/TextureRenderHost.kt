package com.github.avbox.osc.player.host

interface TextureRenderHost {

    fun setOnSurfaceReadyListener(listener: Runnable?)

    fun setOutputSize(width: Int, height: Int)
}
