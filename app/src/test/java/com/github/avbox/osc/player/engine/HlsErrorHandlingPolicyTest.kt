package com.github.avbox.osc.player.engine

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class HlsErrorHandlingPolicyTest {

    private val policy = HlsErrorHandlingPolicy()

    @Test
    fun isChunkError_onlyForIoExceptions() {
        assertTrue(HlsErrorHandlingPolicy.isChunkError(IOException("net")))
        assertTrue(HlsErrorHandlingPolicy.isChunkError(InterruptedIOException()))
        assertFalse(HlsErrorHandlingPolicy.isChunkError(RuntimeException("boom")))
        assertFalse(HlsErrorHandlingPolicy.isChunkError(null))
    }

    @Test
    fun minimumRetryCount_mediaIsCappedAtThree() {
        assertEquals(3, DefaultLoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT)
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun minimumRetryCount_otherTypesFollowMedia3Default() {
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        assertEquals(6, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
    }
}
