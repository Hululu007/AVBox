package com.github.avbox.osc.ui

import com.github.avbox.osc.bean.EpgInfo
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LiveEpgParserTest {

    private fun gmt8(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("GMT+8:00")
    }

    private fun epg(startMs: Long, endMs: Long): EpgInfo {
        val info = EpgInfo(Date(0), "t", Date(0), "00:00", "01:00", 0)
        info.startdateTime = Date(startMs)
        info.enddateTime = Date(endMs)
        return info
    }

    @Test
    fun normalize_cctvSeriesEatsSuffix() {
        assertEquals("CCTV1", LiveEpgParser.normalizeEpgChannelName("CCTV-1 综合"))
        assertEquals("CCTV5+", LiveEpgParser.normalizeEpgChannelName("cctv5+高清"))
        assertEquals("CCTV1", LiveEpgParser.normalizeEpgChannelName("  CCTV1  "))
        assertEquals("CCTV13", LiveEpgParser.normalizeEpgChannelName("CCTV13新闻"))
    }

    @Test
    fun normalize_nonCctvKeepsTrimmedOriginal() {
        assertEquals("湖南卫视", LiveEpgParser.normalizeEpgChannelName(" 湖南卫视 "))
        assertEquals("", LiveEpgParser.normalizeEpgChannelName(null))
    }

    @Test
    fun firstPartBeforeSpace() {
        assertEquals("CCTV1", LiveEpgParser.getFirstPartBeforeSpace("CCTV1 综合"))
        assertEquals("CCTV1", LiveEpgParser.getFirstPartBeforeSpace("CCTV1"))
        assertNull(LiveEpgParser.getFirstPartBeforeSpace(null))
        assertEquals("", LiveEpgParser.getFirstPartBeforeSpace(""))
    }

    @Test
    fun cleanTitleStripsSourceWatermark() {
        assertEquals("新闻联播", LiveEpgParser.cleanEpgTitle("新闻联播 --免费使用"))
        assertEquals("新闻联播", LiveEpgParser.cleanEpgTitle("新闻联播--免费使用"))
        assertEquals("", LiveEpgParser.cleanEpgTitle(null))
    }

    @Test
    fun unavailableTextDetection() {
        assertTrue(LiveEpgParser.isUnavailableEpgText("暂无"))
        assertTrue(LiveEpgParser.isUnavailableEpgText("未提供节目单"))
        assertFalse(LiveEpgParser.isUnavailableEpgText("新闻联播"))
        assertFalse(LiveEpgParser.isUnavailableEpgText(null))
    }

    @Test
    fun jsonEpgDate_fullAndTimeOnly() {
        val day = gmt8("yyyy-MM-dd").parse("2026-09-15")!!
        val full = LiveEpgParser.parseJsonEpgDate(day, "2026-09-15 20:30:00")!!
        assertEquals("2026-09-15 20:30", gmt8("yyyy-MM-dd HH:mm").format(full))
        val timeOnly = LiveEpgParser.parseJsonEpgDate(day, "20:30")!!
        assertEquals("2026-09-15 20:30", gmt8("yyyy-MM-dd HH:mm").format(timeOnly))
        assertNull(LiveEpgParser.parseJsonEpgDate(day, ""))
        assertNull(LiveEpgParser.parseJsonEpgDate(day, null))
    }

    @Test
    fun xmlTvDate_bothFormatsAndNull() {
        assertNotNull(LiveEpgParser.parseXmlTvDate("20260915203000 +0800"))
        val plain = LiveEpgParser.parseXmlTvDate("20260915203000")!!
        assertEquals("2026-09-15 20:30", gmt8("yyyy-MM-dd HH:mm").format(plain))
        assertNull(LiveEpgParser.parseXmlTvDate("   "))
        assertNull(LiveEpgParser.parseXmlTvDate(null))
    }

    @Test
    fun dayStartIsMidnightGmt8() {
        val any = gmt8("yyyy-MM-dd HH:mm").parse("2026-09-15 20:30")!!
        assertEquals("2026-09-15 00:00", gmt8("yyyy-MM-dd HH:mm").format(LiveEpgParser.getDayStart(any)))
    }

    @Test
    fun xmlAddressAndResponseDetection() {
        assertTrue(LiveEpgParser.isXmlEpgAddress("http://x/epg.xml?ch=1"))
        assertFalse(LiveEpgParser.isXmlEpgAddress("http://x/epg.php"))
        assertFalse(LiveEpgParser.isXmlEpgAddress(null))
        assertTrue(LiveEpgParser.isXmlEpgResponse("  <tv></tv>"))
        assertTrue(LiveEpgParser.isXmlEpgResponse("<?xml version=\"1.0\"?>"))
        assertFalse(LiveEpgParser.isXmlEpgResponse("{\"epg_data\":[]}"))
    }

    @Test
    fun templateAddressDetection() {
        assertTrue(LiveEpgParser.isTemplateEpgAddress("http://x/{name}"))
        assertTrue(LiveEpgParser.isTemplateEpgAddress("http://x/{date}"))
        assertFalse(LiveEpgParser.isTemplateEpgAddress("http://x/epg?ch=1"))
        assertFalse(LiveEpgParser.isTemplateEpgAddress(null))
    }

    @Test
    fun buildUrl_queryXmlAndTemplate() {
        val date = gmt8("yyyy-MM-dd").parse("2026-09-15")!!
        val fmt = gmt8("yyyy-MM-dd")
        assertEquals(
            "http://x/epg?ch=CCTV1&date=2026-09-15",
            LiveEpgParser.buildEpgUrl("http://x/epg", "CCTV1", date, fmt),
        )
        assertEquals(
            "http://x/epg?a=1&ch=CCTV1&date=2026-09-15",
            LiveEpgParser.buildEpgUrl("http://x/epg?a=1", "CCTV1", date, fmt),
        )
        assertEquals(
            "http://x/2026-09-15/CCTV1",
            LiveEpgParser.buildEpgUrl("http://x/{date}/{name}", "CCTV1", date, fmt),
        )
        assertEquals("http://x/epg.xml", LiveEpgParser.buildEpgUrl("http://x/epg.xml", "CCTV1", date, fmt))
    }

    @Test
    fun queryNamesAreDedupedAndEndWithRawChannelName() {
        assertEquals(
            listOf("CCTV1", "CCTV1 综合"),
            LiveEpgParser.buildEpgQueryNames("CCTV1 综合", "CCTV1", "CCTV1"),
        )
        assertEquals(
            listOf("CCTV1综合", "CCTV1", "CCTV1 综合"),
            LiveEpgParser.buildEpgQueryNames("CCTV1 综合", "CCTV1", "CCTV1综合"),
        )
    }

    @Test
    fun encodeParamUsesPercent20() {
        assertEquals("CCTV1%20%E7%BB%BC%E5%90%88", LiveEpgParser.encodeEpgParam("CCTV1 综合"))
        assertEquals("", LiveEpgParser.encodeEpgParam(null))
    }

    @Test
    fun appendCatchupUrl_replaceAndSecondQuery() {
        assertEquals(
            "http://a/live.m3u8?playseek=1",
            LiveEpgParser.appendCatchupUrl("http://a/live.m3u8", "/PLTV/,/TVOD/", "?playseek=1"),
        )
        assertEquals(
            "http://a/TVOD/x.m3u8?t=1&playseek=2",
            LiveEpgParser.appendCatchupUrl("http://a/PLTV/x.m3u8?t=1", "/PLTV/,/TVOD/", "?playseek=2"),
        )
    }

    @Test
    fun catchupTokenExpansion() {
        val info = epg(1_700_000_000_000L, 1_700_000_360_000L)
        assertEquals("1700000000", LiveEpgParser.formatCatchupSource("{utc:}", info))
        assertEquals("1700000360", LiveEpgParser.formatCatchupSource("{utcend:}", info))
        assertEquals("1700000000", LiveEpgParser.formatCatchupSource("{(b)timestamp}", info))
        assertEquals("1700000360", LiveEpgParser.formatCatchupSource("{(e)timestamp}", info))
        assertEquals("", LiveEpgParser.formatCatchupSource("{unknown}", info))
        assertEquals("1700000000-1700000360", LiveEpgParser.formatCatchupSource("{utc:}-{utcend:}", info))
    }

    @Test
    fun formatCatchupUrl_defaultTypeAndReplaceType() {
        val info = epg(1_700_000_000_000L, 1_700_000_360_000L)
        val defaultType = JsonObject().apply {
            addProperty("source", "{utc:}")
            addProperty("type", "default")
        }
        assertEquals("1700000000", LiveEpgParser.formatCatchupUrl("http://a/live.m3u8", defaultType, info))

        val replaceType = JsonObject().apply {
            addProperty("source", "?playseek={utc:}")
            addProperty("replace", "/PLTV/,/TVOD/")
        }
        assertEquals(
            "http://a/TVOD/live.m3u8?playseek=1700000000",
            LiveEpgParser.formatCatchupUrl("http://a/PLTV/live.m3u8", replaceType, info),
        )
    }

    @Test
    fun catchupSourceDetection() {
        assertTrue(LiveEpgParser.hasCatchupSource(JsonObject().apply { addProperty("source", "x") }))
        assertFalse(LiveEpgParser.hasCatchupSource(JsonObject()))
        assertFalse(LiveEpgParser.hasCatchupSource(null))
        assertEquals("", LiveEpgParser.catchupValue(null, "source"))
        assertEquals("", LiveEpgParser.catchupValue(JsonObject().apply { addProperty("source", "") }, "source"))
    }

    @Test
    fun catchupDurationAndTimeFormatting() {
        val info = epg(1_700_000_000_000L, 1_700_000_360_000L)
        assertEquals(360, LiveEpgParser.catchupDurationSeconds(info))
        assertEquals(0, LiveEpgParser.catchupDurationSeconds(null))
        assertEquals("1700000000", LiveEpgParser.formatCatchupTime(Date(1_700_000_000_000L), "timestamp"))
    }

    @Test
    fun durationToString_formatsMs() {
        assertEquals("00:00", LiveEpgParser.durationToString(0))
        assertEquals("01:05", LiveEpgParser.durationToString(65_000))
        assertEquals("1:02:05", LiveEpgParser.durationToString(3_725_000))
        assertEquals("00:00", LiveEpgParser.durationToString(-1))
    }

    private val xmltv = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="cctv1.hd"><display-name>CCTV-1 综合</display-name></channel>
          <channel id="hunan"><display-name>湖南卫视</display-name></channel>
          <programme channel="cctv1.hd" start="20260915120000 +0800" stop="20260915130000 +0800">
            <title>新闻联播</title>
          </programme>
          <programme channel="cctv1.hd" start="20260915200000 +0800" stop="20260915210000 +0800">
            <title>焦点访谈 --免费使用</title>
          </programme>
          <programme channel="hunan" start="20260915140000 +0800" stop="20260915150000 +0800">
            <title>别的台</title>
          </programme>
          <programme channel="cctv1.hd" start="20260917090000 +0800" stop="20260917100000 +0800">
            <title>后天节目</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parseXmlEpg_matchesByDisplayNameAndFiltersWindow() {
        val day = gmt8("yyyy-MM-dd").parse("2026-09-15")!!
        val list = LiveEpgParser.parseXmlEpg(xmltv, "CCTV1", day)

        assertEquals(2, list.size)
        assertEquals("新闻联播", list[0].title)
        assertEquals(0, list[0].index)
        assertEquals("焦点访谈 --免费使用", list[1].title)
        assertEquals(1, list[1].index)
        assertEquals("2026-09-15 12:00", gmt8("yyyy-MM-dd HH:mm").format(list[0].startdateTime!!))
        assertEquals("2026-09-15 13:00", gmt8("yyyy-MM-dd HH:mm").format(list[0].enddateTime!!))
        assertEquals("2026-09-15 20:00", gmt8("yyyy-MM-dd HH:mm").format(list[1].startdateTime!!))
        assertEquals("2026-09-15 21:00", gmt8("yyyy-MM-dd HH:mm").format(list[1].enddateTime!!))
    }

    @Test
    fun parseXmlEpg_keepsTitleVerbatimUnlikeJsonPath() {
        val day = gmt8("yyyy-MM-dd").parse("2026-09-15")!!
        val list = LiveEpgParser.parseXmlEpg(xmltv, "CCTV1", day)
        assertEquals("焦点访谈 --免费使用", list[1].title)
        assertEquals("焦点访谈", LiveEpgParser.cleanEpgTitle(list[1].title))
    }

    @Test
    fun parseXmlEpg_unknownChannelYieldsEmpty() {
        val day = gmt8("yyyy-MM-dd").parse("2026-09-15")!!
        assertEquals(0, LiveEpgParser.parseXmlEpg(xmltv, "不存在的频道", day).size)
    }

    @Test
    fun parseXmlEpg_malformedXmlYieldsEmpty() {
        val day = gmt8("yyyy-MM-dd").parse("2026-09-15")!!
        assertEquals(0, LiveEpgParser.parseXmlEpg("<tv><channel", "CCTV1", day).size)
        assertEquals(0, LiveEpgParser.parseXmlEpg("", "CCTV1", day).size)
    }
}
