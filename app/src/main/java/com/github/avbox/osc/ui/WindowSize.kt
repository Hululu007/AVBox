package com.github.avbox.osc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

enum class WindowWidthClass { Compact, Medium, Expanded }

object WindowSize {

    const val MEDIUM_MIN_WIDTH_DP = 600

    const val EXPANDED_MIN_WIDTH_DP = 840

    const val LARGE_SCREEN_MIN_WIDTH_DP = 600

    @JvmStatic
    fun classify(windowWidthDp: Int): WindowWidthClass = when {
        windowWidthDp < MEDIUM_MIN_WIDTH_DP -> WindowWidthClass.Compact
        windowWidthDp < EXPANDED_MIN_WIDTH_DP -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }

    @JvmStatic
    fun shouldLockPortrait(smallestWidthDp: Int): Boolean =
        smallestWidthDp < LARGE_SCREEN_MIN_WIDTH_DP

    const val TARGET_CARD_WIDTH_DP = 130

    const val TARGET_ROW_CARD_WIDTH_DP = 200

    const val GRID_COLUMN_SPACING_DP = 12

    @JvmStatic
    fun gridColumns(availableWidthDp: Int, minColumns: Int): Int {
        if (availableWidthDp <= 0) return minColumns
        val perColumn = TARGET_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP
        return ((availableWidthDp + GRID_COLUMN_SPACING_DP) / perColumn).coerceAtLeast(minColumns)
    }

    @JvmStatic
    fun rowColumns(availableWidthDp: Int, maxColumns: Int): Int {
        if (availableWidthDp <= 0) return 1
        val perColumn = TARGET_ROW_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP
        return ((availableWidthDp + GRID_COLUMN_SPACING_DP) / perColumn).coerceIn(1, maxColumns)
    }
}

@Composable
fun currentWindowWidthClass(): WindowWidthClass =
    WindowSize.classify(LocalConfiguration.current.screenWidthDp)
