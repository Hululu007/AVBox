package com.github.avbox.osc.player.host

import android.content.Context

class EngineTextureRenderViewFactory : PlayerRenderViewFactory() {

    companion object {
        @JvmStatic
        fun create(): EngineTextureRenderViewFactory = EngineTextureRenderViewFactory()
    }

    override fun createRenderView(context: Context): PlayerRenderView = EngineTextureRenderView(context)
}
