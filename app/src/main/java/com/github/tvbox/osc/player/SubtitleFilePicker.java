package com.github.tvbox.osc.player;

import java.util.List;
import java.util.Locale;

/**
 * 在线字幕"同一发布页里挑本集文件"的匹配规则(由 SubtitleFilePickerTest 锁行为)。
 *
 * <p>发布页通常是一整部剧的压缩包,里面按集号命名文件。换集既不能沿用上一集的文件(时间轴不对),
 * 也不能乱猜(错字幕比没字幕更糟)。按下面顺序取,任何一步不唯一就返回 -1:
 * <ol>
 *   <li>集号:当前集号能提取且同集号只有一条 ⇒ 用它(换集最常走这步);</li>
 *   <li>变体:同集号有多条(简繁/字幕组)时取"变体"与所选相同的 —— 变体 = 名字里数字串换成 `#`,
 *       于是 E01 的 chs 能对应到 E02 的 chs;</li>
 *   <li>同名:集号提取不出来时退化为与所选文件名完全相同;</li>
 *   <li>单文件:只有一个文件且它的集号提不出或与当前集一致(整季合一)。</li>
 * </ol>
 * ⚠️ 同名匹配**不能**放到集号之前:上一集所选的文件名会原样命中,等于把上一集的字幕套到这一集。
 */
public final class SubtitleFilePicker {

    private SubtitleFilePicker() {
    }

    /**
     * @param fileNames    发布页里的文件名列表(顺序即列表顺序)
     * @param episodeName  当前集名(提取集号用)
     * @param fileNameHint 用户当时所选的文件名(可为空)
     * @return 命中项下标,-1 = 无法确定
     */
    public static int pick(List<String> fileNames, String episodeName, String fileNameHint) {
        if (fileNames == null || fileNames.isEmpty()) return -1;
        int episode = EpisodeMatcher.extractEpisodeNumber(episodeName);

        if (episode >= 0) {
            int only = -1;
            int sameEpisode = 0;
            for (int i = 0; i < fileNames.size(); i++) {
                if (EpisodeMatcher.extractEpisodeNumber(fileNames.get(i)) != episode) continue;
                only = i;
                sameEpisode++;
            }
            if (sameEpisode == 1) return only;
            if (sameEpisode > 1) return pickSameVariant(fileNames, episode, fileNameHint);
        }

        String hint = normalize(fileNameHint);
        if (!hint.isEmpty()) {
            int only = -1;
            int sameName = 0;
            for (int i = 0; i < fileNames.size(); i++) {
                if (!normalize(fileNames.get(i)).equals(hint)) continue;
                only = i;
                sameName++;
            }
            if (sameName == 1) return only;
        }

        if (fileNames.size() == 1) {
            int fileEpisode = EpisodeMatcher.extractEpisodeNumber(fileNames.get(0));
            if (fileEpisode < 0 || episode < 0 || fileEpisode == episode) return 0;
        }
        return -1;
    }

    /** 同集多条:取变体与所选一致的那条;仍不唯一则不猜 */
    private static int pickSameVariant(List<String> fileNames, int episode, String fileNameHint) {
        String hintVariant = variant(fileNameHint);
        if (hintVariant.isEmpty()) return -1;
        int only = -1;
        int matched = 0;
        for (int i = 0; i < fileNames.size(); i++) {
            if (EpisodeMatcher.extractEpisodeNumber(fileNames.get(i)) != episode) continue;
            if (!variant(fileNames.get(i)).equals(hintVariant)) continue;
            only = i;
            matched++;
        }
        return matched == 1 ? only : -1;
    }

    /** 去扩展名 + 统一大小写:同一文件在列表里可能带 .ass 也可能带 .srt,hint 与实际项要对得上 */
    private static String normalize(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    /** 变体 = 归一化后把所有数字串替换成 '#':抹掉集号只留"同一版本/语言"的特征 */
    private static String variant(String text) {
        String value = normalize(text);
        return value.isEmpty() ? "" : value.replaceAll("\\d+", "#");
    }
}
