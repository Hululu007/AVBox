package com.github.avbox.osc.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroScrollProgressTest {

    @Test
    fun progress_zeroAtTop() {
        assertEquals(0f, heroScrollProgress(0, 0, 600), 0.001f)
    }

    @Test
    fun progress_scalesWithOffset() {
        assertEquals(0.5f, heroScrollProgress(0, 300, 600), 0.001f)
    }

    @Test
    fun progress_clampsAtOne() {
        assertEquals(1f, heroScrollProgress(0, 900, 600), 0.001f)
    }

    @Test
    fun progress_fullWhenHeroGone() {
        assertEquals(1f, heroScrollProgress(2, 0, 600), 0.001f)
    }

    @Test
    fun progress_zeroForBadReference() {
        assertEquals(0f, heroScrollProgress(0, 100, 0), 0.001f)
    }
}
