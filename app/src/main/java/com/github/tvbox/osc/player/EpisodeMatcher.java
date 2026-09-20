package com.github.tvbox.osc.player;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.LOG;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 线路与剧集匹配算法(由 EpisodeMatcherTest 锁行为)。
 *
 * <p>坑:不得改用 android.text.TextUtils —— 单测 unitTests.isReturnDefaultValues=true 会让它静默返 false,
 * 空判断走错分支(换回去时 lineFlagIndex(flags, null) 立刻 NPE)。
 */
final class EpisodeMatcher {

    private EpisodeMatcher() {
    }

    private static boolean isEmpty(String text) {
        return text == null || text.length() == 0;
    }

    static List<String> lineFlagsInDisplayOrder(VodInfo vod) {
        List<String> lineFlags = new ArrayList<>();
        if (vod == null || vod.seriesMap == null) {
            return lineFlags;
        }
        if (vod.seriesFlags != null) {
            for (VodInfo.VodSeriesFlag flag : vod.seriesFlags) {
                if (flag != null && !isEmpty(flag.name) && vod.seriesMap.containsKey(flag.name) && !lineFlags.contains(flag.name)) {
                    lineFlags.add(flag.name);
                }
            }
        }
        for (String flag : vod.seriesMap.keySet()) {
            if (!isEmpty(flag) && !lineFlags.contains(flag)) {
                lineFlags.add(flag);
            }
        }
        return lineFlags;
    }

    static int lineFlagIndex(List<String> lineFlags, String currentFlag) {
        if (lineFlags == null || isEmpty(currentFlag)) {
            return -1;
        }
        for (int i = 0; i < lineFlags.size(); i++) {
            if (currentFlag.equals(lineFlags.get(i))) {
                return i;
            }
        }
        return -1;
    }

    static int sameEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList, int fallbackIndex) {
        if (targetList == null || targetList.isEmpty()) {
            return 0;
        }
        if (targetList.size() == 1) {
            return 0;
        }
        if (currentSeries == null || isEmpty(currentSeries.name)) {
            return Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
        }
        int currentEpisode = extractEpisodeNumber(currentSeries.name);
        int matchedIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < targetList.size(); i++) {
            VodInfo.VodSeries targetSeries = targetList.get(i);
            int score = episodeMatchScore(currentSeries.name, currentEpisode, targetSeries == null ? null : targetSeries.name);
            if (score > bestScore) {
                bestScore = score;
                matchedIndex = i;
            }
        }
        if (matchedIndex >= 0) {
            return matchedIndex;
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
    }

    static int episodeMatchScore(String currentName, int currentEpisode, String targetName) {
        if (isEmpty(currentName) || isEmpty(targetName)) {
            return 0;
        }
        if (targetName.equalsIgnoreCase(currentName)) {
            return 100;
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80;
        }
        String currentLower = currentName.toLowerCase(Locale.ROOT);
        String targetLower = targetName.toLowerCase(Locale.ROOT);
        if (currentEpisode < 0 && currentName.length() >= 2 && targetLower.contains(currentLower)) {
            return 70;
        }
        if (currentEpisode < 0 && targetName.length() >= 2 && currentLower.contains(targetLower)) {
            return 60;
        }
        return 0;
    }

    static int extractEpisodeNumber(String name) {
        if (isEmpty(name)) {
            return -1;
        }
        try {
            String text = name.replaceAll("\\[.*?\\]|\\(.*?\\)", "");
            text = text.replaceAll("\\b(19|20)\\d{2}\\b", "");
            text = text.toLowerCase(Locale.ROOT).replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");
            Matcher matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            String number = text.replaceAll("\\D+", "");
            if (!isEmpty(number)) {
                return Integer.parseInt(number);
            }
        } catch (Exception ignored) {
            LOG.d("EpisodeMatcher", "episode number extract failed, name=" + name);
        }
        return -1;
    }
}
