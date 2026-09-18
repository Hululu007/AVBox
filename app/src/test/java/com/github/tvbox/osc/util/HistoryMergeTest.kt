package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMergeTest {

    private fun dedupe(vararg names: String): List<String> =
        HistoryMerge.dedupe(names.toList()) { it }.first

    @Test
    fun dedupe_reportsMergedRecordsForRemoval() {
        val (kept, dropped) = HistoryMerge.dedupe(
            listOf("庆余年", "斗罗大陆", "庆余年", "庆余年(2019)", "鹿鼎记(1998)", "鹿鼎记(2020)"),
        ) { it }
        assertEquals(listOf("庆余年", "斗罗大陆", "鹿鼎记(1998)", "鹿鼎记(2020)"), kept)
        assertEquals(listOf("庆余年", "庆余年(2019)"), dropped)
    }

    @Test
    fun dedupe_keepsFirstOccurrence() {
        // 输入按时间倒序,保留最新一条
        assertEquals(listOf("庆余年", "斗罗大陆"), dedupe("庆余年", "斗罗大陆", "庆余年"))
    }

    @Test
    fun dedupe_mergesBracketNotesAndCase() {
        assertEquals(listOf("庆余年(2019)"), dedupe("庆余年(2019)", "庆余年【全46集】", "庆 余年"))
        assertEquals(listOf("Stranger Things"), dedupe("Stranger Things", "stranger-things"))
    }

    @Test
    fun dedupe_distinguishesTitleBody() {
        // 括号外的差异(季数/续集)必须区分,否则「庆余年」会把全系列合并成一条
        assertEquals(
            listOf("庆余年", "庆余年 第二季", "庆余年2"),
            dedupe("庆余年", "庆余年 第二季", "庆余年2"),
        )
    }

    @Test
    fun dedupe_keepsDifferentYearsSeparately() {
        assertEquals(listOf("鹿鼎记(1998)", "鹿鼎记(2020)"), dedupe("鹿鼎记(1998)", "鹿鼎记(2020)"))
    }

    @Test
    fun dedupe_mergesWhenOneSideHasNoYear() {
        assertEquals(listOf("鹿鼎记(1998)"), dedupe("鹿鼎记(1998)", "鹿鼎记"))
        assertEquals(listOf("鹿鼎记"), dedupe("鹿鼎记", "鹿鼎记(2020)"))
    }

    @Test
    fun dedupe_keepsEmptyOrSymbolNames() {
        assertEquals(listOf("", "", "!!!"), dedupe("", "", "!!!"))
    }

    @Test
    fun isSameTitle_basics() {
        assertTrue(HistoryMerge.isSameTitle("庆余年(2019)", "庆余年"))
        assertTrue(HistoryMerge.isSameTitle("鹿鼎记(1998)", "鹿鼎记"))
        assertFalse(HistoryMerge.isSameTitle("鹿鼎记(1998)", "鹿鼎记(2020)"))
        assertFalse(HistoryMerge.isSameTitle("庆余年", "庆余年 第二季"))
        assertFalse(HistoryMerge.isSameTitle(null, "庆余年"))
        assertFalse(HistoryMerge.isSameTitle("", ""))
    }
}
