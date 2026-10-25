package com.github.avbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test

class KernelReusePolicyTest {

    private fun decide(
        kernelPresent: Boolean = true,
        rebuildRequired: Boolean = false,
        dedicatedPath: Boolean = false,
        reuseAllowed: Boolean = true,
    ): KernelDecision = KernelReusePolicy.decide(kernelPresent, rebuildRequired, dedicatedPath, reuseAllowed)

    @Test
    fun rebuild_whenKernelMissing() {
        assertEquals(KernelDecision.REBUILD, decide(kernelPresent = false))
    }

    @Test
    fun rebuild_whenRebuildRequiredEvenIfReuseAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(rebuildRequired = true))
    }

    @Test
    fun rebuild_whenDedicatedPathEvenIfReuseAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(dedicatedPath = true))
    }

    @Test
    fun rebuild_whenReuseNotAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(reuseAllowed = false))
    }

    @Test
    fun reuse_onlyWhenAllGatesPass() {
        assertEquals(KernelDecision.REUSE, decide())
    }

    private fun cross(started: String?, target: String?): Boolean =
        KernelReusePolicy.isCrossContentSwitch(started, target)

    @Test
    fun crossContent_falseForIdleKernelWithoutContent() {
        assertEquals(false, cross(null, "src|100|线路1|0"))
        assertEquals(false, cross("", "src|100|线路1|0"))
    }

    @Test
    fun crossContent_falseForSameVodSameLineNextEpisode() {
        assertEquals(false, cross("src|100|线路1|0", "src|100|线路1|1"))
        assertEquals(false, cross("src|100|线路1|1", "src|100|线路1|0"))
    }

    @Test
    fun crossContent_falseForSameVodEvenIfTargetIsLastEpisode() {
        assertEquals(false, cross("src|100|线路1|0", "src|100|线路1|9"))
    }

    @Test
    fun crossContent_trueForDifferentVod() {
        assertEquals(true, cross("src|100|线路1|0", "src|200|线路1|0"))
    }

    @Test
    fun crossContent_trueForSameVodDifferentLine() {
        assertEquals(true, cross("src|100|线路1|0", "src|100|线路2|0"))
    }

    @Test
    fun crossContent_trueForDifferentSource() {
        assertEquals(true, cross("srcA|100|线路1|0", "srcB|100|线路1|0"))
    }

    @Test
    fun crossContent_trueWhenTargetKeyUnparsable() {
        assertEquals(true, cross("src|100|线路1|0", "no-separator"))
        assertEquals(true, cross("src|100|线路1|0", null))
    }

    @Test
    fun crossContent_doesNotPrefixMatchDifferentVod() {
        assertEquals(true, cross("src|100|线路1|0", "src|1000|线路1|0"))
    }
}
