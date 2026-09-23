package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网速文本口径:入参是**字节/秒**、按 **1024** 进制,单位 B/s | KB/s | MB/s。
 * 标成比特率(Mb/s)或换成 1000 进制都会与播放器里其它显示差 8 倍/4.8%,故锁在用例里。
 */
class PlayerHelperTest {

    @Test
    fun displaySpeed_usesByteUnitsWithBinaryBase() {
        assertEquals("512B/s", PlayerHelper.getDisplaySpeed(512L, true))
        assertEquals("900KB/s", PlayerHelper.getDisplaySpeed(900L * 1024, true))
        assertTrue(PlayerHelper.getDisplaySpeed(3L * 1024 * 1024, true).endsWith("MB/s"))
    }

    @Test
    fun displaySpeed_zeroTextFollowsShowFlag() {
        assertEquals("0B/s", PlayerHelper.getDisplaySpeed(0L, true))
        assertEquals("", PlayerHelper.getDisplaySpeed(0L, false))
    }
}
