package com.github.avbox.osc.player

import com.github.avbox.osc.player.core.RetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun refreshResolve_onlyForMalformedManifest() {
        assertTrue(RetryPolicy.shouldRefreshResolve("ERROR_CODE_PARSING_MANIFEST_MALFORMED"))
        assertFalse(RetryPolicy.shouldRefreshResolve("ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED"))
        assertFalse(RetryPolicy.shouldRefreshResolve("ERROR_CODE_PARSING_CONTAINER_MALFORMED"))
        assertFalse(RetryPolicy.shouldRefreshResolve("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertFalse(RetryPolicy.shouldRefreshResolve("ERROR_CODE_DECODING_FAILED"))
        assertFalse(RetryPolicy.shouldRefreshResolve(null))
        assertFalse(RetryPolicy.shouldRefreshResolve(""))
    }
}
