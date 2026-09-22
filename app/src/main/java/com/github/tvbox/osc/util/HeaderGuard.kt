package com.github.tvbox.osc.util

/**
 * 配置 / 标记里带进来的 HTTP header 必须过这里。
 *
 * OkHttp 在**构造请求时**才校验 header 名与值的字符集,越界会抛 IllegalArgumentException,
 * 而站点请求、push 标记头等分支没有 try/catch —— 一份坏配置能把 App 直接带崩。
 */
object HeaderGuard {

    /** OkHttp 的 header 名只接受 0x21-0x7e */
    @JvmStatic
    fun isNameSendable(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        for (c in name) {
            if (c.code < 0x21 || c.code > 0x7e) return false
        }
        return true
    }

    /** OkHttp 的 header 值只接受 tab 与 0x20-0x7e(按最严口径收:高字节各 OkHttp 版本行为不一致) */
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
