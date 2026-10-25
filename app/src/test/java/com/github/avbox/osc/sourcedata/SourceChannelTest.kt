package com.github.avbox.osc.sourcedata

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceChannelTest {

    @Test
    fun flowReplaysLatestValueToLateCollector() = runBlocking {
        val channel = SourceChannel<Int?>()
        channel.postValue(1)
        channel.postValue(2)
        channel.postValue(3)
        assertEquals("新收集者应立刻拿到最近一次的值", 3, withTimeout(1000) { channel.flow.first() })
    }

    @Test
    fun valuePostedAfterCollectorStartsIsDelivered() = runBlocking {
        val channel = SourceChannel<String?>()
        val pending = async { withTimeout(1000) { channel.flow.first() } }
        yield()
        channel.postValue("late-post")
        assertEquals("late-post", pending.await())
    }

    @Test
    fun nullPayloadIsDeliveredNotSwallowed() = runBlocking {
        val channel = SourceChannel<Int?>()
        channel.postValue(7)
        channel.postValue(null)
        val got = withTimeout(1000) { channel.flow.take(1).toList() }
        assertEquals("null 必须作为一次投递送达", 1, got.size)
        assertNull(got[0])
    }

    @Test
    fun bothEntryPointsFeedTheSameFlowOnceEach() = runBlocking {
        val channel = SourceChannel<Int?>()
        val pending = async { withTimeout(1000) { channel.flow.take(2).toList() } }
        yield()
        channel.postValue(1)
        channel.setValue(2)
        assertEquals(listOf<Int?>(1, 2), pending.await())
    }

    @Test
    fun postValueFromBackgroundThreadIsDelivered() = runBlocking {
        val channel = SourceChannel<Int?>()
        val pending = async { withTimeout(2000) { channel.flow.first() } }
        yield()
        val worker = Thread { channel.postValue(42) }
        worker.start()
        worker.join()
        assertEquals("任意线程投递都必须送达活跃收集者", 42, pending.await())
    }

    @Test
    fun rapidPostsAreNotConflatedForActiveCollector() = runBlocking {
        val channel = SourceChannel<Int?>()
        val pending = async { withTimeout(2000) { channel.flow.take(5).toList() } }
        yield()
        for (i in 1..5) channel.postValue(i)
        assertEquals(listOf(1, 2, 3, 4, 5), pending.await())
    }

    @Test
    fun latestValueSurvivesOverflowForLateCollector() = runBlocking {
        val channel = SourceChannel<Int?>()
        for (i in 1..100) channel.postValue(i)
        assertEquals("无收集者时投递不阻塞且回放最近一次值", 100, withTimeout(1000) { channel.flow.first() })
    }
}
