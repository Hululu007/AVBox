package com.github.catvod.crawler.js

import com.github.avbox.osc.util.AppLog
import okhttp3.Call
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.util.concurrent.atomic.AtomicBoolean

class OpenStream(private val call: Call, private val response: Response, val sessionId: Int? = null) {

    private val source: BufferedSource = response.body.source()

    private val buffer = Buffer()

    private val closed = AtomicBoolean(false)

    fun read(size: Int): ByteArray? {
        if (closed.get()) return null
        return try {
            val read = source.read(buffer, size.toLong())
            if (read == -1L) {
                release(false)
                null
            } else {
                buffer.readByteArray()
            }
        } catch (th: Throwable) {
            AppLog.e("OpenStream", th)
            release(true)
            null
        }
    }

    fun close(): Boolean {
        return release(true)
    }

    private fun release(abort: Boolean): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        try {
            response.close()
        } catch (th: Throwable) {
            AppLog.e("OpenStream", th)
        }
        if (abort) {
            try {
                call.cancel()
            } catch (th: Throwable) {
                AppLog.e("OpenStream", th)
            }
        }
        return true
    }
}
