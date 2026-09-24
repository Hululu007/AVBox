package com.github.tvbox.osc.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 锁住"同一发布页里挑本集文件"的口径。核心风险是**静默套错集**:宁可返回 -1 走默认链路,
 * 也不能把上一集的文件继续用在下一集上。
 */
public class SubtitleFilePickerTest {

    private static final String EP1 = "Show.S01E01.1080p.ass";
    private static final String EP2 = "Show.S01E02.1080p.ass";
    private static final String EP3 = "Show.S01E03.1080p.ass";

    @Test
    public void picksSameFileWhenUserPickedItBefore() {
        // 用户重看同一集 / 发布页只有一个文件且就是它
        assertEquals(1, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2, EP3), "第2集", EP2));
    }

    @Test
    public void picksByEpisodeNumberAcrossEpisodes() {
        // 换集:按集号找本集文件(这是"记住在线字幕选择"真正要干的事)
        assertEquals(2, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2, EP3), "第3集", EP1));
        assertEquals(0, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2, EP3), "第1集", EP3));
    }

    @Test
    public void episodeNumberFormatsAreTolerated() {
        java.util.List<String> files = Arrays.asList("Show.S01E01.ass", "Show.S01E02.ass");
        assertEquals(1, SubtitleFilePicker.pick(files, "02", "Show.S01E01.ass"));
        assertEquals(1, SubtitleFilePicker.pick(files, "第2集", ""));
        assertEquals(1, SubtitleFilePicker.pick(files, "EP02", ""));
    }

    @Test
    public void sameEpisodeMultipleVariantsPicksMatchingVariant() {
        // 同集号两条(简中/繁中):取"变体"与用户当时所选一致的那条 —— E01 的 chs 对应到 E02 的 chs
        java.util.List<String> files = Arrays.asList("Show.S01E02.chs.ass", "Show.S01E02.cht.ass");
        assertEquals(0, SubtitleFilePicker.pick(files, "第2集", "Show.S01E01.chs.ass"));
        assertEquals(1, SubtitleFilePicker.pick(files, "第2集", "Show.S01E01.cht.ass"));
    }

    @Test
    public void sameEpisodeSameVariantReturnsMiss() {
        // 同集号且变体也相同(如两个分辨率版本):无从判断,不猜
        java.util.List<String> files = Arrays.asList("Show.S01E02.1080p.ass", "Show.S01E02.720p.ass");
        assertEquals(-1, SubtitleFilePicker.pick(files, "第2集", "Show.S01E01.1080p.ass"));
    }

    @Test
    public void sameEpisodeDuplicatedNameReturnsMiss() {
        java.util.List<String> files = Arrays.asList("Show.E02.ass", "Show.E02.ass");
        assertEquals(-1, SubtitleFilePicker.pick(files, "第2集", ""));
    }

    @Test
    public void singleFileReleaseIsWholeSeason() {
        // 整季合一的字幕:只有一个文件、集名又提不出集号 ⇒ 用它(跨过了集号与同名两步)
        java.util.List<String> files = Collections.singletonList("Show.S01.全集.ass");
        assertEquals(0, SubtitleFilePicker.pick(files, "正片", ""));
    }

    @Test
    public void singleFileWithOtherEpisodeNumberReturnsMiss() {
        // 只有 E01 的文件却要看 E03:套上去就是错集字幕,宁可不要
        java.util.List<String> files = Collections.singletonList(EP1);
        assertEquals(-1, SubtitleFilePicker.pick(files, "第3集", ""));
    }

    @Test
    public void noEpisodeNumberInSeriesNameReturnsMiss() {
        // 集名提不出集号(如"正片/预告"):多文件时无从判断
        assertEquals(-1, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2), "正片", ""));
    }

    @Test
    public void emptyInputsReturnMiss() {
        assertEquals(-1, SubtitleFilePicker.pick(Collections.<String>emptyList(), "第1集", EP1));
        assertEquals(-1, SubtitleFilePicker.pick(null, "第1集", EP1));
    }

    @Test
    public void extensionDifferenceIsTolerated() {
        // 集名提不出集号 ⇒ 只能走同名比较:此时 .srt 与 .ass 必须视为同一个文件(去扩展名比较)
        assertEquals(0, SubtitleFilePicker.pick(Collections.singletonList("Show.S01E01.ass"), "正片", "Show.S01E01.srt"));
        // 名字不同则不该被这套容错蒙混过去(多文件,免得落到"单文件整季"那条规则上)
        assertEquals(-1, SubtitleFilePicker.pick(Arrays.asList("Show.S01E01.ass", "Show.S01E02.ass"), "正片", "Other.S01E01.srt"));
    }
}
