package com.github.avbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
