package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailHeroLayoutTest {

    @Test
    fun flat_offForPhonePortrait() {
        assertFalse(flatHeroLayout(360, 360, 800))
    }

    @Test
    fun flat_offForPhoneLandscape() {
        assertFalse(flatHeroLayout(360, 800, 360))
    }

    @Test
    fun flat_onForTabletLandscape() {
        assertTrue(flatHeroLayout(617, 1097, 617))
    }

    @Test
    fun flat_onAtSmallestWidthBoundary() {
        assertTrue(flatHeroLayout(600, 1000, 600))
    }

    @Test
    fun flat_offForTabletPortrait() {
        assertFalse(flatHeroLayout(617, 617, 1097))
    }

    @Test
    fun flat_offForSquareWindow() {
        assertFalse(flatHeroLayout(700, 700, 700))
    }
}
