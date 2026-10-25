package com.github.avbox.osc.ui.navbar

import com.github.avbox.osc.ui.WindowWidthClass
import com.github.avbox.osc.ui.theme.GLASS_BACKDROP_BAND_MARGIN_DP

object NavMetrics {

    const val MARGIN_DP = 12

    const val BAR_CROSS_DP = 64

    const val SLOT_MAIN_DP = 56

    const val FLOATING_OVERLAY_DP = BAR_CROSS_DP + MARGIN_DP

    const val SURFACE_RAIL_WIDTH_DP = 80

    const val BAND_EXTENT_DP = FLOATING_OVERLAY_DP + GLASS_BACKDROP_BAND_MARGIN_DP

    fun axisFor(widthClass: WindowWidthClass): NavAxis =
        if (widthClass == WindowWidthClass.Compact) NavAxis.Horizontal else NavAxis.Vertical

    fun actionSlotFor(tabCount: Int): Int = tabCount / 2

    fun slotIndexOfTab(tab: Int, actionSlot: Int?): Int =
        if (actionSlot != null && tab >= actionSlot) tab + 1 else tab

    fun tabIndexOfSlot(slot: Int, actionSlot: Int?): Int =
        if (actionSlot != null && slot > actionSlot) slot - 1 else slot

    fun reserveDp(glassEnabled: Boolean, axis: NavAxis): Int = when {
        axis == NavAxis.Vertical -> if (glassEnabled) FLOATING_OVERLAY_DP else SURFACE_RAIL_WIDTH_DP
        glassEnabled -> FLOATING_OVERLAY_DP
        else -> 0
    }
}
