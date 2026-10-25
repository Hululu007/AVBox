package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MplPrefetchWindowTest {

    @Test
    fun contiguousExtentsCollapseIntoOneWindow() {
        val plan = mplPrefetchPlan(
            listOf(
                IsoExtent(1000L, 400L),
                IsoExtent(1400L, 600L),
                IsoExtent(2000L, 1000L),
            ),
        )

        assertEquals(REASON_OK, plan.reason)
        assertEquals(1, plan.windows.size)
        assertEquals(1000L, plan.windows[0].offset)
        assertEquals(2000, plan.windows[0].length)
    }

    @Test
    fun smallGapsAreMerged() {
        val plan = mplPrefetchPlan(
            listOf(
                IsoExtent(0L, 100L * 1024L),
                IsoExtent(200L * 1024L, 100L * 1024L),
            ),
        )

        assertEquals(1, plan.windows.size)
        assertEquals(0L, plan.windows[0].offset)
        assertEquals(300 * 1024, plan.windows[0].length)
    }

    @Test
    fun wideGapSplitsWindows() {
        val plan = mplPrefetchPlan(
            listOf(
                IsoExtent(0L, 100L * 1024L),
                IsoExtent(1024L * 1024L, 100L * 1024L),
            ),
        )

        assertEquals(2, plan.windows.size)
        assertEquals(0L, plan.windows[0].offset)
        assertEquals(1024L * 1024L, plan.windows[1].offset)
    }

    @Test
    fun sparsePayloadIsRejected() {
        val plan = mplPrefetchPlan(
            listOf(
                IsoExtent(0L, 1024L),
                IsoExtent(200L * 1024L, 1024L),
            ),
        )

        assertTrue(plan.windows.isEmpty())
        assertEquals(REASON_SPARSE, plan.reason)
    }

    @Test
    fun oversizedSpanIsRejected() {
        val plan = mplPrefetchPlan(
            listOf(
                IsoExtent(0L, 3L * 1024L * 1024L),
                IsoExtent(3L * 1024L * 1024L, 2L * 1024L * 1024L),
            ),
            maxSpan = 4L * 1024L * 1024L,
        )

        assertTrue(plan.windows.isEmpty())
        assertEquals(REASON_SPAN, plan.reason)
    }

    @Test
    fun tooManyWindowsFallsBack() {
        val extents = (0 until 6L).map { IsoExtent(it * 1024L * 1024L, 4096L) }
        val plan = mplPrefetchPlan(extents, maxWindows = 4)

        assertTrue(plan.windows.isEmpty())
        assertEquals(REASON_MANY, plan.reason)
    }

    @Test
    fun emptyInputReportsEmpty() {
        val plan = mplPrefetchPlan(emptyList())

        assertTrue(plan.windows.isEmpty())
        assertEquals(REASON_EMPTY, plan.reason)
    }

    @Test
    fun overlappingExtentsStayInsideUnion() {
        val plan = mplPrefetchPlan(
            listOf(
                IsoExtent(0L, 1000L),
                IsoExtent(0L, 1000L),
            ),
        )

        assertEquals(1, plan.windows.size)
        assertEquals(0L, plan.windows[0].offset)
        assertEquals(1000, plan.windows[0].length)
    }

    @Test
    fun candidatesKeepEverythingWithoutCoverage() {
        val entries = listOf(entry("00800.mpls"), entry("00801.mpls"))

        assertEquals(entries, mplPrefetchCandidates(entries, emptySet()))
        assertEquals(listOf("00801.mpls"), mplPrefetchCandidates(entries, setOf("00800.mpls")).map { it.name })
    }

    private fun entry(name: String): IsoEntry {
        val extents = listOf(IsoExtent(0L, 100L))
        return IsoEntry(name, false, 100L, extents, EntryData.Extents(extents))
    }
}
