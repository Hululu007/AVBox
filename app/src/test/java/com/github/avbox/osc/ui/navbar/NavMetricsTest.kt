package com.github.avbox.osc.ui.navbar

import com.github.avbox.osc.ui.WindowWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMetricsTest {

    @Test
    fun axisFor_onlyCompactUsesBottomBar() {
        assertEquals(NavAxis.Horizontal, NavMetrics.axisFor(WindowWidthClass.Compact))
        assertEquals(NavAxis.Vertical, NavMetrics.axisFor(WindowWidthClass.Medium))
        assertEquals(NavAxis.Vertical, NavMetrics.axisFor(WindowWidthClass.Expanded))
    }

    @Test
    fun reserveDp_railAlwaysReservesEvenWithoutGlass() {
        assertEquals(NavMetrics.FLOATING_OVERLAY_DP, NavMetrics.reserveDp(true, NavAxis.Vertical))
        assertEquals(NavMetrics.SURFACE_RAIL_WIDTH_DP, NavMetrics.reserveDp(false, NavAxis.Vertical))
    }

    @Test
    fun reserveDp_bottomBarOnlyReservesWhenFloating() {
        assertEquals(NavMetrics.FLOATING_OVERLAY_DP, NavMetrics.reserveDp(true, NavAxis.Horizontal))
        assertEquals(0, NavMetrics.reserveDp(false, NavAxis.Horizontal))
    }

    @Test
    fun reserveDp_isNonNegativeAndNeverExceedsBand() {
        for (glass in listOf(true, false)) {
            for (axis in NavAxis.entries) {
                val reserve = NavMetrics.reserveDp(glass, axis)
                assertTrue("reserve 不能为负", reserve >= 0)
                assertTrue("reserve 不能超出 band", reserve <= NavMetrics.BAND_EXTENT_DP)
            }
        }
    }

    @Test
    fun reserveDp_surfaceRailFitsItsOwnBar() {
        assertTrue(NavMetrics.SURFACE_RAIL_WIDTH_DP >= NavMetrics.BAR_CROSS_DP)
    }

    @Test
    fun actionSlotFor_isSymmetricAroundTheCentre() {
        assertEquals(2, NavMetrics.actionSlotFor(4))
        assertEquals(2, NavMetrics.actionSlotFor(5))
    }

    @Test
    fun slotIndexOfTab_skipsTheActionSlot() {
        val action = NavMetrics.actionSlotFor(4)
        assertEquals(0, NavMetrics.slotIndexOfTab(0, action))
        assertEquals(1, NavMetrics.slotIndexOfTab(1, action))
        assertEquals(3, NavMetrics.slotIndexOfTab(2, action))
        assertEquals(4, NavMetrics.slotIndexOfTab(3, action))
    }

    @Test
    fun slotIndexOfTab_roundTripsBackToTheSamePage() {
        val action = NavMetrics.actionSlotFor(4)
        for (page in 0..3) {
            assertEquals(
                "页面 $page 的往返必须闭合",
                page,
                NavMetrics.tabIndexOfSlot(NavMetrics.slotIndexOfTab(page, action), action),
            )
        }
    }

    @Test
    fun slotIndexOfTab_withoutActionSlot_isIdentity() {
        for (tab in 0..3) {
            assertEquals(tab, NavMetrics.slotIndexOfTab(tab, null))
        }
    }

    @Test
    fun tabIndexOfSlot_withoutActionSlot_isIdentity() {
        for (slot in 0..3) {
            assertEquals(slot, NavMetrics.tabIndexOfSlot(slot, null))
        }
    }

    @Test
    fun actionSlotIsTheOnlySlotWithoutAPage() {
        val action = NavMetrics.actionSlotFor(4)
        val pages = (0..4).filter { it != action }.map { NavMetrics.tabIndexOfSlot(it, action) }
        assertEquals(listOf(0, 1, 2, 3), pages.sorted())
        assertEquals(4, pages.size)
    }

    @Test
    fun bandExtent_leavesRoomBeyondTheNavItself() {
        assertTrue(NavMetrics.BAND_EXTENT_DP > NavMetrics.FLOATING_OVERLAY_DP)
    }

    @Test
    fun overlayReserve_isBarPlusMargin() {
        assertEquals(
            NavMetrics.BAR_CROSS_DP + NavMetrics.MARGIN_DP,
            NavMetrics.FLOATING_OVERLAY_DP,
        )
    }
}
