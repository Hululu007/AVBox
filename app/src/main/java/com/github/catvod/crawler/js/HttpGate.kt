package com.github.catvod.crawler.js

import com.github.avbox.osc.util.AppLog
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService

class HttpGate(private val executor: ExecutorService) {

    private val lock = Any()

    private var inFlight = 0

    private val pending = ArrayDeque<Request>()

    @Volatile
    private var epoch = 0

    class Request(val start: Runnable, val fail: (String) -> Unit) {

        @Volatile
        var epoch = 0
    }

    fun dispatch(request: Request) {
        var runNow = false
        var rejected = false
        synchronized(lock) {
            request.epoch = epoch
            if (inFlight < MAX_IN_FLIGHT) {
                inFlight++
                runNow = true
            } else if (pending.size < MAX_PENDING) {
                pending.addLast(request)
            } else {
                rejected = true
            }
        }
        when {
            runNow -> run(request)
            rejected -> request.fail("too many pending requests")
        }
    }

    fun finish() {
        var next: Request? = null
        synchronized(lock) {
            if (!executor.isShutdown) next = pending.pollFirst()
            if (next == null && inFlight > 0) inFlight--
            if (next == null) pending.clear()
        }
        if (next != null) run(next)
    }

    fun cancelPending() {
        val dropped = ArrayList<Request>()
        synchronized(lock) {
            epoch++
            while (true) {
                dropped.add(pending.pollFirst() ?: break)
            }
        }
        for (request in dropped) request.fail("cancelled")
    }

    fun clear() {
        synchronized(lock) {
            epoch++
            pending.clear()
        }
    }

    private fun run(request: Request) {
        if (request.epoch != epoch) {
            request.fail("cancelled")
            finish()
            return
        }
        try {
            request.start.run()
        } catch (th: Throwable) {
            finish()
            AppLog.e("HttpGate", th)
        }
    }

    companion object {

        private const val MAX_IN_FLIGHT = 16

        private const val MAX_PENDING = 64
    }
}
