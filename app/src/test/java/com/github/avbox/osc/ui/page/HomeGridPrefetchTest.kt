package com.github.avbox.osc.ui.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridPrefetchTest {

    @Test
    fun tabletFirstScreenWithHolePrefetches() {
        assertTrue(shouldPrefetchNextPage(lastVisibleIndex = 20, totalItemsCount = 22, columns = 7))
    }

    @Test
    fun phoneFirstScreenDoesNotPrefetch() {
        assertFalse(shouldPrefetchNextPage(lastVisibleIndex = 11, totalItemsCount = 22, columns = 3))
    }

    @Test
    fun afterPrefetchTailIsPushedAway() {
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
