package com.github.avbox.osc.sourcedata

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlayLoaderSeqTest {

    @Test
    fun olderRequestBecomesStaleAfterCancelOrSwitch() {
        val seq = AtomicInteger(0)
        val mine = seq.incrementAndGet()
        assertFalse("本次请求的序号应被认领", PlayLoader.isStaleResult(mine, seq))

        seq.incrementAndGet()
        assertTrue("序号被顶掉后不得再投递", PlayLoader.isStaleResult(mine, seq))
    }

    @Test
    fun playAndPreloadKeepSeparateSeqFields() {
        val names = PlayLoader::class.java.declaredFields
            .filter { AtomicInteger::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("playRequestSeq", "preloadRequestSeq"), names)
    }

    @Test
    fun preloadSeqIsNotInvalidatedByRealPlayback() {
        val playSeq = AtomicInteger(0)
        val preloadSeqHolder = AtomicInteger(0)

        val preload = preloadSeqHolder.incrementAndGet()
        playSeq.incrementAndGet()

        assertFalse("预载结果不该被真实播放作废", PlayLoader.isStaleResult(preload, preloadSeqHolder))
        assertFalse("两条通道的序号各自独立", PlayLoader.isStaleResult(playSeq.get(), playSeq))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    @Test
    fun cancelPlayRequestInvalidatesOnlyPlayChain() {
        val vm = SourceViewModel()
        val loader = field<PlayLoader>(vm, "playLoader")
        val playSeq = field<AtomicInteger>(loader, "playRequestSeq")
        val preloadSeq = field<AtomicInteger>(loader, "preloadRequestSeq")
        val playChainBefore = field<Job>(loader, "playChain")
        val preloadChain = field<Job>(loader, "preloadChain")

        val playSeqBefore = playSeq.get()
        val preloadSeqBefore = preloadSeq.get()
        loader.cancelPlayRequest()

        assertTrue("play 链序号必须自增(池阶段结果门)", playSeq.get() > playSeqBefore)
        assertEquals("preload 链序号不得被连带作废", preloadSeqBefore, preloadSeq.get())
        assertTrue("旧 play 链必须被取消", playChainBefore.isCancelled)
        assertNotSame("play 链必须换成新实例", playChainBefore, field<Job>(loader, "playChain"))
        assertTrue("preload 链不得被连带取消", preloadChain.isActive)
        assertTrue("取消前那一轮的序号必须变陈旧", PlayLoader.isStaleResult(playSeqBefore, playSeq))
    }
}
