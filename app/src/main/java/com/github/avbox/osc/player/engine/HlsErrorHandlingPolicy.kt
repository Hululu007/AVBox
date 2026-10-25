package com.github.avbox.osc.player.engine

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

class HlsErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isChunkError(loadErrorInfo.exception)) {
            return RETRY_DELAY_MS
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        if (dataType == C.DATA_TYPE_MEDIA) {
            return MAX_RETRIES
        }
        return super.getMinimumLoadableRetryCount(dataType)
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (isChunkError(loadErrorInfo.exception)) {
            return null
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    companion object {
        const val MAX_RETRIES = 3

        const val RETRY_DELAY_MS = 500L

        @JvmStatic
        fun isChunkError(exception: Throwable?): Boolean = exception is IOException
    }
}
