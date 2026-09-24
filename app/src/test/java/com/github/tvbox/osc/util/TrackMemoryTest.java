package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 锁住轨道记忆的编码与匹配口径(纯函数,不碰 KV)。
 *
 * <p>这个类的全部风险都在"跨集匹配错轨":指纹编得不对会匹配不上(换个集就失效),
 * 匹配得太松会静默选错轨。故两个方向都要有用例。
 */
public class TrackMemoryTest {

    // ==================== 内容键 ====================

    @Test
    public void contentKey_isSourcePlusVodId() {
        assertEquals("csp_abc@12345", TrackMemory.contentKey("csp_abc", "12345"));
    }

    @Test
    public void contentKey_emptyWhenMissingPart() {
        // 直播/无剧集信息:不得生成键(否则写出 track_mem_null_* 垃圾)
        assertEquals("", TrackMemory.contentKey(null, "1"));
        assertEquals("", TrackMemory.contentKey("src", ""));
        assertEquals("", TrackMemory.contentKey("  ", "  "));
    }

    // ==================== 指纹 ====================

    @Test
    public void fingerprint_distinguishesCodecAndChannel() {
        String aac = TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2);
        String eac3 = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        assertFalse(aac.equals(eac3));
        // 语言+编码+声道三者都在
        assertEquals("A/国语/mp4a.40.2/2", aac);
    }

    @Test
    public void fingerprint_codecIsCaseInsensitive() {
        assertEquals(TrackMemory.audioFingerprint("国语", "AAC", 2), TrackMemory.audioFingerprint("国语", "aac", 2));
    }

    @Test
    public void usable_rejectsDegenerateFingerprint() {
        // 全字段为空 ⇒ 匹配第一条,会静默选错轨:既不能写库也不能用于还原
        assertFalse(TrackMemory.usable(TrackMemory.audioFingerprint("", "", 0)));
        assertFalse(TrackMemory.usable(TrackMemory.videoFingerprint("", 0, 0)));
        assertFalse(TrackMemory.usable(TrackMemory.textFingerprint("", "")));
        assertFalse(TrackMemory.usable(null));
        assertFalse(TrackMemory.usable("A"));
        assertTrue(TrackMemory.usable(TrackMemory.audioFingerprint("", "aac", 0)));
        assertTrue(TrackMemory.usable(TrackMemory.videoFingerprint("h264", 1920, 1080)));
        assertTrue(TrackMemory.usable(TrackMemory.textFingerprint("国语", "")));
    }

    // ==================== 匹配 ====================

    @Test
    public void pick_exactMatchWins() {
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
                remembered,
                TrackMemory.audioFingerprint("粤语", "mp4a.40.2", 2));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_languageFallbackWhenCodecChanged() {
        // 源站换了编码但语言没变,且同语言只此一条 ⇒ 认它(否则用户的选择换个集就失效)
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.audioFingerprint("英语", "mp4a.40.2", 2),
                TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_languageAmbiguousReturnsMiss() {
        // 同语言两条(国语 AAC + 国语 E-AC3):猜哪条都可能错,交给默认选轨
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
                TrackMemory.audioFingerprint("国语", "ec-3", 2));
        assertEquals(-1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_missWhenNothingMatches() {
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Collections.singletonList(
                TrackMemory.audioFingerprint("英语", "mp4a.40.2", 2));
        assertEquals(-1, TrackMemory.pick(available, remembered));
        assertEquals(-1, TrackMemory.pick(available, null));
        assertEquals(-1, TrackMemory.pick(Collections.<String>emptyList(), remembered));
    }

    @Test
    public void pick_videoByResolution() {
        String remembered = TrackMemory.videoFingerprint("h264", 1920, 1080);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.videoFingerprint("h264", 1280, 720),
                TrackMemory.videoFingerprint("h264", 1920, 1080));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_textByLanguage() {
        String remembered = TrackMemory.textFingerprint("英语", "srt");
        java.util.List<String> available = Arrays.asList(
                TrackMemory.textFingerprint("国语", "srt"),
                TrackMemory.textFingerprint("英语", "srt"));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    // ==================== 字幕来源记录 ====================

    @Test
    public void subtitleSource_roundTrip() {
        String local = TrackMemory.subtitleLocal("/data/user/0/app/cache/subtitle_1_a.srt");
        assertTrue(TrackMemory.isSubtitleLocal(local));
        assertEquals("/data/user/0/app/cache/subtitle_1_a.srt", TrackMemory.localPath(local));
        // 本地路径里的分隔符不被改写(路径是记录的最后一段)
        assertTrue(local.contains("/data/user/0/"));

        String online = TrackMemory.subtitleOnline("https://assrt.net/sub/12345", "Show.S01E02.ass");
        assertTrue(TrackMemory.isSubtitleOnline(online));
        assertEquals("https://assrt.net/sub/12345", TrackMemory.onlineRelease(online));
        assertEquals("Show.S01E02.ass", TrackMemory.onlineFileName(online));
    }

    @Test
    public void subtitleSource_offAndTrackAreDistinguished() {
        assertTrue(TrackMemory.isSubtitleOff(TrackMemory.SUBTITLE_OFF));
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.SUBTITLE_OFF));
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.subtitleLocal("/tmp/a.srt")));
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.subtitleOnline("https://a/b", "c.ass")));
        // 内置字幕指纹:唯一以轨道指纹形式存的字幕取值
        String builtin = TrackMemory.textFingerprint("国语", "srt");
        assertTrue(TrackMemory.isSubtitleTrack(builtin));
    }

    @Test
    public void subtitleSource_emptyInputsRejected() {
        assertEquals("", TrackMemory.subtitleLocal(null));
        assertEquals("", TrackMemory.subtitleLocal("   "));
        assertEquals("", TrackMemory.subtitleOnline("", "a.ass"));
        assertFalse(TrackMemory.isSubtitleOnline(null));
        assertEquals("", TrackMemory.onlineRelease(TrackMemory.SUBTITLE_OFF));
        assertEquals("", TrackMemory.onlineFileName(TrackMemory.SUBTITLE_OFF));
    }

    @Test
    public void subtitleSource_onlineWithoutFileName() {
        String online = TrackMemory.subtitleOnline("https://assrt.net/sub/1", "");
        assertEquals("https://assrt.net/sub/1", TrackMemory.onlineRelease(online));
        assertEquals("", TrackMemory.onlineFileName(online));
    }
}
