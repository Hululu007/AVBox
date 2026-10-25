package com.github.avbox.osc.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.github.avbox.osc.player.core.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetryPolicyHttpStatusTest {

    @Test
    fun responseCodeException_yieldsStatus() {
        assertEquals(403, RetryPolicy.httpStatusOf(httpError(403)))
    }

    @Test
    fun nestedCause_stillFound() {
        val wrapped = IllegalStateException("wrapper", IOException("io", httpError(404)))
        assertEquals(404, RetryPolicy.httpStatusOf(wrapped))
    }

    @Test
    fun noStatus_returnsZero() {
        assertEquals(0, RetryPolicy.httpStatusOf(IllegalStateException("boom")))
        assertEquals(0, RetryPolicy.httpStatusOf(IOException("timeout", IOException("socket"))))
        assertEquals(0, RetryPolicy.httpStatusOf(null))
    }

    @Test
    fun localProxyFailure_isSuppressed() {
        assertEquals(0, RetryPolicy.httpStatusForTip(httpError(500), localProxy = true))
        assertEquals(0, RetryPolicy.httpStatusForTip(null, localProxy = true))
    }

    @Test
    fun localProxyKeepsUpstreamStatus() {
        assertEquals(403, RetryPolicy.httpStatusForTip(httpError(403), localProxy = true))
    }

    @Test
    fun directSourceKeepsServerError() {
        assertEquals(500, RetryPolicy.httpStatusForTip(httpError(500), localProxy = false))
    }

    @Test
    fun cyclicCause_terminates() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(0, RetryPolicy.httpStatusOf(first))
    }

    private fun httpError(responseCode: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            responseCode,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(Uri.parse("http://example.com/a.mp4")),
            byteArrayOf(),
        )
}
