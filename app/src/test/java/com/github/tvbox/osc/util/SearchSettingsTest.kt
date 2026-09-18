package com.github.tvbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSettingsTest {

    @Test
    fun exactMatch_ignoresBracketNotes() {
        assertTrue(SearchSettings.isExactMatch("庆余年(2019)", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("庆余年【全46集】", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("庆余年 [1080P]", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("庆余年（第一季）", "庆余年"))
    }

    @Test
    fun exactMatch_ignoresSpacePunctuationAndCase() {
        assertTrue(SearchSettings.isExactMatch(" 庆 余年 ", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("Stranger-Things", "stranger things"))
    }

    @Test
    fun exactMatch_distinguishesTitleBody() {
        // 括号外的差异(季数/续集/子标题)必须区分 —— 否则"精准"等于没开
        assertFalse(SearchSettings.isExactMatch("庆余年 第二季", "庆余年"))
        assertFalse(SearchSettings.isExactMatch("庆余年2", "庆余年"))
        assertFalse(SearchSettings.isExactMatch("庆余年之少年纵横", "庆余年"))
    }

    @Test
    fun exactMatch_emptyAndNullAreSafe() {
        assertFalse(SearchSettings.isExactMatch(null, "庆余年"))
        assertFalse(SearchSettings.isExactMatch("庆余年", null))
        assertFalse(SearchSettings.isExactMatch("", ""))
    }
}
