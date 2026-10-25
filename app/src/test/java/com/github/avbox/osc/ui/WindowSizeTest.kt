package com.github.avbox.osc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSizeTest {

    @Test
    fun classify_boundariesAreExclusiveOnTheLowerEdge() {
        assertEquals(WindowWidthClass.Compact, WindowSize.classify(599))
        assertEquals(WindowWidthClass.Medium, WindowSize.classify(600))
        assertEquals(WindowWidthClass.Medium, WindowSize.classify(839))
        assertEquals(WindowWidthClass.Expanded, WindowSize.classify(840))
    }

    @Test
    fun classify_coversPhoneAndTabletWidths() {
        assertEquals(WindowWidthClass.Compact, WindowSize.classify(360))
        assertEquals(WindowWidthClass.Medium, WindowSize.classify(800))
        assertEquals(WindowWidthClass.Expanded, WindowSize.classify(1280))
    }

    @Test
    fun shouldLockPortrait_onlyBelowLargeScreenThreshold() {
        assertTrue(WindowSize.shouldLockPortrait(360))
        assertTrue(WindowSize.shouldLockPortrait(599))
        assertFalse(WindowSize.shouldLockPortrait(600))
        assertFalse(WindowSize.shouldLockPortrait(1280))
    }

    @Test
    fun shouldLockPortrait_matchesPlatformIgnoredRange() {
        for (sw in 0..1400 step 20) {
            assertEquals(sw < 600, WindowSize.shouldLockPortrait(sw))
        }
    }

    @Test
    fun gridColumns_keepsPhoneAtThreeColumns() {
        assertEquals(3, WindowSize.gridColumns(328, minColumns = 3))
        assertEquals(2, WindowSize.gridColumns(328, minColumns = 2))
    }

    @Test
    fun gridColumns_growsWithAvailableWidth() {
        val phone = WindowSize.gridColumns(328, minColumns = 3)
        val medium = WindowSize.gridColumns(568, minColumns = 3)
        val tablet = WindowSize.gridColumns(1084, minColumns = 3)
        val wide = WindowSize.gridColumns(1248, minColumns = 3)
        assertTrue("列数应随可用宽度单调不减", phone <= medium && medium <= tablet && tablet <= wide)
        assertEquals(3, phone)
        assertEquals(4, medium)
        assertEquals(7, tablet)
        assertEquals(8, wide)
    }

    @Test
    fun gridColumns_isNonNegativeAndNeverBelowFloor() {
        for (width in listOf(-100, 0, 1, 50, 200, 1000, 4000)) {
            for (floor in 2..3) {
                assertTrue(WindowSize.gridColumns(width, floor) >= floor)
            }
        }
    }

    @Test
    fun gridColumns_keepsCardWidthInsideTargetBand() {
        fun cardWidth(availableWidthDp: Int, columns: Int): Int =
            (availableWidthDp - (columns - 1) * WindowSize.GRID_COLUMN_SPACING_DP) / columns

        for (windowWidth in listOf(360, 600, 840, 1116, 1280)) {
            val available = windowWidth - 32
            val columns = WindowSize.gridColumns(available, minColumns = 3)
            val card = cardWidth(available, columns)
            if (windowWidth == 360) {
                assertEquals(101, card)
            } else {
                assertTrue("窗口 ${windowWidth}dp / ${columns}列 的卡宽 $card 超出 120–160dp", card in 120..160)
            }
        }
    }

    @Test
    fun rowColumns_followsAvailableWidthUpToCap() {
        assertEquals(1, WindowSize.rowColumns(303, 3))
        assertEquals(2, WindowSize.rowColumns(492, 3))
        assertEquals(3, WindowSize.rowColumns(738, 3))
        assertEquals(3, WindowSize.rowColumns(2000, 3))
    }

    @Test
    fun rowColumns_neverLeavesTheCardBelowTargetWidth() {
        fun cardWidth(availableWidthDp: Int, columns: Int): Int =
            (availableWidthDp - (columns - 1) * WindowSize.GRID_COLUMN_SPACING_DP) / columns

        for (available in listOf(180, 240, 320, 492, 612, 738, 1084)) {
            val columns = WindowSize.rowColumns(available, 3)
            val card = cardWidth(available, columns)
            if (available >= WindowSize.TARGET_ROW_CARD_WIDTH_DP) {
                assertTrue("可用 ${available}dp / ${columns}列 的卡宽 $card 低于目标", card >= WindowSize.TARGET_ROW_CARD_WIDTH_DP)
            }
        }
    }

    @Test
    fun rowColumns_isNonNegativeAndNeverAboveCap() {
        for (width in listOf(-100, 0, 1, 50, 200, 1000, 4000)) {
            for (cap in 1..3) {
                val columns = WindowSize.rowColumns(width, cap)
                assertTrue(columns in 1..cap)
            }
        }
    }
}
