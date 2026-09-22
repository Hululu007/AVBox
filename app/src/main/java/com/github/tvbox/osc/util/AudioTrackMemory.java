package com.github.tvbox.osc.util;

import android.text.TextUtils;

/**
 * 音轨记忆(2026-09-15 由独立 SharedPreferences `audio_track_prefs` 迁入全局 KV/MMKV)。
 *
 * <p>键按剧动态生成(见 {@link #KEY_PREFIX}),值恒为 int;动态键无法逐键登记 KVKeySpec,
 * 但调用侧一律带具体默认值(-1),KV 按默认值类型还原即可,无需登记。
 *
 * <p>无状态工具类:迁移后不再需要 Context 与单例(原 SharedPreferences 实例的持有者已删除)。
 *
 * <p>EXO 侧额外记**渲染器下标**:同一部片的两条音轨可能分属两路音频渲染器,而 (组,轨) 只在**所属渲染器内**才有意义 ——
 * 按"第一个音频渲染器"还原会把记在扩展渲染器上的选择套到 MediaCodec 渲染器上,静默选中另一条轨。
 */
public final class AudioTrackMemory {

    /** 动态键族前缀:替代旧 SP 文件名,避免与其它 KV 键撞名 */
    private static final String KEY_PREFIX = "audio_track_";
    private static final String KEY_GROUP_SUFFIX = "_group";
    private static final String KEY_TRACK_SUFFIX = "_track";
    /** EXO 渲染器下标后缀;旧记忆无此键,读出 -1,调用侧回落第一个音频渲染器 */
    private static final String KEY_RENDERER_SUFFIX = "_renderer";

    private AudioTrackMemory() {
    }

    /** EXO 音轨记忆三元组;{@code rendererIndex < 0} = 旧版记忆(只存了组/轨,渲染器未知) */
    public static final class ExoTrack {
        public final int rendererIndex;
        public final int groupIndex;
        public final int trackIndex;

        ExoTrack(int rendererIndex, int groupIndex, int trackIndex) {
            this.rendererIndex = rendererIndex;
            this.groupIndex = groupIndex;
            this.trackIndex = trackIndex;
        }
    }

    /**
     * 记住本次选择的 EXO 音轨。
     *
     * @param rendererIndex 该轨所属渲染器下标(必须一并记住,见类注释)
     */
    public static void save(String playKey, int rendererIndex, int groupIndex, int trackIndex) {
        if (TextUtils.isEmpty(playKey)) return; // 无进度键(直播/无剧集信息):不写,避免生成 audio_track_null_* 垃圾键
        LOG.i("echo-AudioTrackMemory save playKey:" + playKey);
        String key = KEY_PREFIX + playKey + "_exo";
        KV.put(key + KEY_RENDERER_SUFFIX, rendererIndex);
        KV.put(key + KEY_GROUP_SUFFIX, groupIndex);
        KV.put(key + KEY_TRACK_SUFFIX, trackIndex);
    }

    public static void save(String playKey, int trackIndex) {
        if (TextUtils.isEmpty(playKey)) return;
        LOG.i("echo-AudioTrackMemory save playKey:" + playKey);
        KV.put(KEY_PREFIX + playKey + "_ijk" + KEY_TRACK_SUFFIX, trackIndex);
    }

    /** 读取 EXO 音轨记忆;无记忆(或键为空)返回 null */
    public static ExoTrack exoLoad(String playKey) {
        if (TextUtils.isEmpty(playKey)) return null;
        String key = KEY_PREFIX + playKey + "_exo";
        int group = KV.get(key + KEY_GROUP_SUFFIX, -1);
        int track = KV.get(key + KEY_TRACK_SUFFIX, -1);
        if (group >= 0 && track >= 0) {
            return new ExoTrack(KV.get(key + KEY_RENDERER_SUFFIX, -1), group, track);
        }
        return null;
    }

    public static Integer ijkLoad(String playKey) {
        if (TextUtils.isEmpty(playKey)) return -1;
        return KV.get(KEY_PREFIX + playKey + "_ijk" + KEY_TRACK_SUFFIX, -1);
    }
}
