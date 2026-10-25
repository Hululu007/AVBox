package com.github.avbox.osc.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingNoteTest {

    @Test
    fun isRatingNote_recognizesNumericForms() {
        assertTrue(isRatingNote("8.1"))
        assertTrue(isRatingNote("评分8.1"))
        assertTrue(isRatingNote("评分：9.0"))
        assertTrue(isRatingNote("8.1分"))
    }

    @Test
    fun isRatingNote_keepsTextNotes() {
        assertFalse(isRatingNote("更新至第11集"))
        assertFalse(isRatingNote("全36集"))
        assertFalse(isRatingNote(""))
        assertFalse(isRatingNote(null))
    }
}
