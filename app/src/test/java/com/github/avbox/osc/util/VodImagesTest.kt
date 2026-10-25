package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodImagesTest {

    @Test
    fun upgradesDoubanSmallPosterToLarge() {
        assertEquals(
            "https://img3.doubanio.com/view/photo/l_ratio_poster/public/p2931851430.jpg",
            VodImages.largePosterUrl("https://img3.doubanio.com/view/photo/s_ratio_poster/public/p2931851430.jpg"),
        )
    }

    @Test
    fun keepsExtendedHeaderSuffix() {
        val raw = "https://img3.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg@Headers={\"Referer\":\"https://movie.douban.com/\"}"
        val upgraded = VodImages.largePosterUrl(raw).orEmpty()
        assertTrue(upgraded.startsWith("https://img3.doubanio.com/view/photo/l_ratio_poster/public/p1.jpg@Headers="))
        assertTrue(upgraded.endsWith("@Headers={\"Referer\":\"https://movie.douban.com/\"}"))
    }

    @Test
    fun doesNotTouchNonDoubanUrl() {
        assertEquals("https://cdn.example.com/a.jpg", VodImages.largePosterUrl("https://cdn.example.com/a.jpg"))
    }

    @Test
    fun nonDoubanWithSuffixStaysRaw() {
        val raw = "https://cdn.example.com/a.jpg@Referer=https://x.com/"
        assertEquals(raw, VodImages.largePosterUrl(raw))
    }

    @Test
    fun doesNotTouchDataUri() {
        val raw = "data:image/png;base64,AAAA"
        assertEquals(raw, VodImages.largePosterUrl(raw))
    }

    @Test
    fun doesNotTouchLargePosterAgain() {
        val raw = "https://img3.doubanio.com/view/photo/l_ratio_poster/public/p1.jpg"
        assertEquals(raw, VodImages.largePosterUrl(raw))
    }

    @Test
    fun nullStaysNull() {
        assertNull(VodImages.largePosterUrl(null))
    }
}
