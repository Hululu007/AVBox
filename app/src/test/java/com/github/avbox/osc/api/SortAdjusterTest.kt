package com.github.avbox.osc.api

import com.github.avbox.osc.bean.MovieSort
import org.junit.Assert.assertEquals
import org.junit.Test

class SortAdjusterTest {

    private fun sort(name: String) = MovieSort.SortData(name, name)

    @Test
    fun pickByCategories_keepsWhitelistOrder() {
        val list = listOf(sort("A"), sort("B"), sort("C"))
        val picked = SortAdjuster.pickByCategories(list, listOf("C", "A"))
        assertEquals(listOf("C", "A"), picked.map { it.name })
    }

    @Test
    fun pickByCategories_unmatchedFallsBackToAll() {
        val list = listOf(sort("A"), sort("B"))
        val picked = SortAdjuster.pickByCategories(list, listOf("X", "Y"))
        assertEquals(listOf("A", "B"), picked.map { it.name })
    }

    @Test
    fun pickByCategories_emptyListStaysEmpty() {
        val picked = SortAdjuster.pickByCategories(emptyList(), listOf("X"))
        assertEquals(0, picked.size)
    }
}
