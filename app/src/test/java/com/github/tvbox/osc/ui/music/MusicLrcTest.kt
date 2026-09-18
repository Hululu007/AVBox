package com.github.tvbox.osc.ui.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLrcTest {

    @Test
    fun parseLrcWithMultipleTimestamps() {
        val lines = MusicLrc.parse("[ti:标题]\n[00:12.34]第一句\n[00:15.00][01:20.00]第二句")
        assertEquals(3, lines.size)
        assertEquals(12340L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(15000L, lines[1].timeMs)
        assertEquals("第二句", lines[1].text)
        assertEquals(80000L, lines[2].timeMs)
    }

    @Test
    fun parseLrcKeepsShortFraction() {
        val lines = MusicLrc.parse("[00:01.5]半秒\n[01:02:100]冒号毫秒")
        assertEquals(1500L, lines[0].timeMs)
        assertEquals(62100L, lines[1].timeMs)
    }

    @Test
    fun parseSrtStripsBraceAndBreakTags() {
        val lines = MusicLrc.parse("1\n00:00:01,000 --> 00:00:03,000\n{\\an8}你好<br />世界\n")
        assertEquals(1, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("你好\n世界", lines[0].text)
    }

    @Test
    fun parseWithoutTimestampReturnsEmpty() {
        assertEquals(0, MusicLrc.parse("没有时间戳的普通文本").size)
    }

    @Test
    fun parseLrcWithDialogueWordIsNotMistakenForAss() {
        // ASS 判定只认"行首 Dialogue:",歌词正文里出现这个词不能被误判(误判 = 整首 0 行)
        val lines = MusicLrc.parse("[00:12.34]Dialogue: 一句歌词\n[00:15.00]第二句")
        assertEquals(2, lines.size)
        assertEquals("Dialogue: 一句歌词", lines[0].text)
        assertEquals(15000L, lines[1].timeMs)
    }

    @Test
    fun parseAssDialogueWithOverrideAndBreakTags() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1280
            PlayResY: 720

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:12.34,0:00:15.00,Default,,0,0,0,,第一句
            Dialogue: 0,0:00:15.00,0:00:18.00,Default,,0,0,0,,{\k50}第二句\N副歌,带逗号
        """.trimIndent()
        val lines = MusicLrc.parse(ass)
        assertEquals(2, lines.size)
        assertEquals(12340L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(15000L, lines[1].timeMs)
        // {\k50} 覆盖块被剥离、\N 还原为换行、Text 里的逗号保留
        assertEquals("第二句\n副歌,带逗号", lines[1].text)
    }
}
