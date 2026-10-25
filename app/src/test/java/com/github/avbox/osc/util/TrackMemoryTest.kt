package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMemoryTest {

    @Test
    fun contentKey_isSourcePlusVodId() {
        assertEquals("csp_abc@12345", TrackMemory.contentKey("csp_abc", "12345"))
    }

    @Test
    fun contentKey_emptyWhenMissingPart() {
        assertEquals("", TrackMemory.contentKey(null, "1"))
        assertEquals("", TrackMemory.contentKey("src", ""))
        assertEquals("", TrackMemory.contentKey("  ", "  "))
    }

    @Test
    fun fingerprint_distinguishesCodecAndChannel() {
        val aac = TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2)
        val eac3 = TrackMemory.audioFingerprint("国语", "ec-3", 6)
        assertFalse(aac == eac3)
        assertEquals("A/国语/mp4a.40.2/2", aac)
    }

    @Test
    fun fingerprint_codecIsCaseInsensitive() {
        assertEquals(TrackMemory.audioFingerprint("国语", "AAC", 2), TrackMemory.audioFingerprint("国语", "aac", 2))
    }

    @Test
    fun usable_rejectsDegenerateFingerprint() {
        assertFalse(TrackMemory.usable(TrackMemory.audioFingerprint("", "", 0)))
        assertFalse(TrackMemory.usable(TrackMemory.videoFingerprint("", 0, 0)))
        assertFalse(TrackMemory.usable(TrackMemory.textFingerprint("", null, null, "")))
        assertFalse(TrackMemory.usable(null))
        assertFalse(TrackMemory.usable("A"))
        assertTrue(TrackMemory.usable(TrackMemory.audioFingerprint("", "aac", 0)))
        assertTrue(TrackMemory.usable(TrackMemory.videoFingerprint("h264", 1920, 1080)))
        assertTrue(TrackMemory.usable(TrackMemory.textFingerprint("国语", null, null, "")))
    }

    @Test
    fun pick_exactMatchWins() {
        val remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6)
        val available = listOf(
            TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
            remembered,
            TrackMemory.audioFingerprint("粤语", "mp4a.40.2", 2),
        )
        assertEquals(1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_languageFallbackWhenCodecChanged() {
        val remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6)
        val available = listOf(
            TrackMemory.audioFingerprint("英语", "mp4a.40.2", 2),
            TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
        )
        assertEquals(1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_languageAmbiguousReturnsMiss() {
        val remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6)
        val available = listOf(
            TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
            TrackMemory.audioFingerprint("国语", "ec-3", 2),
        )
        assertEquals(-1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_missWhenNothingMatches() {
        val remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6)
        val available = listOf(TrackMemory.audioFingerprint("英语", "mp4a.40.2", 2))
        assertEquals(-1, TrackMemory.pick(available, remembered))
        assertEquals(-1, TrackMemory.pick(available, null))
        assertEquals(-1, TrackMemory.pick(emptyList<String>(), remembered))
    }

    @Test
    fun pick_videoByResolution() {
        val remembered = TrackMemory.videoFingerprint("h264", 1920, 1080)
        val available = listOf(
            TrackMemory.videoFingerprint("h264", 1280, 720),
            TrackMemory.videoFingerprint("h264", 1920, 1080),
        )
        assertEquals(1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_textByLanguage() {
        val remembered = TrackMemory.textFingerprint("英语", null, null, "srt")
        val available = listOf(
            TrackMemory.textFingerprint("国语", null, null, "srt"),
            TrackMemory.textFingerprint("英语", null, null, "srt"),
        )
        assertEquals(1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun textFingerprint_carriesIdAndLabel() {
        assertEquals("T/国语/3/主字幕/srt", TrackMemory.textFingerprint("国语", "3", "主字幕", "srt"))
    }

    @Test
    fun pick_textByIdDistinguishesSameLanguageAndCodec() {
        val remembered = TrackMemory.textFingerprint("", "5", null, "x-quicktime-tx3g")
        val available = listOf(
            TrackMemory.textFingerprint("", "1", null, "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("", "2", null, "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("", "5", null, "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("", "8", null, "x-quicktime-tx3g"),
        )
        assertEquals(2, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_textByLabelWhenIdMissing() {
        val remembered = TrackMemory.textFingerprint("", null, "评论音轨", "x-quicktime-tx3g")
        val available = listOf(
            TrackMemory.textFingerprint("", null, "正片", "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("", null, "评论音轨", "x-quicktime-tx3g"),
        )
        assertEquals(1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_textWithoutIdKeepsFirstMatch() {
        val remembered = TrackMemory.textFingerprint("", null, null, "x-quicktime-tx3g")
        val available = listOf(
            TrackMemory.textFingerprint("", null, null, "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("", null, null, "x-quicktime-tx3g"),
        )
        assertEquals(0, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_textIdMissFallsBackByUniqueLanguage() {
        val remembered = TrackMemory.textFingerprint("国语", "9", null, "x-quicktime-tx3g")
        val available = listOf(
            TrackMemory.textFingerprint("英语", "2", null, "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("国语", "3", null, "x-quicktime-tx3g"),
        )
        assertEquals(1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_textIdMissWithAmbiguousLanguageReturnsMiss() {
        val remembered = TrackMemory.textFingerprint("国语", "9", null, "srt")
        val available = listOf(
            TrackMemory.textFingerprint("国语", "1", null, "srt"),
            TrackMemory.textFingerprint("国语", "2", null, "srt"),
        )
        assertEquals(-1, TrackMemory.pick(available, remembered))
    }

    @Test
    fun pick_textLegacyRecordFallsBackByLanguage() {
        val legacy = "T/国语/srt"
        val available = listOf(
            TrackMemory.textFingerprint("英语", "1", null, "srt"),
            TrackMemory.textFingerprint("国语", "2", null, "srt"),
        )
        assertEquals(1, TrackMemory.pick(available, legacy))
    }

    @Test
    fun pick_textLegacyRecordWithoutLanguageStaysMiss() {
        val legacy = "T//x-quicktime-tx3g"
        val available = listOf(
            TrackMemory.textFingerprint("", "1", null, "x-quicktime-tx3g"),
            TrackMemory.textFingerprint("", "2", null, "x-quicktime-tx3g"),
        )
        assertEquals(-1, TrackMemory.pick(available, legacy))

        val ambiguous = listOf(
            TrackMemory.textFingerprint("国语", "1", null, "srt"),
            TrackMemory.textFingerprint("国语", "2", null, "srt"),
        )
        assertEquals(-1, TrackMemory.pick(ambiguous, "T/国语/srt"))
    }

    @Test
    fun subtitleSource_roundTrip() {
        val local = TrackMemory.subtitleLocal("/data/user/0/app/cache/subtitle_1_a.srt")
        assertTrue(TrackMemory.isSubtitleLocal(local))
        assertEquals("/data/user/0/app/cache/subtitle_1_a.srt", TrackMemory.localPath(local))
        assertTrue(local.contains("/data/user/0/"))

        val online = TrackMemory.subtitleOnline("https://assrt.net/sub/12345", "Show.S01E02.ass")
        assertTrue(TrackMemory.isSubtitleOnline(online))
        assertEquals("https://assrt.net/sub/12345", TrackMemory.onlineRelease(online))
        assertEquals("Show.S01E02.ass", TrackMemory.onlineFileName(online))
    }

    @Test
    fun subtitleSource_offAndTrackAreDistinguished() {
        assertTrue(TrackMemory.isSubtitleOff(TrackMemory.SUBTITLE_OFF))
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.SUBTITLE_OFF))
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.subtitleLocal("/tmp/a.srt")))
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.subtitleOnline("https://a/b", "c.ass")))
        val builtin = TrackMemory.textFingerprint("国语", null, null, "srt")
        assertTrue(TrackMemory.isSubtitleTrack(builtin))
    }

    @Test
    fun subtitleSource_emptyInputsRejected() {
        assertEquals("", TrackMemory.subtitleLocal(null))
        assertEquals("", TrackMemory.subtitleLocal("   "))
        assertEquals("", TrackMemory.subtitleOnline("", "a.ass"))
        assertFalse(TrackMemory.isSubtitleOnline(null))
        assertEquals("", TrackMemory.onlineRelease(TrackMemory.SUBTITLE_OFF))
        assertEquals("", TrackMemory.onlineFileName(TrackMemory.SUBTITLE_OFF))
    }

    @Test
    fun subtitleSource_onlineWithoutFileName() {
        val online = TrackMemory.subtitleOnline("https://assrt.net/sub/1", "")
        assertEquals("https://assrt.net/sub/1", TrackMemory.onlineRelease(online))
        assertEquals("", TrackMemory.onlineFileName(online))
    }
}
