package com.github.avbox.osc.player.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFormatNameTest {

    @Test
    fun mapsMimeToShortName() {
        assertEquals("PGS", subtitleFormatName("application/pgs"))
        assertEquals("PGS", subtitleFormatName("APPLICATION/PGS"))
        assertEquals("PGS", subtitleFormatName("S_HDMV/PGS"))
        assertEquals("VobSub", subtitleFormatName("application/vobsub"))
        assertEquals("DVB", subtitleFormatName("application/dvbsubs"))
        assertEquals("ASS", subtitleFormatName("text/x-ssa"))
        assertEquals("SRT", subtitleFormatName("application/x-subrip"))
        assertEquals("WebVTT", subtitleFormatName("text/vtt"))
        assertEquals("TTML", subtitleFormatName("application/ttml+xml"))
        assertEquals("CEA-608", subtitleFormatName("application/cea-608"))
        assertEquals("CEA-708", subtitleFormatName("application/cea-708"))
    }

    @Test
    fun hidesCuesWrapperMime() {
        assertEquals("", subtitleFormatName("application/x-media3-cues"))
    }

    @Test
    fun keepsUnknownCodecId() {
        assertEquals("MP4A-LATM", subtitleFormatName("mp4a-latm"))
        assertEquals("X-CUSTOM", subtitleFormatName("application/x-custom"))
    }

    @Test
    fun blankValuesMapToEmpty() {
        assertEquals("", subtitleFormatName(null))
        assertEquals("", subtitleFormatName(""))
        assertEquals("", subtitleFormatName("   "))
    }
}
