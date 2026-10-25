package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedCallTest {

    @Test
    fun call_returnsTaskResult() {
        assertEquals("ok", BoundedCall.call(Callable { "ok" }, 1_000L, "test"))
    }

    @Test
    fun call_timesOutToNullAndInterruptsTask() {
        val interrupted = CountDownLatch(1)
        val result = BoundedCall.call(Callable<String?> {
            try {
                Thread.sleep(5_000L)
            } catch (e: InterruptedException) {
                interrupted.countDown()
            }
            null
        }, 1_000L, "test-timeout")
        assertNull(result)
        assertTrue("超时必须打断任务线程", interrupted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun call_swallowsTaskFailure() {
        val failed = Callable<String> { throw IllegalStateException("boom") }
        assertNull(BoundedCall.call(failed, 1_000L, "test-error"))
    }
}
