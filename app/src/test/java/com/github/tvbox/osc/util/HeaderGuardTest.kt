package com.github.tvbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HeaderGuard 的边界回归。
 *
 * 存在理由:这些字符会让 OkHttp 在构造请求时抛 IllegalArgumentException,而站点请求分支没有
 * try/catch —— 一份带非法 header 的配置等于把 App 带崩。口径刻意取最严(名 0x21-0x7e、
 * 值 tab+0x20-0x7e):高字节区间的行为在各 OkHttp 版本间并不一致,不赌。
 */
class HeaderGuardTest {

    @Test
    fun name_onlyAcceptsVisibleAscii() {
        assertTrue(HeaderGuard.isNameSendable("User-Agent"))
        assertTrue(HeaderGuard.isNameSendable("X-A"))
        assertFalse(HeaderGuard.isNameSendable(""))
        assertFalse(HeaderGuard.isNameSendable(null))
        assertFalse(HeaderGuard.isNameSendable("User Agent"))
        assertFalse(HeaderGuard.isNameSendable("User-Agent "))
        assertFalse(HeaderGuard.isNameSendable("中文名"))
        assertFalse(HeaderGuard.isNameSendable("X-Del\u007f"))
    }

    @Test
    fun value_allowsVisibleAsciiAndTab() {
        assertTrue(HeaderGuard.isValueSendable("Bearer abc.def"))
        assertTrue(HeaderGuard.isValueSendable("*/*"))
        assertTrue(HeaderGuard.isValueSendable("http://a/?x=1&y=2"))
        assertTrue(HeaderGuard.isValueSendable("a\tb"))
        assertTrue(HeaderGuard.isValueSendable(""))
        assertFalse(HeaderGuard.isValueSendable(null))
        assertFalse(HeaderGuard.isValueSendable("中文"))
        assertFalse(HeaderGuard.isValueSendable("a\nb"))
        assertFalse(HeaderGuard.isValueSendable("a\rb"))
        assertFalse(HeaderGuard.isValueSendable("a\u007fb"))
    }

    @Test
    fun isSendable_needsBothLegit() {
        assertTrue(HeaderGuard.isSendable("Referer", "http://a/"))
        assertFalse(HeaderGuard.isSendable("中文名", "http://a/"))
        assertFalse(HeaderGuard.isSendable("Referer", "中文"))
        assertFalse(HeaderGuard.isSendable(null, null))
    }
}
