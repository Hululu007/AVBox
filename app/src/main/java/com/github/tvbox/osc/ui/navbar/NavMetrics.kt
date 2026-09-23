package com.github.tvbox.osc.ui.navbar

import com.github.tvbox.osc.ui.WindowWidthClass
import com.github.tvbox.osc.ui.components.GLASS_BACKDROP_BAND_MARGIN_DP

/**
 * 导航壳的几何常量与判据(纯函数,见 spec §4.11)。
 *
 * 集中在这里的原因:这些值必须与 [NavAxis] 联动 —— 改一个忘了改另一个就会出现
 * "竖条按横条的宽度让位""遮罩把白色糊在内容侧"这类错位,而它们只在真机上显形。
 */
object NavMetrics {

    /** 悬浮导航距屏幕边缘的间距 */
    const val MARGIN_DP = 12

    /** 导航条交叉轴长度(横条的高 / 竖条的宽) */
    const val BAR_CROSS_DP = 64

    /** 悬浮导航在主轴方向的占位:横条 = 高 + 间距,竖条 = 宽 + 间距 */
    const val FLOATING_OVERLAY_DP = BAR_CROSS_DP + MARGIN_DP

    /** 关闭玻璃后回退用的 M3 NavigationRail 宽度 */
    const val SURFACE_RAIL_WIDTH_DP = 80

    /** 玻璃源层 band 在主轴方向的长度:导航占位 + 取景余量 */
    const val BAND_EXTENT_DP = FLOATING_OVERLAY_DP + GLASS_BACKDROP_BAND_MARGIN_DP

    /** 导航形态:Compact 用底部横条,Medium/Expanded 用侧边竖条 */
    fun axisFor(widthClass: WindowWidthClass): NavAxis =
        if (widthClass == WindowWidthClass.Compact) NavAxis.Horizontal else NavAxis.Vertical

    /** 中央动作槽的槽位下标:两侧各留一半页面,即"历史 | 直播 | 收藏" */
    fun actionSlotFor(tabCount: Int): Int = tabCount / 2

    /** 页面下标 → 槽位下标(动作槽插在中间,它右侧的页面整体右移一格);只用于"定位",不做插值 */
    fun slotIndexOfTab(tab: Int, actionSlot: Int?): Int =
        if (actionSlot != null && tab >= actionSlot) tab + 1 else tab

    /**
     * 槽位下标 → 页面下标。
     *
     * ⚠️ 动作槽没有页面,故**不是** [slotIndexOfTab] 的逆(槽位 2 与 3 都指向页面 2);
     * 成立的不变量只有单向的"页面 → 槽位 → 页面"回到原页面。
     */
    fun tabIndexOfSlot(slot: Int, actionSlot: Int?): Int =
        if (actionSlot != null && slot > actionSlot) slot - 1 else slot

    /**
     * 页面在主轴方向要让开的导航占位(dp)。
     *
     * 关玻璃是回退到 M3 surface 导航、不是取消竖条,所以竖条档两种皮肤都要占位;
     * 横条档关玻璃后由 Scaffold 的 bottomBar 承担留白(走 innerPadding),覆盖层不再占位。
     */
    fun reserveDp(glassEnabled: Boolean, axis: NavAxis): Int = when {
        axis == NavAxis.Vertical -> if (glassEnabled) FLOATING_OVERLAY_DP else SURFACE_RAIL_WIDTH_DP
        glassEnabled -> FLOATING_OVERLAY_DP
        else -> 0
    }

    /**
     * 遮罩渐变的不透明端是否落在渐变的"起点"。
     *
     * 不透明端必须贴屏幕边缘:横条贴屏幕底边 ⇒ 不透明在终点(1f);竖条贴屏幕左边 ⇒ 不透明在起点(0f)。
     * 照抄横条的 0f/1f 顺序会让竖条把半透明白糊在内容侧(真机确认过的回归)。
     */
    fun scrimOpaqueAtStart(axis: NavAxis): Boolean = axis == NavAxis.Vertical
}
