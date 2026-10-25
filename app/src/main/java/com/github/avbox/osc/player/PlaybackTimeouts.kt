package com.github.avbox.osc.player

import android.os.Handler
import android.os.Looper
import android.os.Message

class PlaybackTimeouts(private val callback: Callback) {

    interface Callback {
        fun onResolvePlayUrlTimeout()

        fun onSwitchLinePlayTimeout()

        fun onPendingCompletionDrop()
    }

    private val handler: Handler = Handler(Looper.getMainLooper(), Handler.Callback { msg: Message ->
        when (msg.what) {
            MSG_RESOLVE_PLAY_URL_TIMEOUT -> {
                callback.onResolvePlayUrlTimeout()
                true
            }

            MSG_SWITCH_LINE_PLAY_TIMEOUT -> {
                callback.onSwitchLinePlayTimeout()
                true
            }

            MSG_DROP_SESSION_AFTER_COMPLETED -> {
                callback.onPendingCompletionDrop()
                true
            }

            else -> false
        }
    })

    fun startResolvePlayUrlTimeout(timeoutMs: Long) {
        cancelPlayTimeout()
        handler.sendEmptyMessageDelayed(MSG_RESOLVE_PLAY_URL_TIMEOUT, timeoutMs)
    }

    fun startSwitchLinePlayTimeout(timeoutMs: Long) {
        cancelPlayTimeout()
        handler.sendEmptyMessageDelayed(MSG_SWITCH_LINE_PLAY_TIMEOUT, timeoutMs)
    }

    fun cancelPlayTimeout() {
        handler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT)
        handler.removeMessages(MSG_SWITCH_LINE_PLAY_TIMEOUT)
    }

    fun cancelResolvePlayUrlTimeout() {
        handler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT)
    }

    fun cancelPendingCompletionDrop() {
        handler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED)
    }

    fun armPendingCompletionDrop() {
        handler.sendEmptyMessage(MSG_DROP_SESSION_AFTER_COMPLETED)
    }

    companion object {
        @JvmField
        val RESOLVE_PLAY_URL_TIMEOUT_MS: Long = 15 * 1000L

        @JvmField
        val SWITCH_LINE_PLAY_TIMEOUT_MS: Long = 20 * 1000L

        @JvmField
        val ISO_SWITCH_LINE_PLAY_TIMEOUT_MS: Long = 45 * 1000L

        @JvmField
        val PAN_SWITCH_LINE_PLAY_TIMEOUT_MS: Long = 35 * 1000L

        @JvmField
        val SLOW_OPEN_GRACE_MS: Long = 15 * 1000L

        @JvmStatic
        fun switchLinePlayTimeoutMs(isoSession: Boolean, localProxy: Boolean = false): Long = when {
            isoSession -> ISO_SWITCH_LINE_PLAY_TIMEOUT_MS
            localProxy -> PAN_SWITCH_LINE_PLAY_TIMEOUT_MS
            else -> SWITCH_LINE_PLAY_TIMEOUT_MS
        }

        private const val MSG_RESOLVE_PLAY_URL_TIMEOUT = 101
        private const val MSG_SWITCH_LINE_PLAY_TIMEOUT = 102

        private const val MSG_DROP_SESSION_AFTER_COMPLETED = 103
    }
}
