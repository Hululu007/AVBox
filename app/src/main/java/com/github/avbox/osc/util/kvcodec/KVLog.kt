package com.github.avbox.osc.util.kvcodec

object KVLog {

    fun interface Sink {
        fun e(message: String)
    }

    private val FALLBACK: Sink = Sink { message -> System.err.println("[KV] " + message) }

    @Volatile
    private var sink: Sink = FALLBACK

    @JvmStatic
    fun setSink(newSink: Sink?) {
        sink = newSink ?: FALLBACK
    }

    @JvmStatic
    fun e(message: String) {
        sink.e(message)
    }
}
