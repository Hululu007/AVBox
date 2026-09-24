package com.github.tvbox.osc.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class PlayerTipState(
    val msg: String = "",
    val loading: Boolean = false,
    val err: Boolean = false,
)

/** 提示层状态变化回调:页面据此把状态桥进控制层,并收起位置在控制器之上的弹幕视图 */
fun interface TipStateListener {
    fun onTipStateChanged(state: PlayerTipState)
}

object PlayerTipBridge {
    var state by mutableStateOf(PlayerTipState())
        private set

    // 写读跨线程(注册在主线程,通知可能来自解析/取流线程),故加 @Volatile
    @Volatile
    private var listener: TipStateListener? = null

    /** 单槽位：同屏只应有一个播放容器（详情页），后注册者顶替前者 */
    @JvmStatic
    fun setTipStateListener(listener: TipStateListener?) {
        this.listener = listener
    }

    /** 只摘自己注册的那个:Activity 重建时"新容器先注册、旧容器后销毁",无条件清空会打断新页面的桥 */
    @JvmStatic
    fun clearTipStateListener(listener: TipStateListener?) {
        if (listener != null && this.listener === listener) this.listener = null
    }

    @JvmStatic
    fun setTip(msg: String, loading: Boolean, err: Boolean) {
        state = PlayerTipState(msg, loading, err)
        listener?.onTipStateChanged(state)
    }

    @JvmStatic
    fun hide() {
        state = PlayerTipState()
        listener?.onTipStateChanged(state)
    }
}
