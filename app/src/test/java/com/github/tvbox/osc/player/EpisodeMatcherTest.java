package com.github.tvbox.osc.player;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * EpisodeMatcher 单测:锁住匹配算法行为(换线选集/同名集号匹配)。
 */
public class EpisodeMatcherTest {

    private static VodInfo.VodSeries series(String name) {
        return new VodInfo.VodSeries(name, "http://example/" + name);
    }

    private static List<VodInfo.VodSeries> seriesList(String... names) {
        List<VodInfo.VodSeries> list = new ArrayList<>();
        for (String name : names) list.add(series(name));
        return list;
    }

    private static VodInfo vod(List<String> flagNames, String... mapFlags) {
        VodInfo vod = new VodInfo();
        vod.seriesFlags = new ArrayList<>();
        for (String flag : flagNames) vod.seriesFlags.add(new VodInfo.VodSeriesFlag(flag));
        vod.seriesMap = new LinkedHashMap<>();
        for (String flag : mapFlags) vod.seriesMap.put(flag, seriesList("第1集"));
        return vod;
    }

    // ---------- extractEpisodeNumber ----------

    @Test
    public void episodeNumber_fromCommonNameForms() {
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("第12集"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("第 12 集"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("EP12"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("E12"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("12"));
    }

    @Test
    public void episodeNumber_ignoresYearBracketsAndQuality() {
        assertEquals(5, EpisodeMatcher.extractEpisodeNumber("[1080P] 第5集"));
        assertEquals(8, EpisodeMatcher.extractEpisodeNumber("影片名 (2024) 第08集"));
        assertEquals(1000, EpisodeMatcher.extractEpisodeNumber("名侦探柯南 2024 第1000集"));
        assertEquals(2, EpisodeMatcher.extractEpisodeNumber("S01E02"));
    }

    @Test
    public void episodeNumber_withoutNumber_isMinusOne() {
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber(null));
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber(""));
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber("抢先版"));
        // 既有行为:全角括号不在剔除规则内,"1080p" 被剔掉后无数字可提取
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber("【测试】1080P"));
    }

    // ---------- episodeMatchScore ----------

    @Test
    public void matchScore_exactNameWins() {
        assertEquals(100, EpisodeMatcher.episodeMatchScore("第3集", 3, "第3集"));
        assertEquals(100, EpisodeMatcher.episodeMatchScore("Final", -1, "final"));
    }

    @Test
    public void matchScore_sameEpisodeNumber() {
        assertEquals(80, EpisodeMatcher.episodeMatchScore("第3集", 3, "第3话"));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("第3集", 3, "第5集"));
    }

    @Test
    public void matchScore_containsNameOnlyWhenNoEpisodeNumber() {
        assertEquals(70, EpisodeMatcher.episodeMatchScore("正片", -1, "正片 上"));
        assertEquals(60, EpisodeMatcher.episodeMatchScore("正片 上", -1, "正片"));
        // 单字不参与包含匹配
        assertEquals(0, EpisodeMatcher.episodeMatchScore("正", -1, "正片"));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("正片", -1, "正"));
    }

    @Test
    public void matchScore_emptyInputs() {
        assertEquals(0, EpisodeMatcher.episodeMatchScore(null, -1, "x"));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("x", -1, null));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("", 3, ""));
    }

    // ---------- sameEpisodeIndex ----------

    @Test
    public void sameEpisodeIndex_matchesByEpisodeNumber() {
        List<VodInfo.VodSeries> targets = seriesList("第1集", "第3集", "第5集");
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(series("第3集"), targets, 0));
    }

    @Test
    public void sameEpisodeIndex_tieKeepsFirstCandidate() {
        // 两个候选都是"同集号"(80 分):取先出现的那个
        List<VodInfo.VodSeries> targets = seriesList("第3话", "EP3");
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), targets, 0));
    }

    @Test
    public void sameEpisodeIndex_exactNameBeatsEarlierEpisodeNumber() {
        // 精确同名(100 分)必须压过"先出现的同集号"(80 分):否则会选中 0
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(series("第3集"), seriesList("EP3", "第3集"), 0));
    }

    @Test
    public void sameEpisodeIndex_containsMatchWhenNoEpisodeNumber() {
        // 无集号时走"包含关系"(70 分):该分支失效就会落到 fallback=1
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("正片"), seriesList("正片 上", "其他"), 1));
    }

    @Test
    public void sameEpisodeIndex_noMatch_fallsBackAndClamps() {
        List<VodInfo.VodSeries> targets = seriesList("第1集", "第2集");
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(series("第99集"), targets, 5));
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第99集"), targets, -3));
    }

    @Test
    public void sameEpisodeIndex_degenerateInputs() {
        List<VodInfo.VodSeries> targets = seriesList("第1集", "第2集", "第3集");
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), null, 0));
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), seriesList(), 0));
        // 只有一条的线路直接返回 0(不再做集名匹配)
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), seriesList("第9集"), 0));
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(null, targets, 1));
        assertEquals(2, EpisodeMatcher.sameEpisodeIndex(new VodInfo.VodSeries(), targets, 9));
    }

    // ---------- lineFlagsInDisplayOrder / lineFlagIndex ----------

    @Test
    public void lineFlags_seriesFlagsFirstThenRemainingMapKeys() {
        VodInfo vod = vod(Arrays.asList("线路A", "", "线路B", "线路A"), "线路B", "线路A", "线路C", "");
        assertEquals(Arrays.asList("线路A", "线路B", "线路C"), EpisodeMatcher.lineFlagsInDisplayOrder(vod));
    }

    @Test
    public void lineFlags_nullVodOrMap_isEmpty() {
        assertEquals(0, EpisodeMatcher.lineFlagsInDisplayOrder(null).size());
        assertEquals(0, EpisodeMatcher.lineFlagsInDisplayOrder(new VodInfo()).size());
    }

    @Test
    public void lineFlagIndex_foundAndMissing() {
        List<String> flags = Arrays.asList("线路A", "线路B");
        assertEquals(1, EpisodeMatcher.lineFlagIndex(flags, "线路B"));
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(flags, "线路C"));
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(null, "线路A"));
        // 传 null 走的是本地 isEmpty 分支:若换回 android.text.TextUtils,此处会 NPE(单测里它静默返回 false)
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(flags, null));
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(flags, ""));
    }
}
