package com.github.tvbox.osc.util

object GestureHelper {

    @JvmStatic
    fun isControlDisabled(): Boolean {
        return KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false)
    }
}
