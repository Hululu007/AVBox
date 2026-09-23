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

    // ---------- 中央动作槽的槽位映射 ----------

    @Test
    fun actionSlotFor_isSymmetricAroundTheCentre() {
        // 4 个页面 ⇒ 槽位 2,即"首页 历史 | 直播 | 收藏 设置",两侧各两个
        assertEquals(2, NavMetrics.actionSlotFor(4))
        assertEquals(2, NavMetrics.actionSlotFor(5))
    }

    @Test
    fun slotIndexOfTab_skipsTheActionSlot() {
        val action = NavMetrics.actionSlotFor(4)
        // 页面 0/1 落在动作槽左侧,2/3 整体右移一格 ⇒ 胶囊只停 0/1/3/4,永不停在 2
        assertEquals(0, NavMetrics.slotIndexOfTab(0, action))
        assertEquals(1, NavMetrics.slotIndexOfTab(1, action))
        assertEquals(3, NavMetrics.slotIndexOfTab(2, action))
        assertEquals(4, NavMetrics.slotIndexOfTab(3, action))
    }

    @Test
    fun slotIndexOfTab_roundTripsBackToTheSamePage() {
        // 唯一需要成立的不变量:页面 → 槽位 → 页面 必须回到原页面
        // (反向不成立:动作槽没有页面,槽位 2 与 3 都指向页面 2)
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
        // 拖动落点全靠这两个函数的配合:除动作槽外,每个槽位都必须映射到某个页面
        val action = NavMetrics.actionSlotFor(4)
        val pages = (0..4).filter { it != action }.map { NavMetrics.tabIndexOfSlot(it, action) }
        assertEquals(listOf(0, 1, 2, 3), pages.sorted())
        assertEquals(4, pages.size)
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
