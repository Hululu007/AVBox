package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoSelectionTest {

    @Test
    fun ordersSegmentsByPlaylistNotBySize() {
        val targets = listOf(
            target("00019.m2ts", 2_000L, 5_437_642_752L),
            target("00016.m2ts", 1_000L, 5_386_985_472L),
            target("00017.m2ts", 3_000L, 5_420_802_048L),
            target("00011.m2ts", 4_000L, 6_917_855_232L),
        )
        val playlist = playlist("00005.mpls", "00017", "00016", "00019")
        val ordered = requireNotNull(matchPlaylistSegments(playlist, targets))
        assertEquals(listOf("00017.m2ts", "00016.m2ts", "00019.m2ts"), ordered.map { it.name })
        assertEquals(listOf(3_000L, 1_000L, 2_000L), ordered.map { it.m2tsOffset })
    }

    @Test
    fun matchesClipNameCaseInsensitively() {
        val targets = listOf(IsoPlayTarget(1_000L, 100L, "00016.M2TS"))
        val ordered = requireNotNull(matchPlaylistSegments(playlist("00005.mpls", "00016"), targets))
        assertEquals("00016.M2TS", ordered.first().name)
    }

    @Test
    fun returnsNullWhenSegmentMissing() {
        val targets = listOf(target("00016.m2ts", 1_000L, 100L))
        assertNull(matchPlaylistSegments(playlist("00005.mpls", "00016", "00017"), targets))
    }

    @Test
    fun returnsNullForEmptyPlaylist() {
        assertNull(matchPlaylistSegments(playlist("00005.mpls"), emptyList()))
    }

    @Test
    fun selectionSumsSegmentLengths() {
        val selection = IsoSelection(
            listOf(target("00016.m2ts", 0L, 300L), target("00017.m2ts", 300L, 200L)),
            "00005.mpls",
            9_964_162L,
        )
        assertEquals(500L, selection.totalLength)
        assertEquals("00016.m2ts", selection.primary.name)
        assertTrue(selection.multiSegment)
    }

    @Test
    fun playlistDurationWinsOverPlayerDuration() {
        assertEquals(9_964_162L, effectiveIsoDuration(1_422_000L, 9_964_162L))
        assertEquals(1_422_000L, effectiveIsoDuration(1_422_000L, 0L))
    }

    @Test
    fun wholeDiscThresholdUsesPlaylistDuration() {
        val total = 38_000_000_000L
        val wholeDisc = effectiveIsoDuration(1_422_000L, 9_964_162L)
        assertNotNull(isoBandwidthVerdict(3_700_000L, total, wholeDisc))
        assertNull(isoBandwidthVerdict(4_300_000L, total, wholeDisc))
        assertNotNull(isoBandwidthVerdict(3_700_000L, total, 1_422_000L))
    }

    @Test
    fun playlistStateResetClearsDuration() {
        IsoPlaylistState.update(IsoSelection(listOf(target("00016.m2ts", 0L, 1L)), "00005.mpls", 9_964_162L))
        assertEquals(9_964_162L, IsoPlaylistState.durationMs())
        assertEquals(1, IsoPlaylistState.segments())
        IsoPlaylistState.reset()
        assertEquals(0L, IsoPlaylistState.durationMs())
        assertEquals(0, IsoPlaylistState.segments())
    }

    @Test
    fun playlistStatePublishesFirstLanguagePerPid() {
        IsoPlaylistState.reset()
        IsoPlaylistState.publishStreams(
            listOf(
                IsoStream(0x1200, "jpn"),
                IsoStream(0x1201, "eng"),
                IsoStream(0x1200, "chi"),
                IsoStream(0x1202, null),
            ),
        )
        assertEquals("jpn", IsoPlaylistState.languageForPid(0x1200))
        assertEquals("eng", IsoPlaylistState.languageForPid(0x1201))
        assertNull(IsoPlaylistState.languageForPid(0x1202))
        assertNull(IsoPlaylistState.languageForPid(0x1203))
        IsoPlaylistState.reset()
        assertNull(IsoPlaylistState.languageForPid(0x1200))
    }

    @Test
    fun playlistStatePublishesPrimaryVideoPidAndClearsOnReset() {
        IsoPlaylistState.reset()
        IsoPlaylistState.publishVideoPid(0x1011)
        assertEquals(0x1011, IsoPlaylistState.videoPid())
        IsoPlaylistState.publishVideoPid(null)
        assertNull(IsoPlaylistState.videoPid())
        IsoPlaylistState.publishVideoPid(0x1011)
        IsoPlaylistState.reset()
        assertNull(IsoPlaylistState.videoPid())
    }

    @Test
    fun coalescesContiguousExtents() {
        val extents = listOf(
            IsoExtent(1_000, 300),
            IsoExtent(1_300, 200),
            IsoExtent(9_000, 100),
            IsoExtent(9_100, 50),
        )
        val runs = coalesceRuns(extents)
        assertEquals(2, runs.size)
        assertEquals(IsoPlayRun(1_000, 500), runs[0])
        assertEquals(IsoPlayRun(9_000, 150), runs[1])
    }

    @Test
    fun coalesceKeepsSingleRunAndDropsEmptyExtents() {
        assertEquals(listOf(IsoPlayRun(2_048, 192)), coalesceRuns(listOf(IsoExtent(2_048, 192))))
        assertEquals(listOf(IsoPlayRun(2_048, 192)), coalesceRuns(listOf(IsoExtent(2_048, 192), IsoExtent(2_240, 0))))
        assertEquals(0, coalesceRuns(emptyList()).size)
    }

    private fun target(name: String, offset: Long, length: Long) = IsoPlayTarget(offset, length, name)

    private fun playlist(name: String, vararg clips: String) = IsoPlaylist(
        name,
        clips.map { IsoPlaylistSegment(it, 0L, 1_000_000L) },
        clips.size * 1_000L,
    )
}
