package com.github.avbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRotationTest {

    @Test
    fun keepsWritingBelowThreshold() {
        assertFalse(AppLog.shouldRotateLog(0L))
        assertFalse(AppLog.shouldRotateLog(7L * 1024 * 1024))
        assertFalse(AppLog.shouldRotateLog(AppLog.FILE_LOG_MAX_BYTES - 1))
    }

    @Test
    fun rotatesAtThreshold() {
        assertTrue(AppLog.shouldRotateLog(AppLog.FILE_LOG_MAX_BYTES))
        assertTrue(AppLog.shouldRotateLog(AppLog.FILE_LOG_MAX_BYTES + 1024))
    }
}
