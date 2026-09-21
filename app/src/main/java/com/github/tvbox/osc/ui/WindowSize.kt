package com.github.tvbox.osc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 窗口宽度分档(M3 惯例) */
enum class WindowWidthClass { Compact, Medium, Expanded }

/**
 * 窗口分档与方向策略的唯一判据来源。
 *
 * 两个判据必须分开用:方向策略看 `smallestWidthDp` —— 平台规则用的就是"显示屏最小宽度",与设备方向无关,
 * 换成当前窗口宽度会在横屏时自己变;布局分档看当前窗口宽度 —— 分屏/自由窗口下窗口可能远窄于屏幕。
 */
object WindowSize {

    const val MEDIUM_MIN_WIDTH_DP = 600

    const val EXPANDED_MIN_WIDTH_DP = 840

    /** 平台"大屏"门槛:API 36+ 在此宽度以上忽略应用声明的方向限制 */
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600

    @JvmStatic
    fun classify(windowWidthDp: Int): WindowWidthClass = when {
        windowWidthDp < MEDIUM_MIN_WIDTH_DP -> WindowWidthClass.Compact
        windowWidthDp < EXPANDED_MIN_WIDTH_DP -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }

    /** sw<600dp 锁竖屏 —— 与平台忽略范围一致,故手机档行为不变,大屏交由用户旋转/折叠 */
    @JvmStatic
    fun shouldLockPortrait(smallestWidthDp: Int): Boolean =
        smallestWidthDp < LARGE_SCREEN_MIN_WIDTH_DP

    /** 目标卡片宽度(dp):列数由可用宽度除以它得出,使卡宽在各档窗口下都落在 120–160dp */
    const val TARGET_CARD_WIDTH_DP = 130

    /** 栅格列间距(dp),必须与各栅格实际使用的 `horizontalArrangement` 一致 */
    const val GRID_COLUMN_SPACING_DP = 12

    /**
     * 按可用宽度算栅格列数(入参是**去掉内容内边距之后**的可用宽度)。
     *
     * 为什么既不限宽居中、也不用 `GridCells.Adaptive`:
     * - 限宽居中会让栅格与**不在栅格里的兄弟元素**(分类 tab 行、顶栏)错开 —— 真机实测差 41dp,用户先发现的;
     * - `Adaptive` 会"尽量多塞",卡宽不可控。
     * 列数下限取手机档列数,保证 Compact 与原布局一致(手机 360dp 下算出来是 2,被下限抬回 3)。
     */
    @JvmStatic
    fun gridColumns(availableWidthDp: Int, minColumns: Int): Int {
        if (availableWidthDp <= 0) return minColumns
        val perColumn = TARGET_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP
        return ((availableWidthDp + GRID_COLUMN_SPACING_DP) / perColumn).coerceAtLeast(minColumns)
    }
}

/** 当前窗口宽度档。`@Preview` 传 `widthDp` 即可切档。 */
@Composable
fun currentWindowWidthClass(): WindowWidthClass =
    WindowSize.classify(LocalConfiguration.current.screenWidthDp)
