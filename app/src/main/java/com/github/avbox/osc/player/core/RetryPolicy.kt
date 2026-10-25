package com.github.avbox.osc.player.core

import androidx.media3.datasource.HttpDataSource

object RetryPolicy {

    private const val MANIFEST_MALFORMED = "ERROR_CODE_PARSING_MANIFEST_MALFORMED"

    private const val BAD_HTTP_STATUS = "ERROR_CODE_IO_BAD_HTTP_STATUS"

    private const val MAX_CAUSE_DEPTH = 5

    fun shouldRefreshResolve(errorCodeName: String?): Boolean =
        errorCodeName == MANIFEST_MALFORMED

    fun isHttpStatusError(errorCodeName: String?): Boolean =
        errorCodeName == BAD_HTTP_STATUS

    fun httpStatusOf(error: Throwable?): Int {
        var cause = error
        var depth = 0
        while (cause != null && depth <= MAX_CAUSE_DEPTH) {
            val status = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            if (status > 0) return status
            cause = cause.cause
            depth++
        }
        return 0
    }

    fun httpStatusForTip(error: Throwable?, localProxy: Boolean): Int {
        val status = httpStatusOf(error)
        if (localProxy && status == LOCAL_PROXY_FAILURE_STATUS) return 0
        return status
    }

    private const val LOCAL_PROXY_FAILURE_STATUS = 500
}
