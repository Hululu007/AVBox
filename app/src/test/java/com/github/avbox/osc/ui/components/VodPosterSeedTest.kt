package com.github.avbox.osc.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPosterSeedTest {

    @Test
    fun firstCharTakesLeadingCharacter() {
        assertEquals("因", posterFirstChar("因果报应"))
        assertEquals("K", posterFirstChar("K-POP：猎魔女团"))
    }

    @Test
    fun firstCharIgnoresSurroundingWhitespace() {
        assertEquals("峡", posterFirstChar("  峡谷 "))
    }

    @Test
    fun firstCharKeepsSurrogatePairWhole() {
        assertEquals("\uD83C\uDFAC", posterFirstChar("\uD83C\uDFAC 某电影"))
    }

    @Test
    fun firstCharFallsBackWhenNameMissing() {
        assertEquals(UNNAMED, posterFirstChar(null))
        assertEquals(UNNAMED, posterFirstChar(""))
        assertEquals(UNNAMED, posterFirstChar("   "))
    }

    @Test
    fun seedIsStableForSameName() {
        assertEquals(posterSeedColor("因果报应"), posterSeedColor("因果报应"))
        assertEquals(posterSeedColor("因果报应"), posterSeedColor(" 因果报应 "))
    }

    @Test
    fun seedIgnoresNameTail() {
        assertEquals(posterSeedColor("因果报应"), posterSeedColor("因果报应 2024"))
        assertEquals(posterSeedColor("因果报应"), posterSeedColor("因果报应(国语)"))
    }

    @Test
    fun differentFirstCharsGiveDifferentSeeds() {
        assertNotEquals(posterSeedColor("因果报应"), posterSeedColor("峡谷"))
    }

    @Test
    fun seedIsOpaque() {
        listOf("因果报应", "峡谷", "K-POP：猎魔女团", null).forEach { name ->
            val argb = posterSeedColor(name)
            assertEquals("占位底色必须不透明: $name", 0xFF, argb ushr 24 and 0xFF)
        }
    }

    @Test
    fun negativeHashStaysInRange() {
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertTrue(posterSeedColor("polygenelubricants") in KNOWN_PALETTE)
    }

    @Test
    fun everySampleStaysInsidePalette() {
        listOf(
            "因果报应", "关于我和鬼变成家人的那件事", "峡谷", "终极对决", "阳光普照",
            "谁先爱上他的", "K-POP：猎魔女团", "polygenelubricants", UNNAMED,
        ).forEach { name ->
            assertTrue("占位底色落在调色板外: $name", posterSeedColor(name) in KNOWN_PALETTE)
        }
    }

    @Test
    fun distinctFirstCharsSpreadAcrossPalette() {
        val seeds = listOf("因", "关", "峡", "终", "阳", "谁", "K", "p", UNNAMED).map { posterSeedColor(it) }
        assertTrue("首字色位过度集中: ${seeds.toSet()}", seeds.toSet().size >= 5)
    }

    private companion object {
        const val UNNAMED = "！"

        val KNOWN_PALETTE = setOf(
            0xFFEF5350.toInt(), 0xFFEC407A.toInt(), 0xFFAB47BC.toInt(), 0xFF7E57C2.toInt(),
            0xFF5C6BC0.toInt(), 0xFF42A5F5.toInt(), 0xFF29B6F6.toInt(), 0xFF26C6DA.toInt(),
            0xFF26A69A.toInt(), 0xFF66BB6A.toInt(), 0xFF9CCC65.toInt(), 0xFFD4E157.toInt(),
            0xFFFFEE58.toInt(), 0xFFFFCA28.toInt(), 0xFFFFA726.toInt(), 0xFFFF7043.toInt(),
            0xFF8D6E63.toInt(), 0xFFBDBDBD.toInt(), 0xFF78909C.toInt(),
        )
    }
}
