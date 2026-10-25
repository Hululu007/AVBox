package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoSeekRapTest {

    @Test
    fun alignsToRapBeforeTarget() {
        val selection = requireNotNull(align(seekMs = 250L))
        val head = selection.primary
        assertEquals("00016.m2ts", head.name)
        assertEquals(3_840L, head.startOffset)
        assertEquals(800L, head.durationMs)
        assertEquals(200L, selection.baseMs)
        assertEquals(1_000L, selection.durationMs)
    }

    @Test
    fun keepsFullTotalDurationAndShrinksBytes() {
        val selection = requireNotNull(align(seekMs = 250L))
        assertEquals(1_000L, selection.durationMs)
        assertEquals(5_000L - 3_840L, selection.totalLength)
    }

    @Test
    fun rapStartOffsetLandsOnTsSync() {
        val selection = requireNotNull(align(seekMs = 250L))
        val head = selection.primary
        val point = clip("00016").accessPoints[2]
        assertEquals(point.syncOffset, head.m2tsOffset + head.startOffset + m2tsOffset(0))
    }

    @Test
    fun firstRapKeepsWholeWindowWhenTargetBeforeAnyPoint() {
        val selection = requireNotNull(align(seekMs = 50L))
        assertEquals(0L, selection.primary.startOffset)
        assertEquals(1_000L, selection.primary.durationMs)
        assertEquals(0L, selection.baseMs)
    }

    @Test
    fun seekIntoLaterSegmentStartsThere() {
        val segments = listOf(
            target("00016.m2ts", 0L, 5_000L, 1_000L),
            target("00017.m2ts", 8_000L, 6_000L, 2_000L),
            target("00018.m2ts", 20_000L, 7_000L, 3_000L),
        )
        val selection = requireNotNull(
            alignSelectionToAccessPoint(segments, 6_000L, "00005.mpls", 2_500L) { clip(it) },
        )
        assertEquals(2, selection.segments.size)
        assertEquals("00017.m2ts", selection.primary.name)
        assertEquals(28_800L, selection.primary.startOffset)
        assertEquals(500L, selection.primary.durationMs)
        assertEquals(2_500L, selection.baseMs)
        assertEquals("00018.m2ts", selection.segments[1].name)
        assertEquals(0L, selection.segments[1].startOffset)
        assertEquals(3_000L, selection.segments[1].durationMs)
    }

    @Test
    fun seekPastEndClampsInsideLastWindow() {
        val selection = requireNotNull(align(seekMs = 9_999L))
        assertEquals(1, selection.segments.size)
        assertEquals(900L, selection.baseMs)
        assertEquals(100L, selection.primary.durationMs)
    }

    @Test
    fun missingClipReturnsNull() {
        val segments = listOf(target("00016.m2ts", 0L, 5_000L, 1_000L))
        assertNull(alignSelectionToAccessPoint(segments, 1_000L, "00005.mpls", 250L) { null })
    }

    @Test
    fun zeroSeekDoesNotAlign() {
        assertNull(align(seekMs = 0L))
    }

    @Test
    fun zeroDurationSegmentRejectsAlignment() {
        val segments = listOf(target("00016.m2ts", 0L, 5_000L, 0L))
        assertNull(alignSelectionToAccessPoint(segments, 1_000L, "00005.mpls", 250L) { clip(it) })
    }

    @Test
    fun playlistStateMapsWindowStartsWithBase() {
        val selection = requireNotNull(align(seekMs = 250L))
        IsoPlaylistState.update(selection)
        assertEquals(1_000L, IsoPlaylistState.durationMs())
        assertEquals(200L, IsoPlaylistState.baseOffsetMs())
        assertEquals(1, IsoPlaylistState.segments())
        assertEquals(200L, IsoPlaylistState.windowStartMs(0))
        assertNull(IsoPlaylistState.windowStartMs(1))
        IsoPlaylistState.reset()
        assertEquals(0L, IsoPlaylistState.durationMs())
        assertNull(IsoPlaylistState.windowStartMs(0))
    }

    @Test
    fun windowStartsAccumulateDurations() {
        IsoPlaylistState.update(
            IsoSelection(
                listOf(
                    target("00016.m2ts", 0L, 5_000L, 800L),
                    target("00017.m2ts", 8_000L, 6_000L, 2_000L),
                ),
                "00005.mpls",
                2_800L,
                200L,
            ),
        )
        assertEquals(2, IsoPlaylistState.segments())
        assertEquals(200L, IsoPlaylistState.windowStartMs(0))
        assertEquals(1_000L, IsoPlaylistState.windowStartMs(1))
        IsoPlaylistState.reset()
    }

    private fun align(seekMs: Long): IsoSelection? {
        val segments = listOf(target("00016.m2ts", 0L, 5_000L, 1_000L))
        return alignSelectionToAccessPoint(segments, 1_000L, "00005.mpls", seekMs) { clip(it) }
    }

    private fun clip(clipId: String): ClpiIndex = ClpiIndex(
        "$clipId.clpi",
        (0..60).map { step ->
            ClpiAccessPoint(1_000L + step * TICKS * 100L, step * 10L, false)
        },
        1_024L,
    )

    private fun target(name: String, offset: Long, length: Long, durationMs: Long) =
        IsoPlayTarget(offset, length, name, durationMs = durationMs)

    private companion object {
        const val TICKS = 45L
    }
}
