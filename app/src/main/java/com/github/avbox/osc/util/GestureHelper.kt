package com.github.avbox.osc.util

object GestureHelper {

    @JvmStatic
    fun isControlDisabled(): Boolean {
        return KV.get(KVKeys.GESTURE_CONTROL_DISABLED, false)
    }
}
