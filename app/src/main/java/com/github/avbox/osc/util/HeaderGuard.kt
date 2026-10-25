package com.github.avbox.osc.util

object HeaderGuard {

    @JvmStatic
    fun isNameSendable(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        for (c in name) {
            if (c.code < 0x21 || c.code > 0x7e) return false
        }
        return true
    }

    @JvmStatic
    fun isValueSendable(value: String?): Boolean {
        if (value == null) return false
        for (c in value) {
            if (c == '\t') continue
            if (c.code < 0x20 || c.code > 0x7e) return false
        }
        return true
    }

    @JvmStatic
    fun isSendable(name: String?, value: String?): Boolean =
        isNameSendable(name) && isValueSendable(value)
}
