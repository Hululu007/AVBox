package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IsoReadWindowTest {

    private val target = IsoPlayTarget(1000, 500, "sample.m2ts")

    @Test
    fun fullRange_mapsToIsoOffset() {
        assertEquals(IsoReadWindow(1000, 500), isoReadWindow(target, 0, C.LENGTH_UNSET.toLong()))
        assertEquals(IsoReadWindow(1000, 200), isoReadWindow(target, 0, 200))
    }

    @Test
    fun offsetRange_mapsAndClamps() {
        assertEquals(IsoReadWindow(1400, 100), isoReadWindow(target, 400, 2048))
        assertEquals(IsoReadWindow(1500, 0), isoReadWindow(target, 500, 2048))
    }

    @Test
    fun positionPastEnd_throws() {
        assertThrows(IsoFormatException::class.java) {
            isoReadWindow(target, 501, 1)
        }
    }

    @Test
    fun startOffsetShiftsReadWindowAndClampsToAvailable() {
        val rap = IsoPlayTarget(1000, 500, "sample.m2ts", startOffset = 192, durationMs = 1000)
        assertEquals(500L - 192L, rap.availableLength)
        assertEquals(IsoReadWindow(1192, 308), isoReadWindow(rap, 0, C.LENGTH_UNSET.toLong()))
        assertEquals(IsoReadWindow(1292, 208), isoReadWindow(rap, 100, C.LENGTH_UNSET.toLong()))
        assertEquals(0L, isoReadWindow(rap, 308, 64).length)
    }

    @Test
    fun negativePositionWithStartOffset_throws() {
        val rap = IsoPlayTarget(1000, 500, "sample.m2ts", startOffset = 256)
        assertThrows(IsoFormatException::class.java) {
            isoReadWindow(rap, -1, 1)
        }
    }

    @Test
    fun multiRun_readSplitsAtRunBoundary() {
        val split = IsoPlayTarget(
            listOf(IsoPlayRun(1_000, 300), IsoPlayRun(9_000, 200)),
            500,
            "split.m2ts",
        )
        val windows = isoReadWindows(split, 0, C.LENGTH_UNSET.toLong())
        assertEquals(2, windows.size)
        assertEquals(IsoReadWindow(1_000, 300), windows[0])
        assertEquals(IsoReadWindow(9_000, 200), windows[1])
        assertEquals(IsoReadWindow(1_000, 300), isoReadWindow(split, 0, C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun multiRun_secondRunOnlyWhenPositionInsideIt() {
        val split = IsoPlayTarget(
            listOf(IsoPlayRun(1_000, 300), IsoPlayRun(9_000, 200)),
            500,
            "split.m2ts",
        )
        assertEquals(listOf(IsoReadWindow(1_100, 100)), isoReadWindows(split, 100, 100))
        assertEquals(listOf(IsoReadWindow(9_000, 200)), isoReadWindows(split, 300, C.LENGTH_UNSET.toLong()))
        assertEquals(listOf(IsoReadWindow(9_050, 50)), isoReadWindows(split, 350, 50))
    }

    @Test
    fun multiRun_startOffsetLandingInSecondRun() {
        val split = IsoPlayTarget(
            listOf(IsoPlayRun(1_000, 300), IsoPlayRun(9_000, 200)),
            500,
            "split.m2ts",
            startOffset = 320,
        )
        assertEquals(180L, split.availableLength)
        assertEquals(listOf(IsoReadWindow(9_020, 180)), isoReadWindows(split, 0, C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun multiRun_runsShorterThanDeclared_throws() {
        val broken = IsoPlayTarget(listOf(IsoPlayRun(1_000, 300)), 500, "broken.m2ts")
        assertThrows(IsoFormatException::class.java) {
            isoReadWindows(broken, 0, C.LENGTH_UNSET.toLong())
        }
    }

    @Test
    fun closeGuard_runsOnce() {
        val guard = IsoCloseGuard()
        var closed = 0
        guard.close { closed++ }
        assertEquals(0, closed)
        guard.activate()
        guard.close { closed++ }
        guard.close { closed++ }
        assertEquals(1, closed)
    }
}
