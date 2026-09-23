package com.github.tvbox.osc.ui.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldPrefetchNextPage] 单测:判错的后果是首屏末行右侧留一个空格(平板 7 列 + 每页 20 张必现),
 * 或反过来在末尾附近反复拉取。口径 = "最后一个可见项离末尾不足一行"。
 */
class HomeGridPrefetchTest {

    @Test
    fun tabletFirstScreenWithHolePrefetches() {
        // 平板 7 列 + 筛选行 + 20 张 + 末尾哨兵:首屏最后一个可见项是第 20 张,末行只填了 6 格
        assertTrue(shouldPrefetchNextPage(lastVisibleIndex = 20, totalItemsCount = 22, columns = 7))
    }

    @Test
    fun phoneFirstScreenDoesNotPrefetch() {
        // 手机 3 列:首屏只看到第 11 项,离末尾还很远,继续靠末尾哨兵按需加载
        assertFalse(shouldPrefetchNextPage(lastVisibleIndex = 11, totalItemsCount = 22, columns = 3))
    }

    @Test
    fun afterPrefetchTailIsPushedAway() {
        // 取到第二页后末尾被推远,条件不再成立 ⇒ 不会连环拉取
        assertFalse(shouldPrefetchNextPage(lastVisibleIndex = 20, totalItemsCount = 42, columns = 7))
    }

    @Test
    fun reachingTheEndPrefetches() {
        assertTrue(shouldPrefetchNextPage(lastVisibleIndex = 21, totalItemsCount = 22, columns = 7))
        assertTrue(shouldPrefetchNextPage(lastVisibleIndex = 22, totalItemsCount = 22, columns = 3))
    }

    @Test
    fun emptyContentDoesNotPrefetch() {
        assertFalse(shouldPrefetchNextPage(lastVisibleIndex = 0, totalItemsCount = 0, columns = 7))
    }
}
