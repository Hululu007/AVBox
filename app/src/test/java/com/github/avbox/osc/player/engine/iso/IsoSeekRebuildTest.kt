package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Test

class IsoSeekRebuildTest {

    @Test
    fun nonIso_alwaysAllowed() {
        assertEquals(IsoSeekAction.ALLOW, action(isoSession = false, positionMs = 600_000L))
        assertEquals(IsoSeekAction.ALLOW, action(isoSession = false, positionMs = 0L))
    }

    @Test
    fun isoWithinTolerance_allowed() {
        assertEquals(IsoSeekAction.ALLOW, action(isoSession = true, positionMs = 0L))
        assertEquals(IsoSeekAction.ALLOW, action(isoSession = true, positionMs = TOLERANCE))
    }

    @Test
    fun isoFarSeek_rebuildsWhenEnabled() {
        assertEquals(IsoSeekAction.REBUILD, action(isoSession = true, positionMs = TOLERANCE + 1L))
    }

    @Test
    fun isoBackToStartFromMiddle_rebuilds() {
        assertEquals(
            IsoSeekAction.REBUILD,
            action(isoSession = true, positionMs = 0L, currentMs = 600_000L),
        )
    }

    @Test
    fun isoSmallBackStepWithinStartZone_allowed() {
        assertEquals(
            IsoSeekAction.ALLOW,
            action(isoSession = true, positionMs = 500L, currentMs = 900L),
        )
    }

    @Test
    fun isoSmallBackStepAfterStart_rebuilds() {
        assertEquals(
            IsoSeekAction.REBUILD,
            action(isoSession = true, positionMs = 4_500L, currentMs = 5_000L),
        )
    }

    @Test
    fun rebuildDisabledBlocksMidSeek() {
        assertEquals(
            IsoSeekAction.BLOCK,
            action(isoSession = true, positionMs = 600_000L, rebuildEnabled = false),
        )
        assertEquals(
            IsoSeekAction.ALLOW,
            action(isoSession = true, positionMs = 0L, rebuildEnabled = false),
        )
    }

    @Test
    fun isoWithoutPlaylistDurations_blocks() {
        assertEquals(
            IsoSeekAction.BLOCK,
            action(isoSession = true, positionMs = 600_000L, seekable = false),
        )
        assertEquals(
            IsoSeekAction.ALLOW,
            action(isoSession = true, positionMs = 0L, seekable = false),
        )
    }

    @Test
    fun isoFarSeek_blocksWhenRebuildDisabled() {
        assertEquals(
            IsoSeekAction.BLOCK,
            isoSeekAction(
                isoSession = true,
                seekable = true,
                positionMs = TOLERANCE + 1L,
                currentMs = 0L,
                toleranceMs = TOLERANCE,
                rebuildEnabled = false,
            ),
        )
    }

    @Test
    fun disabledRebuild_stillBlocksFarSeek() {
        assertEquals(
            IsoSeekAction.BLOCK,
            isoSeekAction(
                isoSession = true,
                seekable = true,
                positionMs = 1_000_000L,
                currentMs = 0L,
                toleranceMs = TOLERANCE,
                rebuildEnabled = false,
            ),
        )
        assertEquals(
            IsoSeekAction.ALLOW,
            isoSeekAction(
                isoSession = false,
                seekable = false,
                positionMs = 1_000_000L,
                currentMs = 0L,
                toleranceMs = TOLERANCE,
                rebuildEnabled = false,
            ),
        )
    }

    private fun action(
        isoSession: Boolean,
        positionMs: Long,
        currentMs: Long = 0L,
        seekable: Boolean = true,
        rebuildEnabled: Boolean = true,
    ): IsoSeekAction = isoSeekAction(
        isoSession = isoSession,
        seekable = seekable,
        positionMs = positionMs,
        currentMs = currentMs,
        toleranceMs = TOLERANCE,
        rebuildEnabled = rebuildEnabled,
    )

    private companion object {
        const val TOLERANCE = 1000L
    }
}
