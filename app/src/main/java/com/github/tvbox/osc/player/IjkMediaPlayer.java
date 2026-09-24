package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;

import androidx.media3.common.C;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.TrackMemory;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.KV;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.misc.IMediaFormat;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;
import xyz.doikki.videoplayer.ijk.IjkPlayer;

public class IjkMediaPlayer extends IjkPlayer {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    private IJKCode codec = null;
    protected String currentPlayPath;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/json;q=0.9";

    /** 本片记忆键(见 TrackMemory);内核重建即新实例,故由 MyVideoView 在起播前推入 */
    private String contentKey = "";

    public void setContentKey(String key) {
        this.contentKey = key == null ? "" : key;
    }

    public IjkMediaPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
    }

    /**
     * 更新解码配置(2026-09-15)。
     *
     * <p>解码 options 只在 reset/prepare 时由 {@link #setOptions()} 应用,而 {@code codec} 是构造时固化的;
     * 换集/换线/换源/自动换线走的是**复用内核**路径(PlayContainer.startVideoPlayback → VideoView.replay,
     * 不重建实例),只更新工厂的话新解码方式永远不会生效 —— 用户在设置里把硬解改成软解,继续换集仍是硬解。
     * 由 {@code PlayerHelper.updateCfg}(每次起播前的 applyPlayerConfigToView)把最新 codec 推给存活实例,
     * 紧接着的 reset 起播即按新解码方式走。codec 名不变时推的是同一缓存对象,行为零差异。
     */
    public void setCodec(IJKCode codec) {
        if (codec != null) this.codec = codec;
    }

    @Override
    public void setOptions() {
        super.setOptions();
        IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
        LinkedHashMap<String, String> options = codecTmp.getOption();
        if (options != null) {
            for (String key : options.keySet()) {
                String value = options.get(key);
                // assert 在 release 里是空操作,这里用真判空(null 值会走 Long.parseLong NPE 路径)
                if (value == null) continue;
                int category;
                String name;
                try {
                    // 解析必须自带兜底(2026-09-15):本方法在 VideoView.startPlay/startPrepare 链路上被调用,
                    // 且全链路无 try/catch —— 非法配置项(缺竖线 "mediacodec" / 尾部空段 "4|")抛出的
                    // NumberFormatException、ArrayIndexOutOfBoundsException 会在主线程直接崩掉进程
                    String[] opt = key.split("\\|");
                    category = Integer.parseInt(opt[0].trim());
                    name = opt[1].trim();
                } catch (Exception e) {
                    LOG.i("echo-ijk-option-skip:" + key);
                    continue;
                }
                try {
                    mMediaPlayer.setOption(category, name, Long.parseLong(value));
                } catch (Exception e) {
                    mMediaPlayer.setOption(category, name, value);
                }
            }
        }
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 30);

        // 设置视频流格式
//        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", tv.danmaku.ijk.media.player.IjkMediaPlayer.SDL_FCC_RV32);

        //开启内置字幕
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,"safe",0);

        if(KV.get(HawkConfig.PLAYER_IS_LIVE, false)){
            LOG.i("echo-type-直播");
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 300);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 1);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "1");
        }else{
            LOG.i("echo-type-点播");
            // 降低延迟
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 3000);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "2");
        }
//        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);//强制音画同步
    }

    private static final String ITV_TARGET_DOMAIN = "gslbserv.itv.cmvideo.cn";
    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            switch (getStreamType(path)) {
                case RTSP_UDP_RTP:
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp");
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1000);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2 * 1000 * 1000);
                    break;

                case CACHE_VIDEO:
                    if (KV.get(HawkConfig.IJK_CACHE_PLAY, false)) {
                        String cachePath = FileUtils.getCachePath() + "/ijkcaches/";
                        File cacheFile = new File(cachePath);
                        if (!cacheFile.exists()) cacheFile.mkdirs();
                        String tmpMd5 = MD5.string2MD5(path);
                        String cacheFilePath = cachePath + tmpMd5 + ".file";
                        String cacheMapPath = cachePath + tmpMd5 + ".map";

                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cacheFilePath);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cacheMapPath);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", 60 * 1024 * 1024);
                        path = "ijkio:cache:ffio:" + path;
                    }
                    break;

                case M3U8:
                    // 直播且是ijk的时候自动自动走代理解决DNS
                    if (KV.get(HawkConfig.PLAYER_IS_LIVE, false) ) {
                        URI uri = new URI(path);
                        String host = uri.getHost();
                        if(ITV_TARGET_DOMAIN.equalsIgnoreCase(host))path = ControlManager.get().getAddress(true) + "proxy?go=live&type=m3u8&url="+ URLEncoder.encode(path,"UTF-8");
                    }
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        setDataSourceHeader(headers);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        currentPlayPath = path;
        super.setDataSource(path, null);
    }

    /**
     * 解析 URL
     */
    private static final int RTSP_UDP_RTP = 1;
    private static final int CACHE_VIDEO = 2;
    private static final int M3U8 = 3;
    private static final int OTHER = 0;

    private int getStreamType(String path) {
        if (TextUtils.isEmpty(path)) {
            return OTHER;
        }
        // 低成本检查 RTSP/UDP/RTP 类型
        String lowerPath = path.toLowerCase();
        if (lowerPath.startsWith("rtsp://") || lowerPath.startsWith("udp://") || lowerPath.startsWith("rtp://")) {
            return RTSP_UDP_RTP;
        }
        String cleanUrl = path.split("\\?")[0];
        if (cleanUrl.endsWith(".m3u8")) {
            return M3U8;
        }
        if (cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".avi")) {
            return CACHE_VIDEO;
        }
        return OTHER;
    }

    private void setDataSourceHeader(Map<String, String> headers) {
        LinkedHashMap<String, String> playHeaders = new LinkedHashMap<>();
        String userAgent = null;
        boolean hasAccept = false;
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (ExoMediaSourceHelper.HEADER_FORMAT.equalsIgnoreCase(key)) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    if ("Accept".equalsIgnoreCase(key)) {
                        hasAccept = true;
                    }
                    playHeaders.put(key, value.trim());
                }
            }
        }
        if (TextUtils.isEmpty(userAgent)) {
            userAgent = DEFAULT_USER_AGENT;
        }
        if (!hasAccept) {
            playHeaders.put("Accept", DEFAULT_ACCEPT);
        }
        if (!TextUtils.isEmpty(userAgent)) {
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
        }
        if (playHeaders.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : playHeaders.entrySet()) {
                sb.append(entry.getKey());
                sb.append(": ");
                sb.append(entry.getValue());
                sb.append("\r\n");
            }
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
        }
    }

    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int videoSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                if (isAttachedPicture(info)) {
                    LOG.i("echo-ijk-skip-attached-picture:" + info.getInfoInline());
                    index++;
                    continue;
                }
                TrackInfoBean v = new TrackInfoBean();
                String name = processVideoName(info.getInfoInline());
                String language = getFriendlyLanguage(info.getLanguage(), info.getInfoInline());
                v.language = language;
                v.name = buildDisplayName(str(R.string.player_menu_video_track), data.getVideo().size() + 1, language, name);
                v.trackId = index;
                v.index = index;
                v.selected = index == videoSelected;
                v.type = C.TRACK_TYPE_VIDEO;
                v.formatKey = TrackMemory.videoFingerprint(name, 0, 0);
                data.addVideo(v);
            }
            else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {//音轨信息
                TrackInfoBean a = new TrackInfoBean();
                String name = processAudioName(info.getInfoInline());
                a.language = info.getLanguage();
                if(name.startsWith("aac"))a.language="中文"; // i18n: keep(音轨语言值,随后被 getFriendlyLanguage 转换显示)
                a.name = name;
                String language = getFriendlyLanguage(a.language, info.getInfoInline());
                a.language = language;
                a.name = buildDisplayName(str(R.string.player_menu_audio_track), data.getAudio().size() + 1, language, name);
                a.trackId = index;
                a.index = index;
                a.selected = index == audioSelected;
                a.type = C.TRACK_TYPE_AUDIO;
                // 没有归一化 Format:用 ffmpeg 轨描述串当指纹细节(同批片稳定,能区分同语言的 AAC/E-AC3)
                a.formatKey = TrackMemory.audioFingerprint(language, name, 0);
                // 如果需要，还可以检查轨道的描述或标题以获取更多信息
                data.addAudio(a);
            }
            else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {//内置字幕
                if (!isTextSubtitle(info.getInfoInline())) {
                    LOG.i("echo-ijk-skip-bitmap-subtitle:" + info.getInfoInline());
                    index++;
                    continue;
                }
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                String language = getFriendlyLanguage(t.language, t.name);
                t.language = language;
                t.name = buildDisplayName(str(R.string.player_menu_subtitle), data.getSubtitle().size() + 1, language, "");
                t.trackId = index;
                t.index = index;
                t.selected = index == subtitleSelected;
                t.type = C.TRACK_TYPE_TEXT;
                t.formatKey = TrackMemory.textFingerprint(language, info.getInfoInline());
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }
    // 处理音轨名称格式
    private String processAudioName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace("AUDIO,", "")
                .replace("N/A,", "")
                .replace(" ", "")
                .replaceAll("^,+|,+$", "")
                .replace(",", " / ");
    }

    private String processVideoName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace("VIDEO,", "")
                .replace("N/A,", "")
                .replace(" ", "")
                .replaceAll("^,+|,+$", "")
                .replace(",", " / ");
    }

    private boolean isAttachedPicture(IjkTrackInfo info) {
        IMediaFormat format = info.getFormat();
        if (format == null) return false;
        String codecName = format.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
        return "mjpeg".equalsIgnoreCase(codecName)
                && format.getInteger(IjkMediaMeta.IJKM_KEY_BITRATE) <= 0
                && format.getInteger(IjkMediaMeta.IJKM_KEY_FPS_NUM) <= 0;
    }

    private boolean isTextSubtitle(String rawName) {
        String value = rawName == null ? "" : rawName.toLowerCase();
        return !value.contains("pgs")
                && !value.contains("hdmv")
                && !value.contains("dvd subtitle")
                && !value.contains("dvd_subtitle")
                && !value.contains("dvb subtitle")
                && !value.contains("dvb_subtitle")
                && !value.contains("xsub")
                && !value.contains("vobsub")
                && !value.contains("bitmap");
    }

    private String getFriendlyLanguage(String language, String rawInfo) {
        String text = ((language == null ? "" : language) + " " + (rawInfo == null ? "" : rawInfo)).toLowerCase();
        if (text.contains("yue") || text.contains("cantonese") || text.contains("\u7ca4") || text.contains("\u5e7f\u4e1c")) {
            return "\u7ca4\u8bed";
        }
        if (text.contains("zh") || text.contains("chi") || text.contains("zho") || text.contains("chs")
                || text.contains("cht") || text.contains("cmn") || text.contains("\u4e2d")
                || text.contains("\u56fd\u8bed") || text.contains("\u666e\u901a\u8bdd")) {
            return "\u56fd\u8bed";
        }
        if (text.contains("en") || text.contains("eng") || text.contains("english") || text.contains("\u82f1")) {
            return "\u82f1\u8bed";
        }
        if (text.contains("ja") || text.contains("jpn") || text.contains("japanese") || text.contains("\u65e5")) {
            return "\u65e5\u8bed";
        }
        if (text.contains("ko") || text.contains("kor") || text.contains("korean") || text.contains("\u97e9")) {
            return "\u97e9\u8bed";
        }
        if (text.contains("tha") || text.contains("thai") || text.contains("th")) {
            return "\u6cf0\u8bed";
        }
        return "";
    }

    private String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(number);
        if (language != null && !language.isEmpty()) {
            builder.append(" - ").append(language);
        }
        if (detail != null && !detail.isEmpty()) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    /**
     * 用户显式选轨:改当前选择并记住**指纹**(下标换集即失效,存了必然选错轨)。
     * 挡位按轨道类型各取各的当前下标 —— 音轨与字幕下标相同时也得切得动(视轨与字幕共用过同一挡位)。
     */
    public void setTrack(TrackInfoBean track) {
        if (track == null) return;
        if (track.type == C.TRACK_TYPE_TEXT) {
            // 选内置字幕即重新决定字幕来源(覆盖 #off/#local/#online):先记后切,切换失败也保留意图
            TrackMemory.saveSubtitle(contentKey, track.formatKey);
        } else {
            TrackMemory.saveTrack(contentKey, track.type, track.formatKey);
        }
        selectTrack(track);
    }

    /** 程序性选轨(默认字幕等自动逻辑),不写记忆 */
    public void selectTrack(TrackInfoBean track) {
        if (track == null) return;
        selectTrack(track.index, track.type);
    }

    private void selectTrack(int trackIndex, int trackType) {
        try {
            int selected = mMediaPlayer.getSelectedTrack(ijkTrackType(trackType));
            if (trackIndex == selected) return;
            mMediaPlayer.selectTrack(trackIndex);
        } catch (Exception e) {
            LOG.i("echo-ijk-select-track-error:" + e.getMessage());
        }
    }

    /** media3 轨道类型 → IJK 轨道类型(内置字幕在 IJK 侧是 TIMEDTEXT) */
    private static int ijkTrackType(int trackType) {
        if (trackType == C.TRACK_TYPE_VIDEO) return ITrackInfo.MEDIA_TRACK_TYPE_VIDEO;
        if (trackType == C.TRACK_TYPE_AUDIO) return ITrackInfo.MEDIA_TRACK_TYPE_AUDIO;
        return ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT;
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

    /** 按记忆还原音轨/视轨/内置字幕;无记忆时音轨退默认(多条选第一条),内置字幕退"国语→第一条" */
    public void restoreTracks(TrackInfo trackInfo) {
        if (!restoreByMemory(trackInfo, C.TRACK_TYPE_AUDIO)
                && trackInfo != null && trackInfo.getAudio().size() > 1) {
            selectTrack(trackInfo.getAudio().get(0));
        }
        restoreByMemory(trackInfo, C.TRACK_TYPE_VIDEO);
        restoreSubtitleByMemory(trackInfo);
    }

    private boolean restoreByMemory(TrackInfo trackInfo, int trackType) {
        String remembered = TrackMemory.loadTrack(contentKey, trackType);
        if (remembered == null || trackInfo == null) return false;
        List<TrackInfoBean> list = tracksOf(trackInfo, trackType);
        List<String> keys = new ArrayList<>();
        for (TrackInfoBean bean : list) keys.add(bean.formatKey);
        int index = TrackMemory.pick(keys, remembered);
        if (index < 0) {
            LOG.i("echo-track-memory miss type=" + trackType + " key=" + contentKey + " fp=" + remembered);
            return false;
        }
        LOG.i("echo-track-memory restore type=" + trackType + " fp=" + remembered);
        selectTrack(list.get(index));
        return true;
    }

    /** 内置字幕按指纹还原;#off / #local / #online 三种来源决定由页面层落地,这里不动 */
    private void restoreSubtitleByMemory(TrackInfo trackInfo) {
        String record = TrackMemory.loadSubtitle(contentKey);
        if (record == null) {
            selectDefaultSubtitlePick(trackInfo);
            return;
        }
        if (!TrackMemory.isSubtitleTrack(record)) return;
        if (trackInfo != null) {
            List<TrackInfoBean> list = trackInfo.getSubtitle();
            List<String> keys = new ArrayList<>();
            for (TrackInfoBean bean : list) keys.add(bean.formatKey);
            int index = TrackMemory.pick(keys, record);
            if (index >= 0) {
                LOG.i("echo-track-memory restore text fp=" + record);
                selectTrack(list.get(index));
                return;
            }
        }
        // 有决定但这一集定位不到(编码变了/有歧义):退回默认选轨,别变成"什么都没有"
        LOG.i("echo-track-memory text miss, use default: " + record);
        selectDefaultSubtitlePick(trackInfo);
    }

    /** 当前没有选中任何内置字幕轨时补一次默认选轨(外挂字幕落地失败回落时全靠它 —— IJK 自己不会替我们选) */
    public void ensureSubtitleTrackSelected(TrackInfo trackInfo) {
        if (trackInfo == null) return;
        List<TrackInfoBean> subtitles = trackInfo.getSubtitle();
        if (subtitles.isEmpty()) return;
        for (TrackInfoBean subtitle : subtitles) {
            if (subtitle.selected) return;
        }
        selectDefaultSubtitlePick(trackInfo);
    }

    /** 默认内置字幕:国语优先,否则第一条 */
    private void selectDefaultSubtitlePick(TrackInfo trackInfo) {
        if (trackInfo == null) return;
        List<TrackInfoBean> subtitles = trackInfo.getSubtitle();
        if (subtitles.isEmpty()) return;
        for (TrackInfoBean bean : subtitles) {
            if ("国语".equals(bean.language)) { // i18n: keep(字幕语言匹配值)
                selectTrack(bean);
                return;
            }
        }
        selectTrack(subtitles.get(0));
    }

    private List<TrackInfoBean> tracksOf(TrackInfo trackInfo, int trackType) {
        if (trackType == C.TRACK_TYPE_AUDIO) return trackInfo.getAudio();
        if (trackType == C.TRACK_TYPE_VIDEO) return trackInfo.getVideo();
        return trackInfo.getSubtitle();
    }
}
