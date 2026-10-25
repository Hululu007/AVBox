package com.github.avbox.osc.player.danmu

import android.content.Context
import android.util.AttributeSet
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

class PlayerDanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DanmakuView(context, attrs, defStyleAttr) {

    override fun prepare(parser: BaseDanmakuParser?, context: DanmakuContext?) {
        super.prepare(parser, context)
        handler?.setIdleSleep(false)
        handler?.enableNonBlockMode(true)
    }
}
