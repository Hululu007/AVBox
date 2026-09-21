package com.github.tvbox.osc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WindowSize] 的纯函数单测(纯 JVM,无需 Robolectric)。
 *
 * 选点理由:分档算错不会崩,只会"平板上卡片大得离谱""手机上列数莫名少一列"或"平板又被锁回竖屏",
 * 真机上很难判断是分档判据问题还是布局问题,只能靠单测把边界固定下来。
 */
class WindowSizeTest {

    // ---------- 宽度分档边界 ----------

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

    // ---------- 方向策略 ----------

    @Test
    fun shouldLockPortrait_onlyBelowLargeScreenThreshold() {
        assertTrue(WindowSize.shouldLockPortrait(360))
        assertTrue(WindowSize.shouldLockPortrait(599))
        assertFalse(WindowSize.shouldLockPortrait(600))
        assertFalse(WindowSize.shouldLockPortrait(1280))
    }

    @Test
    fun shouldLockPortrait_matchesPlatformIgnoredRange() {
        // 平台在 sw>=600dp 忽略方向限制,策略必须与之一致,否则会出现"应用仍锁竖屏但系统已放开"的错位
        for (sw in 0..1400 step 20) {
            assertEquals(sw < 600, WindowSize.shouldLockPortrait(sw))
        }
    }

    // ---------- 栅格列数 ----------

    @Test
    fun gridColumns_keepsPhoneAtThreeColumns() {
        // 手机档必须与原布局一致:360dp 屏去掉 32dp 内边距后可用 328dp,按公式算出 2 列,被下限抬回 3
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
        // 目标卡宽 120–160dp(spec §4.11)。逐档核对,防止"改目标值/改间距"把卡宽带出区间。
        fun cardWidth(availableWidthDp: Int, columns: Int): Int =
            (availableWidthDp - (columns - 1) * WindowSize.GRID_COLUMN_SPACING_DP) / columns

        // 360=手机 / 600=Medium 下沿 / 840=Expanded 下沿 / 1116=真机实测窗口 / 1280=常见平板横屏
        for (windowWidth in listOf(360, 600, 840, 1116, 1280)) {
            val available = windowWidth - 32
            val columns = WindowSize.gridColumns(available, minColumns = 3)
            val card = cardWidth(available, columns)
            if (windowWidth == 360) {
                // 手机档由下限兜底,维持既有卡宽,不参与目标区间校验
                assertEquals(101, card)
            } else {
                assertTrue("窗口 ${windowWidth}dp / ${columns}列 的卡宽 $card 超出 120–160dp", card in 120..160)
            }
        }
    }
}
