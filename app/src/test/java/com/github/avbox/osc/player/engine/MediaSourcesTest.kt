package com.github.avbox.osc.player.engine

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourcesTest {

    @Test
    fun formatHeader_winsOverFileName() {
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/a.mp4", mapOf("TVBox-Format" to "hls")))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/a.m3u8", mapOf("TVBox-Format" to "dash")))
    }

    @Test
    fun formatHeader_variants() {
        assertEquals(C.TYPE_HLS, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "application/vnd.apple.mpegurl")))
        assertEquals(C.TYPE_HLS, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "M3U8")))
        assertEquals(C.TYPE_DASH, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "application/dash+xml")))
        assertEquals(C.TYPE_DASH, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "mpd")))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "mp4")))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(null))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(mapOf("Other" to "hls")))
    }

    @Test
    fun inferContentType_byFileName() {
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/a.mpd", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?type=mpd", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?type=dash", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?format=mpd", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/play?format=dash", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/a.m3u8", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/play?type=hls", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/play?format=hls", null))
        assertEquals(C.TYPE_OTHER, MediaSources.inferContentType("http://x/a.mp4", null))
    }

    @Test
    fun inferContentType_isCaseInsensitive() {
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/A.M3U8", null))
        assertEquals(C.TYPE_DASH, MediaSources.inferContentType("http://x/A.MPD", null))
        assertEquals(C.TYPE_HLS, MediaSources.inferContentType("http://x/P?TYPE=HLS", null))
    }

    @Test
    fun audioUri_detectedBySuffix() {
        assertTrue(MediaSources.isAudioUri("http://x/song.mp3"))
        assertTrue(MediaSources.isAudioUri("http://x/song.M4A"))
        assertTrue(MediaSources.isAudioUri("http://x/song.flac"))
        assertTrue(MediaSources.isAudioUri("http://x/song.opus"))
        assertFalse(MediaSources.isAudioUri("http://x/movie.mp4"))
        assertFalse(MediaSources.isAudioUri("http://x/a.m3u8"))
    }

    @Test
    fun hlsUri_audioSuffixExcludedEvenWithKeyword() {
        assertFalse(MediaSources.isHlsUri("http://x/live.m3u8.mp3"))
        assertTrue(MediaSources.isHlsUri("http://x/live.m3u8"))
    }

    @Test
    fun hlsUri_keywordVariants() {
        assertTrue(MediaSources.isHlsUri("http://x/play?type=hls"))
        assertTrue(MediaSources.isHlsUri("http://x/play?format=hls"))
        assertFalse(MediaSources.isHlsUri("http://x/a.mp4"))
    }

    @Test
    fun mimeTypeOf_onlyHlsAndDashGetMime() {
        assertEquals("application/x-mpegURL", MediaSources.mimeTypeOf(C.TYPE_HLS))
        assertEquals("application/dash+xml", MediaSources.mimeTypeOf(C.TYPE_DASH))
        assertEquals(null, MediaSources.mimeTypeOf(C.TYPE_OTHER))
    }

    @Test
    fun toRequestHeaders_filtersFormatHeaderAndTrims() {
        val out = MediaSources.toRequestHeaders(
            mapOf(
                "Referer" to " http://ref ",
                "TVBox-Format" to "hls",
                "tvbox-format" to "dash",
                "User-Agent" to "ua",
            ),
        )
        assertEquals("http://ref", out["Referer"])
        assertEquals("ua", out["User-Agent"])
        assertFalse(out.containsKey("TVBox-Format"))
        assertFalse(out.containsKey("tvbox-format"))
    }

    @Test
    fun toRequestHeaders_nullInput() {
        assertTrue(MediaSources.toRequestHeaders(null).isEmpty())
    }

    @Test
    fun toRequestHeaders_trimOnlyAsciiControlChars() {
        val keepNbsp = MediaSources.toRequestHeaders(mapOf("K" to "v\u00A0"))
        assertEquals("v\u00A0", keepNbsp["K"])
        val control = MediaSources.toRequestHeaders(mapOf("K" to "\tv\u0001"))
        assertEquals("v", control["K"])
    }

    @Test
    fun headerKeySuffix_sortedAndOrderIndependent() {
        val a = MediaSources.headerKeySuffix(linkedMapOf("User-Agent" to "u", "Referer" to "r"))
        val b = MediaSources.headerKeySuffix(linkedMapOf("Referer" to "r", "User-Agent" to "u"))
        assertEquals("\nReferer:r;\nUser-Agent:u;", a)
        assertEquals(a, b)
    }

    @Test
    fun headerKeySuffix_trimsValues() {
        assertEquals(
            MediaSources.headerKeySuffix(mapOf("K" to "v")),
            MediaSources.headerKeySuffix(mapOf("K" to " v ")),
        )
    }

    @Test
    fun headerKeySuffix_empty() {
        assertEquals("", MediaSources.headerKeySuffix(null))
        assertEquals("", MediaSources.headerKeySuffix(emptyMap()))
    }

    @Test
    fun isoFormat_matchesOnlyIsoMimes() {
        assertTrue(MediaSources.isIsoFormat("video/x-iso"))
        assertTrue(MediaSources.isIsoFormat("application/x-iso9660-image"))
        assertTrue(MediaSources.isIsoFormat("iso"))
        assertTrue(MediaSources.isIsoFormat(" X-ISO "))
        assertFalse(MediaSources.isIsoFormat("video/mp4"))
        assertFalse(MediaSources.isIsoFormat("m3u8"))
        assertFalse(MediaSources.isIsoFormat(""))
        assertFalse(MediaSources.isIsoFormat(null))
    }

    @Test
    fun isoUri_directSuffix() {
        assertTrue(MediaSources.isIsoUri("http://cdn.example.com/a.iso"))
        assertTrue(MediaSources.isIsoUri("http://cdn.example.com/a.ISO?token=1"))
        assertFalse(MediaSources.isIsoUri("http://cdn.example.com/a.m3u8"))
        assertFalse(MediaSources.isIsoUri("http://cdn.example.com/play?format=iso"))
        assertFalse(MediaSources.isIsoUri("data:application/octet-stream;base64,AAAA"))
        assertFalse(MediaSources.isIsoUri(null))
    }

    @Test
    fun isoUri_netdiskProxyTarget() {
        assertTrue(
            MediaSources.isIsoUri(
                "http://127.0.0.1:5266?url=http%3A%2F%2Fcdn.example.com%2Fdisc.iso&thread=16&header=%7B%7D",
            ),
        )
        assertTrue(
            MediaSources.isIsoUri(
                "http://127.0.0.1:5266?thread=16&url=http%3A%2F%2Fcdn.example.com%2Fdisc.iso%3Ftoken%3D1",
            ),
        )
        assertFalse(
            MediaSources.isIsoUri(
                "http://127.0.0.1:5266?url=http%3A%2F%2Fcdn.example.com%2Findex.m3u8&thread=16",
            ),
        )
    }

    @Test
    fun isoHeader_detectedAndStripped() {
        val headers = mapOf("Referer" to "http://ref", "TVBox-Iso" to "1")
        assertTrue(MediaSources.isoHeaders(headers))
        assertTrue(MediaSources.isoHeaders(mapOf("tvbox-iso" to "1")))
        assertFalse(MediaSources.isoHeaders(mapOf("Referer" to "http://ref")))
        assertFalse(MediaSources.isoHeaders(emptyMap()))
        assertFalse(MediaSources.isoHeaders(null))
        assertFalse(MediaSources.toRequestHeaders(headers).containsKey("TVBox-Iso"))
    }

    @Test
    fun isoHeader_doesNotChangeCacheKey() {
        val plain = MediaSources.headerKeySuffix(mapOf("Referer" to "http://ref"))
        val marked = MediaSources.headerKeySuffix(mapOf("Referer" to "http://ref", "TVBox-Iso" to "1"))
        assertEquals("\nReferer:http://ref;", plain)
        assertEquals(plain, marked)
    }

    @Test
    fun withIsoHeader_copiesAndMarks() {
        val source = hashMapOf("Referer" to "http://ref")
        val marked = MediaSources.withIsoHeader(source)
        assertEquals("1", marked["TVBox-Iso"])
        assertEquals("http://ref", marked["Referer"])
        assertFalse(source.containsKey("TVBox-Iso"))
        assertEquals("1", MediaSources.withIsoHeader(null)["TVBox-Iso"])
    }

    @Test
    fun inferFormatContentType_isoMatches() {
        assertEquals(MediaSources.TYPE_ISO, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "video/x-iso")))
        assertEquals(MediaSources.TYPE_ISO, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "x-iso")))
        assertEquals(MediaSources.TYPE_ISO, MediaSources.inferFormatContentType(mapOf("TVBox-Iso" to "1")))
        assertEquals(MediaSources.TYPE_ISO, MediaSources.inferContentType("http://x/a.iso", mapOf("TVBox-Iso" to "1")))
        assertEquals(C.TYPE_OTHER, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "video/mp4")))
        assertEquals(C.TYPE_HLS, MediaSources.inferFormatContentType(mapOf("TVBox-Format" to "hls")))
    }
}
