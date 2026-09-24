package com.github.tvbox.osc.util;

import androidx.media3.common.C;

import java.util.List;

/**
 * 轨道与字幕来源的记忆。
 *
 * <p>两条不变量:① **键 = 源 + 片**(含集号/线路就换集读不到);② 值 = 轨道**指纹**,
 * 还原时在**当前**轨道里重新定位 —— `(渲染器,组,轨)` 下标只在一次起播的映射里有效,存了就是错的。
 *
 * <p>槽位取值(字幕槽是"来源决定",四者互斥):
 * <pre>
 * A/&lt;语言&gt;/&lt;编码&gt;/&lt;声道&gt;      音轨指纹
 * V/&lt;编码&gt;/&lt;宽&gt;x&lt;高&gt;         视轨指纹
 * T/&lt;语言&gt;/&lt;编码&gt;            内置字幕指纹
 * #off                      关闭字幕
 * #local:&lt;路径&gt;             本地字幕文件(路径在缓存目录,被系统清掉即按失效回落)
 * #online:&lt;发布页&gt;|&lt;文件名&gt;   在线字幕(VOD 直链只对当集有效,入库的是发布页)
 * </pre>
 *
 * <p>无状态工具类。动态键无法逐键登记 KVKeySpec,调用侧一律带具体默认值({@code ""})。
 */
public final class TrackMemory {

    private static final int TYPE_AUDIO = C.TRACK_TYPE_AUDIO;
    private static final int TYPE_VIDEO = C.TRACK_TYPE_VIDEO;
    private static final int TYPE_TEXT = C.TRACK_TYPE_TEXT;

    private static final String KEY_PREFIX = "track_mem_";
    /** 内容键里"源"与"片"的分隔符(sourceKey/id 由源站给出,不含 '@') */
    private static final String CONTENT_SEP = "@";
    private static final String SLOT_AUDIO = "audio";
    private static final String SLOT_VIDEO = "video";
    private static final String SLOT_TEXT = "text";

    /** 字幕槽位里以它开头即"不是轨道指纹",而是来源决定 */
    private static final String MARK = "#";
    public static final String SUBTITLE_OFF = "#off";
    private static final String SUBTITLE_LOCAL = "#local:";
    private static final String SUBTITLE_ONLINE = "#online:";
    /** 发布页与文件名的分隔符(URL/文件名里的 '|' 不可能出现) */
    private static final String ONLINE_SEP = "|";

    private TrackMemory() {
    }

    // ==================== 内容键 ====================

    /** 记忆的作用域键:同一部片的所有集、所有线路共用一把;直播/无剧集信息返回空串(调用侧据此跳过读写) */
    public static String contentKey(String sourceKey, String vodId) {
        String source = trim(sourceKey);
        String id = trim(vodId);
        if (source.isEmpty() || id.isEmpty()) return "";
        return source + CONTENT_SEP + id;
    }

    // ==================== 指纹 ====================

    /** 音轨指纹:语言 + 编码 + 声道。三者缺一就分不开"同语言不同编码"(国语 AAC 与国语 E-AC3) */
    public static String audioFingerprint(String language, String codec, int channels) {
        return "A" + "/" + field(language) + "/" + fieldCodec(codec) + "/" + (channels > 0 ? String.valueOf(channels) : "");
    }

    /** 视轨指纹:编码 + 分辨率。 */
    public static String videoFingerprint(String codec, int width, int height) {
        String size = (width > 0 && height > 0) ? width + "x" + height : "";
        return "V" + "/" + fieldCodec(codec) + "/" + size;
    }

    /** 内置字幕指纹:语言 + 编码。 */
    public static String textFingerprint(String language, String codec) {
        return "T" + "/" + field(language) + "/" + fieldCodec(codec);
    }

    /** 指纹是否"能定位":全字段为空(如 IJK 读不到轨信息)会退化成"匹配第一条"而静默选错轨,故既不写库也不用它还原 */
    public static boolean usable(String fingerprint) {
        if (fingerprint == null) return false;
        String[] parts = fingerprint.split("/", -1);
        if (parts.length < 2) return false;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) return true;
        }
        return false;
    }

    /**
     * 在当前轨道指纹列表里定位记忆中的那条。
     *
     * <p>精确匹配优先;失败后退化为按**第 2 个字段**(音频/字幕 = 语言,视轨 = 编码)匹配,
     * 且该字段在候选里必须唯一;有歧义时返回 -1 交给播放器默认选轨(猜哪条都可能错)。
     *
     * @return 下标,-1 = 无法定位
     */
    public static int pick(List<String> available, String remembered) {
        if (available == null || available.isEmpty() || !usable(remembered)) return -1;
        for (int i = 0; i < available.size(); i++) {
            if (remembered.equals(available.get(i))) return i;
        }
        String language = fieldAt(remembered, 1);
        if (language.isEmpty()) return -1;
        int matched = -1;
        for (int i = 0; i < available.size(); i++) {
            if (!language.equals(fieldAt(available.get(i), 1))) continue;
            if (matched >= 0) return -1;
            matched = i;
        }
        return matched;
    }

    // ==================== 字幕来源记录 ====================

    public static String subtitleLocal(String path) {
        String value = trim(path);
        return value.isEmpty() ? "" : SUBTITLE_LOCAL + value;
    }

    public static String subtitleOnline(String releaseUrl, String fileName) {
        String release = trim(releaseUrl);
        if (release.isEmpty()) return "";
        return SUBTITLE_ONLINE + release + ONLINE_SEP + trim(fileName);
    }

    public static boolean isSubtitleOff(String record) {
        return SUBTITLE_OFF.equals(record);
    }

    public static boolean isSubtitleLocal(String record) {
        return record != null && record.startsWith(SUBTITLE_LOCAL);
    }

    public static boolean isSubtitleOnline(String record) {
        return record != null && record.startsWith(SUBTITLE_ONLINE);
    }

    /** 是否为"内置字幕指纹"(字幕槽位里唯一以轨道指纹形式存的取值) */
    public static boolean isSubtitleTrack(String record) {
        return record != null && !record.isEmpty() && !record.startsWith(MARK);
    }

    /** 本地字幕文件路径;非本地记录返回空串 */
    public static String localPath(String record) {
        return isSubtitleLocal(record) ? record.substring(SUBTITLE_LOCAL.length()) : "";
    }

    /** 在线字幕的发布页地址(不是直链);非在线记录返回空串 */
    public static String onlineRelease(String record) {
        if (!isSubtitleOnline(record)) return "";
        String rest = record.substring(SUBTITLE_ONLINE.length());
        int sep = rest.indexOf(ONLINE_SEP);
        return sep < 0 ? rest : rest.substring(0, sep);
    }

    /** 在线字幕当时所选文件的名称(跨集匹配用的线索);可为空串 */
    public static String onlineFileName(String record) {
        if (!isSubtitleOnline(record)) return "";
        String rest = record.substring(SUBTITLE_ONLINE.length());
        int sep = rest.indexOf(ONLINE_SEP);
        return sep < 0 ? "" : rest.substring(sep + ONLINE_SEP.length());
    }

    // ==================== 读写 ====================

    /** 记住用户显式选择的音轨/视轨。空键、不可定位的指纹、字幕槽位一律跳过。 */
    public static void saveTrack(String contentKey, int type, String fingerprint) {
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return;
        if (isEmpty(contentKey) || !usable(fingerprint)) return;
        KV.put(slotKey(contentKey, type), fingerprint);
        LOG.i("echo-track-memory save " + slot(type) + "=" + fingerprint);
    }

    /**
     * 记住用户显式的字幕决定(内置指纹 / #off / #local / #online)。
     *
     * <p>不可定位的指纹(如 IJK 读不到轨信息时的 `T//`)不能记:它会被当成"这个片有决定"而让默认选轨
     * 整条让位,这一集就什么字幕都没有 —— 宁可不记。
     */
    public static void saveSubtitle(String contentKey, String record) {
        if (isEmpty(contentKey) || isEmpty(record)) return;
        if (isSubtitleTrack(record) && !usable(record)) {
            LOG.i("echo-track-memory skip unusable text=" + record);
            return;
        }
        KV.put(slotKey(contentKey, TYPE_TEXT), record);
        LOG.i("echo-track-memory save text=" + record);
    }

    /** 读出音轨/视轨记忆;无记忆返回 null(调用侧据此保留播放器的自动选轨) */
    public static String loadTrack(String contentKey, int type) {
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return null; // 字幕槽存的是来源决定,误读会被当指纹解析
        if (isEmpty(contentKey)) return null;
        String value = KV.get(slotKey(contentKey, type), "");
        return usable(value) ? value : null;
    }

    /** 读出字幕来源决定;无记忆返回 null */
    public static String loadSubtitle(String contentKey) {
        if (isEmpty(contentKey)) return null;
        String value = KV.get(slotKey(contentKey, TYPE_TEXT), "");
        return isEmpty(value) ? null : value;
    }

    public static void delete(String contentKey) {
        if (isEmpty(contentKey)) return;
        KV.delete(slotKey(contentKey, TYPE_AUDIO));
        KV.delete(slotKey(contentKey, TYPE_VIDEO));
        KV.delete(slotKey(contentKey, TYPE_TEXT));
    }

    private static String slotKey(String contentKey, int type) {
        return KEY_PREFIX + contentKey + "_" + slot(type);
    }

    private static String slot(int type) {
        if (type == TYPE_AUDIO) return SLOT_AUDIO;
        if (type == TYPE_VIDEO) return SLOT_VIDEO;
        return SLOT_TEXT;
    }

    // ==================== 小工具 ====================

    private static boolean isEmpty(String text) {
        return text == null || text.isEmpty();
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    /** 指纹字段:去掉字段分隔符,避免一个字段被拆成两个(字段值只作匹配用,不需要原样保留) */
    private static String field(String text) {
        return trim(text).replace("/", " ");
    }

    private static String fieldCodec(String codec) {
        return field(codec).toLowerCase(java.util.Locale.ROOT);
    }

    /** 取指纹的第 index 个字段(0 = 类型前缀);越界返回空串 */
    private static String fieldAt(String fingerprint, int index) {
        if (fingerprint == null) return "";
        String[] parts = fingerprint.split("/", -1);
        return index >= 0 && index < parts.length ? parts[index] : "";
    }
}
