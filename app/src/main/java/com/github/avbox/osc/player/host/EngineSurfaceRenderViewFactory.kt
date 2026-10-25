package com.github.avbox.osc.player.host

import android.content.Context

class EngineSurfaceRenderViewFactory : PlayerRenderViewFactory() {

    companion object {
        @JvmStatic
        fun create(): EngineSurfaceRenderViewFactory = EngineSurfaceRenderViewFactory()
    }

    override fun createRenderView(context: Context): PlayerRenderView = EngineSurfaceRenderView(context)
}
