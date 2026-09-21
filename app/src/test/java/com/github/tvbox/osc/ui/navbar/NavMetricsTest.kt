package com.github.tvbox.osc.ui.navbar

import com.github.tvbox.osc.ui.WindowWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavMetrics] 的纯函数单测(纯 JVM,无需 Robolectric)。
 *
 * 选点理由:这些判据算错不会崩,只会"竖条按横条的宽度让位""遮罩把白色糊在内容侧"——
 * 两种都只在真机上显形,而本项目已经因此返工过三次,故把规则钉进单测。
 */
class NavMetricsTest {

    // ---------- 形态判据 ----------

    @Test
    fun axisFor_onlyCompactUsesBottomBar() {
        assertEquals(NavAxis.Horizontal, NavMetrics.axisFor(WindowWidthClass.Compact))
        assertEquals(NavAxis.Vertical, NavMetrics.axisFor(WindowWidthClass.Medium))
        assertEquals(NavAxis.Vertical, NavMetrics.axisFor(WindowWidthClass.Expanded))
    }

    // ---------- 留白占位 ----------

    @Test
    fun reserveDp_railAlwaysReservesEvenWithoutGlass() {
        // 关玻璃是回退到 M3 surface 导航,不是取消竖条 ⇒ 竖条档两种皮肤都必须占位,否则内容被压住
        assertEquals(NavMetrics.FLOATING_OVERLAY_DP, NavMetrics.reserveDp(true, NavAxis.Vertical))
        assertEquals(NavMetrics.SURFACE_RAIL_WIDTH_DP, NavMetrics.reserveDp(false, NavAxis.Vertical))
    }

    @Test
    fun reserveDp_bottomBarOnlyReservesWhenFloating() {
        // 横条档关玻璃后由 Scaffold 的 bottomBar 承担留白(走 innerPadding),覆盖层不再占位
        assertEquals(NavMetrics.FLOATING_OVERLAY_DP, NavMetrics.reserveDp(true, NavAxis.Horizontal))
        assertEquals(0, NavMetrics.reserveDp(false, NavAxis.Horizontal))
    }

    @Test
    fun reserveDp_isNonNegativeAndNeverExceedsBand() {
        for (glass in listOf(true, false)) {
            for (axis in NavAxis.entries) {
                val reserve = NavMetrics.reserveDp(glass, axis)
                assertTrue("reserve 不能为负", reserve >= 0)
                // band 是给玻璃取景用的范围,必须能覆盖导航自身
                assertTrue("reserve 不能超出 band", reserve <= NavMetrics.BAND_EXTENT_DP)
            }
        }
    }

    @Test
    fun reserveDp_surfaceRailFitsItsOwnBar() {
        // 回退用的 M3 竖条至少要容得下导航条本身的交叉轴长度
        assertTrue(NavMetrics.SURFACE_RAIL_WIDTH_DP >= NavMetrics.BAR_CROSS_DP)
    }

    // ---------- 遮罩方向 ----------

    @Test
    fun scrimOpaqueAtStart_followsTheScreenEdge() {
        // 横条贴屏幕底边 ⇒ 不透明端在渐变终点(1f);竖条贴屏幕左边 ⇒ 不透明端在起点(0f)。
        // 照抄顺序会让竖条把半透明白糊在内容侧 —— 真机确认过的回归。
        assertFalse(NavMetrics.scrimOpaqueAtStart(NavAxis.Horizontal))
        assertTrue(NavMetrics.scrimOpaqueAtStart(NavAxis.Vertical))
    }

    // ---------- 常量关系 ----------

    @Test
    fun bandExtent_leavesRoomBeyondTheNavItself() {
        // band 要比导航本身宽出一段余量,否则玻璃取不到邻近内容、退化成纯色板
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
