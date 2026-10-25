package com.github.avbox.osc.util

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object BoundedCall {

    @JvmStatic
    fun <T> call(task: Callable<T>, timeoutMs: Long, tag: String): T? {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bounded-call") }
        val future = executor.submit(task)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            AppLog.i("$tag-timeout(${timeoutMs}ms)")
            future.cancel(true)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.i("$tag-interrupted")
            null
        } catch (e: Exception) {
            AppLog.e("BoundedCall", "$tag-error: ${e.cause ?: e}", e.cause ?: e)
            null
        } finally {
            executor.shutdown()
        }
    }
}
