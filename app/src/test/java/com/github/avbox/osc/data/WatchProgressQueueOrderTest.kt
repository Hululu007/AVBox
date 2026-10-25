package com.github.avbox.osc.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WatchProgressQueueOrderTest {

    @Test
    fun asCoroutineDispatcher_secondTaskStartsBeforeFirstCompletes() {
        val executor = Executors.newSingleThreadExecutor()
        val trace = CopyOnWriteArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        try {
            val scope = CoroutineScope(executor.asCoroutineDispatcher())
            scope.launch {
                trace.add("first-start")
                firstStarted.countDown()
                gate.await()
                trace.add("first-end")
            }
            assertTrue(firstStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            scope.launch {
                trace.add("second-start")
                secondStarted.countDown()
            }
            assertTrue(secondStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(trace.contains("first-end"))
            assertEquals(listOf("first-start", "second-start"), trace.toList())
            gate.complete(Unit)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun singleThreadExecutorWithBlockingAwait_secondTaskWaitsForFirst() {
        val executor = Executors.newSingleThreadExecutor()
        val trace = CopyOnWriteArrayList<String>()
        val gate = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val allDone = CountDownLatch(1)
        try {
            executor.execute {
                trace.add("first-start")
                firstStarted.countDown()
                runBlocking { awaitGate(gate) }
                trace.add("first-end")
            }
            assertTrue(firstStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            executor.execute {
                trace.add("second-start")
                allDone.countDown()
            }
            assertFalse(trace.contains("second-start"))
            gate.countDown()
            assertTrue(allDone.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf("first-start", "first-end", "second-start"), trace.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun singleThreadExecutor_interleavedWriteReadDelete_keepsQueueOrder() {
        val executor = Executors.newSingleThreadExecutor()
        val store = ConcurrentHashMap<String, Long>()
        val observed = CopyOnWriteArrayList<Long?>()
        val allDone = CountDownLatch(1)
        try {
            executor.execute { store["k"] = 111L }
            executor.execute { observed.add(store["k"]) }
            executor.execute { store.remove("k") }
            executor.execute { observed.add(store["k"]) }
            executor.execute { allDone.countDown() }
            assertTrue(allDone.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(111L, null), observed.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    private suspend fun awaitGate(gate: CountDownLatch) {
        withContext(Dispatchers.IO) {
            assertTrue(gate.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val AWAIT_SECONDS = 5L
    }
}
