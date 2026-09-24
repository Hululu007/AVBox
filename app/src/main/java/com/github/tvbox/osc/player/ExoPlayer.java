package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.Looper;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.TrackMemory;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.PlayerHelper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;

import java.util.ArrayList;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

public class ExoPlayer extends ExoMediaPlayer {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    private volatile long internalSubtitleDelayUs;
    private OnCuesListener onCuesListener;
    private boolean defaultSubtitleTrackSelected;
    private boolean defaultSubtitleTrackSelectionClosed;
    /** 渲染器工厂创建的视频渲染器(用于关闭帧率匹配,见 disableFrameRateMatching) */
    private final ArrayList<Renderer> capturedVideoRenderers = new ArrayList<>();
    /** 点播磁盘缓存标记(第二期「边播边缓存」,由 MyVideoView 注入;直播页恒 false) */
    private boolean useDiskCache;

    /** 本片记忆键(见 TrackMemory);内核重建即新实例,故由 MyVideoView 在起播前推入 */
    private String contentKey = "";

    public void setContentKey(String key) {
        this.contentKey = key == null ? "" : key;
    }

    /**
     * EXO 解码方式(硬解/软解)的进程级下发位(2026-09-17)。
     *
     * <p>为什么是静态位而不是实例字段:选择器实例活在**视频渲染器**里,而渲染器随播放器实例创建;
     * 换集/换线走复用路径不重建渲染器(与 IJK 的 codec 固化同一类问题)。选择器在**查询时**读本静态位,
     * 于是只要解码器是新建的,就会用上最新值 —— 无须重建播放器。
     *
     * <p>⚠️ 但 media3 会在格式兼容时**跨 period 复用同一 MediaCodec**(renderer disable 只 flush 不 release,
     * 复用评估见 MediaCodecVideoRenderer.canReuseCodec),此时选择器不会再被查询 —— 光改静态位,
     * 换集仍然沿用旧解码器。故 {@code PlayerHelper.updateCfg} 在检测到值变化且当前活着 EXO 内核时,
     * 会给 VideoView 打"必须重建内核"标记(MyVideoView.requireKernelRebuild),起播处据此走非复用路径。
     *
     * <p>写入点只有一个:{@code PlayerHelper.updateCfg}(每次起播前由 applyPlayerConfigToView 调用),
     * 推的是"本剧配置 exo 键 → 缺省回落全局 EXO_DECODE"的有效值。
     */
    private static volatile boolean preferSoftwareDecode = false;

    /** 下发 EXO 解码方式:true = 软解(系统软件解码器优先) */
    public static void setPreferSoftwareDecode(boolean prefer) {
        preferSoftwareDecode = prefer;
    }

    /** 当前已下发的 EXO 解码方式(供 PlayerHelper 判断"这次起的解码方式变了没") */
    public static boolean isPreferSoftwareDecode() {
        return preferSoftwareDecode;
    }

    /**
     * 视频渲染器专用解码选择器:软解 = softwareOnly 解码器(c2.android.*)优先,硬解 = media3 默认顺序。
     *
     * <p>两点刻意的取舍:
     * ① 只注入视频渲染器(见 SubtitleOffsetRenderersFactory.buildVideoRenderers)—— 音频保持
     *    MediaCodec 优先 + ffmpeg 兜底(MODE_ON),不因"视频软解"顺带降级音频解码;
     * ② PREFER_SOFTWARE 是**排序**不是过滤:设备没有该编码的软件解码器时自动回落硬解,
     *    不会因软解不可用而起播失败。
     */
    private static final MediaCodecSelector EXO_VIDEO_CODEC_SELECTOR =
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                    (preferSoftwareDecode ? MediaCodecSelector.PREFER_SOFTWARE : MediaCodecSelector.DEFAULT)
                            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

    public ExoPlayer(Context context) {
        super(context);
        // 缓冲倍数(2026-09-12,照搬 fongmi ExoUtil.buildLoadControl):
        // 蓄水目标 min/max = 官方默认 50s × 用户倍数;起播(2.5s)/再缓冲(5s)阈值保持默认不乘,
        // 保证大缓冲只影响"播起来后攒多少水"而不拖慢起播;下次新建播放器实例时生效
        int bufferTimes = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT);
        bufferTimes = Math.max(1, Math.min(10, bufferTimes));
        setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * bufferTimes,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * bufferTimes,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build());
        setRenderersFactory(buildRenderersFactory(context));
        LOG.i("echo-exo-low-memory-load-control");
    }

    @Override
    public void initPlayer() {
        // 预载对齐(2026-09-12):预载开关开启时,播放器与 DefaultPreloadManager 共享同一播放线程,
        // 满足 PreloadMediaSource 的 looper 硬校验(见 PreloadManagerHolder.preloadLooper);
        // 开关关闭时不注入,播放线程保持 media3 默认(创建线程),与历史行为完全一致
        if (PreloadManagerHolder.enabled()) {
            setPlaybackLooper(PreloadManagerHolder.preloadLooper());
        }
        super.initPlayer();
        applyPlaybackParameters();
        disableFrameRateMatching();
        mInternalPlayer.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                loadDefaultSubtitleTrackBeforeReady();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    defaultSubtitleTrackSelectionClosed = true;
                }
            }

            @Override
            public void onCues(CueGroup cueGroup) {
                OnCuesListener listener = onCuesListener;
                if (listener != null) {
                    listener.onCues(cueGroup.cues);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // 播放错误详情(错误码 + cause 链,排查播放失败用)
                StringBuilder sb = new StringBuilder("echo-exo-player-error: code=")
                        .append(error.getErrorCodeName())
                        .append(", msg=").append(error.getMessage());
                Throwable cause = error.getCause();
                for (int i = 0; cause != null && i < 5; i++) {
                    sb.append(" | cause[").append(i).append("]=")
                            .append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                    cause = cause.getCause();
                }
                LOG.i(sb.toString());
            }
        });
        LOG.i("echo-exo-cues-listener-ready");
    }

    /**
     * 隧道模式(MediaCodec tunneled playback)与 AAC 优先(2026-09-11,对齐 fongmi 实现):
     * 隧道 = DefaultTrackSelector.Parameters.setTunnelingEnabled —— 视频/音频经硬件 AV 同步直通渲染
     * (MediaFormat.KEY_TUNNELED_PLAYBACK),并非音频 offload(offload 路径在本机被系统
     * getPlaybackOffloadSupport=0 挡死,永远不生效);隧道要求视频直出 Surface,TextureView 走 GPU 合成
     * 不可隧道,故非_SurfaceView_渲染时不启用(设置层双向联动见 SettingsPage,fongmi 同款)。
     * 仅 EXO 内核会走到本类,内核被自动切换为 IJK(含自动重试/rtmp 强制)后本类不再实例化 = 自动降级;
     * 设备 codec 不支持 FEATURE_TunneledPlayback 时 media3 静默回退普通渲染,无副作用。
     * 参数在播放器创建时读取,设置改动于下次播放生效。
     */
    private void applyPlaybackParameters() {
        if (mInternalPlayer == null || trackSelector == null) return;
        boolean tunnel = KV.get(HawkConfig.PLAY_TUNNEL, false);
        boolean preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false);
        boolean surfaceRender = KV.get(HawkConfig.PLAY_RENDER, 1) == 1;
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        builder.setTunnelingEnabled(tunnel && surfaceRender);
        if (preferAac) {
            builder.setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC);
        }
        trackSelector.setParameters(builder.build());
        LOG.i("echo-exo-tunnel-prefs: tunnel=" + tunnel + ", surfaceRender=" + surfaceRender + ", preferAac=" + preferAac);
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        defaultSubtitleTrackSelected = false;
        defaultSubtitleTrackSelectionClosed = false;
        super.setDataSource(path, headers);
        // 磁盘缓存数据源(第二期,见 spec §10/§11):
        // ① 预载过的下一集 → 必走:读预载写盘数据,内存交接 miss 时免网络冷启动;
        // ② 普通点播(MyVideoView 点播标记 + 设置「边播边缓存」) → 边播边缓存,回拖/重看/弱网读盘命中;
        // 直播页未打点播标记恒不走;未命中部分照常走网络
        boolean preloadTarget = PreloadManagerHolder.isPreloadTargetUrl(path);
        boolean playCache = useDiskCache && KV.get(HawkConfig.PLAY_CACHE, false);
        // 本地代理 URL 跳过磁盘缓存(2026-09-13):CacheDataSource 与 App 内代理(网盘 spider 自建/
        // M3U8 净化/DASH)的区间读取语义不兼容 —— 实测夸克 4K mp4 源需跳读文件尾 moov 时抛
        // ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE 致 EXO 无法起播(关掉边播缓存即可正常播放)。
        if (PlayerHelper.isLocalProxyUrl(path)) {
            if (preloadTarget || playCache) {
                LOG.i("echo-play-cache-skip-local-proxy: " + path);
            }
            preloadTarget = false;
            playCache = false;
        }
        if (preloadTarget || playCache) {
            MediaSource cached = mMediaSourceHelper.getMediaSource(path, headers, true);
            if (cached != null) {
                mMediaSource = cached;
                LOG.i((preloadTarget ? "echo-preload-disk-source: " : "echo-play-cache-source: ") + path);
            }
        }
    }

    /** 点播磁盘缓存标记(第二期「边播边缓存」;由 MyVideoView 注入,直播页恒 false) */
    public void setUseDiskCache(boolean enabled) {
        useDiskCache = enabled;
    }

    /**
     * 预载命中注入（预载方案第一期,见 skill/avbox-preload-next-episode-spec.md §5.4）:
     * prepare 前用 url+headers 查预载 registry,命中则把预载中的 PreloadMediaSource（含已缓冲数据）
     * 直接替换为本 player 的 MediaSource,跳过网络冷启动,实现秒开;未命中走原逻辑,行为不变。
     */
    @Override
    public void prepareAsync() {
        MediaSource preloaded = PreloadManagerHolder.tryAcquire(currentPlayPath, currentHeaders);
        if (preloaded != null) {
            mMediaSource = preloaded;
        }
        super.prepareAsync();
        if (preloaded != null) {
            // setMediaSource/prepare 消息已先入队(同一 looper):此时 remove 通知 manager 释放条目
            PreloadManagerHolder.confirmTaken();
        }
    }

    /**
     * 关闭 Exo 的"帧率匹配"(2026-09-12,用户定稿:**底层默认开启,不设开关**)。
     *
     * 现象:竖屏详情页/直播页播放小窗时,列表滑动与 bottom sheet 动画从 120fps 掉到 60fps,暂停播放即恢复;
     * 换 IJK 内核不出现,只有 Exo 出现。
     *
     * 根因:media3 的 `VideoFrameReleaseHelper` 默认会把视频帧率写进播放 Surface
     * (`Surface.setFrameRate(fps, FRAME_RATE_COMPATIBILITY_DEFAULT)`,策略默认
     * `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`,见 media3 1.9.0 源码 L140/L460)。
     * 系统据此做显示模式匹配,部分 ROM(实测 vivo V2425A / OriginOS)进一步把**整机刷新率**降到 60Hz ——
     * App 窗口级申请(preferredRefreshRate/preferredDisplayModeId/setRequestedFrameRate)压不住,
     * 只能从源头掐掉这个帧率提示。IJK 不写帧率,所以不受影响。
     *
     * 做法:向视频渲染器发 `Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY` + `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`,
     * media3 内部随即不再调用 `Surface.setFrameRate`(`updateSurfaceMediaFrameRate/clearSurfaceFrameRate` 直接返回)。
     * 代价:放弃"显示刷新率随视频帧率匹配"(24p→24Hz 这类完美匹配);手机高刷屏上 24/30p 在 120Hz 下本就是整除,
     * 影响很小。若要恢复 media3 默认行为,删掉 initPlayer 里的本方法调用即可。
     */
    private void disableFrameRateMatching() {
        if (mInternalPlayer == null || capturedVideoRenderers.isEmpty()) return;
        for (Renderer renderer : capturedVideoRenderers) {
            try {
                mInternalPlayer.createMessage(renderer)
                        .setType(Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY)
                        .setPayload(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                        .send();
                LOG.i("echo-frameRate matching OFF -> " + renderer.getClass().getSimpleName());
            } catch (Throwable th) {
                LOG.i("echo-frameRate matching OFF failed: " + th);
            }
        }
    }

    private RenderersFactory buildRenderersFactory(Context context) {
        DefaultRenderersFactory factory = new SubtitleOffsetRenderersFactory(context, new SubtitleDelayProvider() {
            @Override
            public long getDelayUs() {
                return internalSubtitleDelayUs;
            }
        }, capturedVideoRenderers)
                .setEnableDecoderFallback(true)
                // 音频硬解优先:MediaCodec 不支持的格式(AC3/DTS 类)才落到 ffmpeg 软解兜底
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        // 关闭 MediaCodec 异步队列(2026-09-15:由反射改为直接调用)。该方法是 media3 的**公开 API**
        // (1.11.1 实证 public final,无 @RestrictTo/@UnstableApi/@Deprecated),内部只是
        // DefaultMediaCodecAdapterFactory.forceDisableAsynchronous() 的实例开关,在 renderer 构建前调用即生效。
        // 原写法用反射调一个公开方法,唯一效果是把"升级 media3 时编译期报错"降级成"运行期静默失效"
        // (只少一行日志、异步队列被悄悄恢复)——改直接调用后,升级若该 API 有变,编译期就会暴露。
        // 日志保留用于真机确认确实走到了;若要恢复异步队列,删掉下面这行即可。
        factory.forceDisableMediaCodecAsynchronousQueueing();
        LOG.i("echo-exo-disable-async-codec-queue");
        return factory;
    }

    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedInfo == null) return data;
        logRendererListOnce(mappedInfo);

        for (int rendererIndex = 0; rendererIndex < mappedInfo.getRendererCount(); rendererIndex++) {
            int type = mappedInfo.getRendererType(rendererIndex);
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue;

            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format fmt = group.getFormat(trackIndex);
                    if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue;
                    String language = getLanguage(fmt);
                    String detail = type == C.TRACK_TYPE_VIDEO ? getVideoName(fmt) : getName(fmt);
                    TrackInfoBean bean = new TrackInfoBean();
                    bean.language = language;
                    bean.name = buildDisplayName(type == C.TRACK_TYPE_AUDIO ? str(R.string.player_menu_audio_track) : type == C.TRACK_TYPE_VIDEO ? str(R.string.player_menu_video_track) : str(R.string.player_menu_subtitle),
                            type == C.TRACK_TYPE_AUDIO ? data.getAudio().size() + 1 : type == C.TRACK_TYPE_VIDEO ? data.getVideo().size() + 1 : data.getSubtitle().size() + 1,
                            language, detail);
                    bean.renderId = rendererIndex;
                    bean.trackGroupId = groupIndex;
                    bean.trackId = trackIndex;
                    bean.groupIndex = groupIndex;
                    bean.index = trackIndex;
                    bean.selected = isCurrentTrackSelected(fmt, type);
                    bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt);
                    bean.type = type;
                    bean.formatKey = formatKey(fmt, type);

                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean);
                    } else if (type == C.TRACK_TYPE_VIDEO) {
                        data.addVideo(bean);
                    } else {
                        data.addSubtitle(bean);
                    }
                }
            }
        }
        return data;
    }

    /** 渲染器清单只落一次盘:getTrackInfo 在播放状态回调里被高频调用 */
    private boolean rendererListLogged;

    private void logRendererListOnce(MappingTrackSelector.MappedTrackInfo mappedInfo) {
        if (rendererListLogged) return;
        rendererListLogged = true;
        StringBuilder sb = new StringBuilder("echo-setTrack renderers:");
        for (int i = 0; i < mappedInfo.getRendererCount(); i++) {
            sb.append(" [").append(i).append("]type=").append(mappedInfo.getRendererType(i))
                    .append('/').append(mappedInfo.getRendererName(i));
        }
        LOG.i(sb.toString());
    }

    /** 用户显式选轨:改当前选择并记住**指纹**(下标换集即失效,存了必然选错轨) */
    public void setTrack(TrackInfoBean track) {
        if (track == null) return;
        if (!applyTrack(track.renderId, track.trackGroupId, track.trackId)) return;
        if (track.type == C.TRACK_TYPE_TEXT) {
            // 选内置字幕即重新决定字幕来源,覆盖 #off / #local / #online
            TrackMemory.saveSubtitle(contentKey, track.formatKey);
        } else {
            TrackMemory.saveTrack(contentKey, track.type, track.formatKey);
        }
    }

    /** 程序性选轨(默认字幕等自动逻辑),不写记忆 */
    public void selectTrack(TrackInfoBean track) {
        if (track == null) return;
        applyTrack(track.renderId, track.trackGroupId, track.trackId);
    }

    /** 下发选择(无记忆写入);返回是否真的下发 */
    private boolean applyTrack(int rendererIndex, int groupIndex, int trackIndex) {
        try {
            MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
            if (mappedInfo == null) {
                LOG.i("echo-setTrack: MappedTrackInfo is null");
                return false;
            }
            if (rendererIndex == C.INDEX_UNSET || rendererIndex < 0 || rendererIndex >= mappedInfo.getRendererCount()) {
                LOG.i("echo-setTrack: No renderer found");
                return false;
            }

            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            if (!isTrackIndexValid(groups, groupIndex, trackIndex)) {
                LOG.i("echo-setTrack: Invalid track index - group:" + groupIndex + ", track:" + trackIndex);
                return false;
            }
            DefaultTrackSelector.SelectionOverride override =
                    new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
            DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
            builder.setRendererDisabled(rendererIndex, false);
            builder.clearSelectionOverrides(rendererIndex);
            // 同一 track type 只允许一路渲染器持有选择:media3 只取第一个同类 definition、不清其余,
            // 两路音频渲染器同时 enable 即抛 "Multiple renderer media clocks enabled."(清掉即自动 disable)。
            int targetType = mappedInfo.getRendererType(rendererIndex);
            for (int i = 0; i < mappedInfo.getRendererCount(); i++) {
                if (i != rendererIndex && mappedInfo.getRendererType(i) == targetType) {
                    builder.clearSelectionOverrides(i);
                }
            }
            builder.setSelectionOverride(rendererIndex, groups, override);
            trackSelector.setParameters(builder.build());
            // 诊断:记录真正下发的选择(渲染器/组/轨/格式);本机 ROM 吞 logcat,只信 App 文件日志
            Format applied = groups.get(groupIndex).getFormat(trackIndex);
            LOG.i("echo-setTrack applied: renderer=" + rendererIndex + " group=" + groupIndex + " track=" + trackIndex
                    + " type=" + targetType
                    + " mime=" + (applied == null ? "null" : applied.sampleMimeType)
                    + " channels=" + (applied == null ? -1 : applied.channelCount)
                    + " codecs=" + (applied == null ? "null" : applied.codecs)
                    + " trackKey=" + contentKey);
            return true;
        } catch (Exception e) {
            LOG.i("echo-setTrack error: " + e.getMessage());
            return false;
        }
    }

    /** 按记忆还原音轨/视轨/内置字幕;无记忆/定位不到的类型保持播放器默认(内置字幕则退"国语→第一条") */
    public void restoreTracks() {
        restoreByMemory(C.TRACK_TYPE_AUDIO);
        restoreByMemory(C.TRACK_TYPE_VIDEO);
        restoreSubtitleByMemory();
    }

    private void restoreByMemory(int trackType) {
        String remembered = TrackMemory.loadTrack(contentKey, trackType);
        if (remembered == null) return;
        int[] position = locate(trackType, remembered);
        if (position == null) {
            LOG.i("echo-track-memory miss type=" + trackType + " key=" + contentKey + " fp=" + remembered);
            return;
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore type=" + trackType + " fp=" + remembered);
        }
    }

    /** 内置字幕按指纹还原;#off / #local / #online 三种来源决定由页面层落地,这里不动 */
    private void restoreSubtitleByMemory() {
        String record = TrackMemory.loadSubtitle(contentKey);
        if (record == null) return;
        if (!TrackMemory.isSubtitleTrack(record)) return;
        int[] position = locate(C.TRACK_TYPE_TEXT, record);
        if (position == null) {
            // 有决定但这一集定位不到(编码变了/有歧义):退回默认选轨,别变成"什么都没有"
            LOG.i("echo-track-memory text miss, use default: " + record);
            selectDefaultSubtitlePick();
            return;
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore text fp=" + record);
        }
    }

    /** 在指定类型的全部渲染器/组/轨里按指纹定位;返回 {渲染器,组,轨},定位不到返回 null */
    private int[] locate(int trackType, String fingerprint) {
        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedInfo == null) return null;
        List<String> keys = new ArrayList<>();
        List<int[]> positions = new ArrayList<>();
        for (int rendererIndex = 0; rendererIndex < mappedInfo.getRendererCount(); rendererIndex++) {
            if (mappedInfo.getRendererType(rendererIndex) != trackType) continue;
            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format format = group.getFormat(trackIndex);
                    // 与菜单口径一致:未声明语言的 CEA608/708 不进列表(菜单里看不到,就不会是"用户选过")
                    if (trackType == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(format)) continue;
                    keys.add(formatKey(format, trackType));
                    positions.add(new int[]{rendererIndex, groupIndex, trackIndex});
                }
            }
        }
        int index = TrackMemory.pick(keys, fingerprint);
        return index < 0 ? null : positions.get(index);
    }

    /** 轨道指纹:语言取菜单同款的归一化值(跨内核可比),编码优先 codecs、缺失退 mime 子类型 */
    private String formatKey(Format fmt, int trackType) {
        if (fmt == null) return "";
        String codec = firstNonEmpty(fmt.codecs, mimeSubtype(fmt));
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return TrackMemory.audioFingerprint(getLanguage(fmt), codec, fmt.channelCount);
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            return TrackMemory.videoFingerprint(codec, fmt.width, fmt.height);
        }
        return TrackMemory.textFingerprint(getLanguage(fmt), codec);
    }

    private String mimeSubtype(Format fmt) {
        if (fmt == null || fmt.sampleMimeType == null || !fmt.sampleMimeType.contains("/")) return "";
        return fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1);
    }

    private String firstNonEmpty(String first, String second) {
        return (first != null && !first.isEmpty()) ? first : (second == null ? "" : second);
    }

    public void loadDefaultSubtitleTrack() {
        if (defaultSubtitleTrackSelected) return;
        // 该片已有字幕决定(内置/外挂/关闭):默认选轨让位,由页面层按记忆落地;
        // 内置指纹定位不到时,restoreSubtitleByMemory 会自己退回默认选轨
        if (TrackMemory.loadSubtitle(contentKey) != null) {
            LOG.i("echo-track-memory subtitle decision exists, skip default");
            defaultSubtitleTrackSelected = true;
            return;
        }
        selectDefaultSubtitlePick();
    }

    /** 当前没有选中任何内置字幕轨时补一次默认选轨(外挂字幕落地失败回落、或媒体未标 DEFAULT 轨时全靠它) */
    public void ensureSubtitleTrackSelected() {
        List<TrackInfoBean> subtitles = getTrackInfo().getSubtitle();
        if (subtitles.isEmpty()) return;
        for (TrackInfoBean subtitle : subtitles) {
            if (subtitle.selected) return;
        }
        selectDefaultSubtitlePick();
    }

    /** 默认内置字幕:国语优先,否则第一条 */
    private void selectDefaultSubtitlePick() {
        List<TrackInfoBean> subtitles = getTrackInfo().getSubtitle();
        // 轨道还没映射出来时不封口:onTracksChanged 会再来一次(封了就再也选不上)
        if (subtitles.isEmpty()) return;
        defaultSubtitleTrackSelected = true;
        TrackInfoBean target = subtitles.get(0);
        for (TrackInfoBean subtitle : subtitles) {
            if ("国语".equals(subtitle.language)) { // i18n: keep(字幕语言匹配值)
                target = subtitle;
                break;
            }
        }
        selectTrack(target);
    }

    private void loadDefaultSubtitleTrackBeforeReady() {
        if (defaultSubtitleTrackSelectionClosed) return;
        loadDefaultSubtitleTrack();
    }

    public void setOnCuesListener(OnCuesListener listener) {
        onCuesListener = listener;
    }

    public void setInternalSubtitleDelay(int milliseconds) {
        internalSubtitleDelayUs = milliseconds * 1000L;
    }

    private boolean isTrackIndexValid(TrackGroupArray groups, int groupIndex, int trackIndex) {
        if (groupIndex < 0 || groupIndex >= groups.length) return false;
        TrackGroup group = groups.get(groupIndex);
        return trackIndex >= 0 && trackIndex < group.length;
    }

    private boolean isBitmapSubtitle(Format format) {
        if (format == null || format.sampleMimeType == null) return false;
        String mimeType = format.sampleMimeType.toLowerCase();
        return mimeType.contains("pgs") || mimeType.contains("dvb") || mimeType.contains("vobsub");
    }

    private boolean isUndeclaredClosedCaptionTrack(Format format) {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false;
        return MimeTypes.APPLICATION_CEA608.equals(format.sampleMimeType)
                || MimeTypes.APPLICATION_CEA708.equals(format.sampleMimeType);
    }

    private boolean isCurrentTrackSelected(Format format, int trackType) {
        if (mInternalPlayer == null) return false;
        Tracks tracks = mInternalPlayer.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i) && isSameFormat(format, group.getTrackFormat(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameFormat(Format a, Format b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.id != null && b.id != null && a.id.equals(b.id)) return true;
        return a.equals(b);
    }

    private static final Map<String, String> LANG_MAP = new HashMap<>();

    static {
        LANG_MAP.put("zh", "\u56fd\u8bed");
        LANG_MAP.put("zh-cn", "\u56fd\u8bed");
        LANG_MAP.put("cmn", "\u56fd\u8bed");
        LANG_MAP.put("chi", "\u56fd\u8bed");
        LANG_MAP.put("zho", "\u56fd\u8bed");
        LANG_MAP.put("chs", "\u56fd\u8bed");
        LANG_MAP.put("yue", "\u7ca4\u8bed");
        LANG_MAP.put("zh-hk", "\u7ca4\u8bed");
        LANG_MAP.put("zh-yue", "\u7ca4\u8bed");
        LANG_MAP.put("en", "\u82f1\u8bed");
        LANG_MAP.put("en-us", "\u82f1\u8bed");
        LANG_MAP.put("eng", "\u82f1\u8bed");
        LANG_MAP.put("ja", "\u65e5\u8bed");
        LANG_MAP.put("jpn", "\u65e5\u8bed");
        LANG_MAP.put("ko", "\u97e9\u8bed");
        LANG_MAP.put("kor", "\u97e9\u8bed");
        LANG_MAP.put("th", "\u6cf0\u8bed");
        LANG_MAP.put("tha", "\u6cf0\u8bed");
    }

    private String getLanguage(Format fmt) {
        String language = matchLanguage(fmt.language);
        if (!language.isEmpty()) {
            return language;
        }
        return matchLanguage((fmt.label == null ? "" : fmt.label) + " "
                + (fmt.id == null ? "" : fmt.id) + " "
                + (fmt.codecs == null ? "" : fmt.codecs));
    }

    private String matchLanguage(String text) {
        if (text == null) return "";
        String value = text.toLowerCase();
        String mapped = LANG_MAP.get(value);
        if (mapped != null) return mapped;
        if (value.contains("yue") || value.contains("cantonese") || value.contains("\u7ca4") || value.contains("\u5e7f\u4e1c")) {
            return "\u7ca4\u8bed";
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs")
                || value.contains("cht") || value.contains("cmn") || value.contains("\u4e2d")
                || value.contains("\u56fd\u8bed") || value.contains("\u666e\u901a\u8bdd")) {
            return "\u56fd\u8bed";
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("\u82f1")) {
            return "\u82f1\u8bed";
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("\u65e5")) {
            return "\u65e5\u8bed";
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("\u97e9")) {
            return "\u97e9\u8bed";
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "\u6cf0\u8bed";
        }
        return "";
    }

    private String getName(Format fmt) {
        String channelLabel;
        if (fmt.channelCount <= 0) {
            channelLabel = "";
        } else if (fmt.channelCount == 1) {
            channelLabel = str(R.string.player_channel_mono);
        } else if (fmt.channelCount == 2) {
            channelLabel = str(R.string.player_channel_stereo);
        } else {
            channelLabel = str(R.string.player_channel_count, fmt.channelCount);
        }

        String codec = "";
        if (fmt.codecs != null && !fmt.codecs.isEmpty()) {
            codec = fmt.codecs.toUpperCase();
        }
        if (fmt.sampleMimeType != null && fmt.sampleMimeType.contains("/")) {
            String mime = fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1);
            if (codec.isEmpty()) {
                codec = mime.toUpperCase();
            }
        }
        StringBuilder builder = new StringBuilder();
        appendPart(builder, fmt.label);
        appendPart(builder, codec);
        appendPart(builder, channelLabel);
        return builder.toString();
    }

    private String getVideoName(Format fmt) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, fmt.label);
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, fmt.width + "x" + fmt.height);
        }
        if (fmt.codecs != null && !fmt.codecs.isEmpty()) {
            appendPart(builder, fmt.codecs.toUpperCase());
        } else if (fmt.sampleMimeType != null && fmt.sampleMimeType.contains("/")) {
            appendPart(builder, fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1).toUpperCase());
        }
        return builder.toString();
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

    private void appendPart(StringBuilder builder, String value) {
        if (value == null) return;
        String part = value.trim();
        if (part.isEmpty() || "und".equalsIgnoreCase(part) || "\u672a\u77e5".equals(part)) return;
        if (builder.length() > 0) {
            builder.append(" / ");
        }
        builder.append(part);
    }

    public interface OnCuesListener {
        void onCues(List<Cue> cues);
    }

    private interface SubtitleDelayProvider {
        long getDelayUs();
    }

    private static final class SubtitleOffsetRenderersFactory extends DefaultRenderersFactory {
        private final SubtitleDelayProvider subtitleDelayProvider;
        /** 收集本工厂创建的视频渲染器(帧率匹配策略需要按渲染器实例下发消息) */
        private final List<Renderer> videoRendererSink;

        SubtitleOffsetRenderersFactory(Context context, SubtitleDelayProvider subtitleDelayProvider,
                                       List<Renderer> videoRendererSink) {
            super(context);
            this.subtitleDelayProvider = subtitleDelayProvider;
            this.videoRendererSink = videoRendererSink;
        }

        @Override
        protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                           MediaCodecSelector mediaCodecSelector,
                                           boolean enableDecoderFallback, android.os.Handler eventHandler,
                                           androidx.media3.exoplayer.video.VideoRendererEventListener eventListener,
                                           long allowedJoiningTimeMs, ArrayList<Renderer> out) {
            int firstRendererIndex = out.size();
            // 解码方式注入点(2026-09-17):只换视频渲染器的选择器(EXO_VIDEO_CODEC_SELECTOR 内部
            // 按静态下发位在软解/硬解之间动态二选一),音频渲染器继续用工厂默认选择器
            super.buildVideoRenderers(context, extensionRendererMode, EXO_VIDEO_CODEC_SELECTOR, enableDecoderFallback,
                    eventHandler, eventListener, allowedJoiningTimeMs, out);
            if (videoRendererSink != null) {
                for (int i = firstRendererIndex; i < out.size(); i++) {
                    videoRendererSink.add(out.get(i));
                }
            }
        }

        @Override
        protected void buildTextRenderers(Context context, TextOutput output, Looper outputLooper,
                                          int extensionRendererMode, ArrayList<Renderer> out) {
            int firstRendererIndex = out.size();
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out);
            for (int i = firstRendererIndex; i < out.size(); i++) {
                Renderer renderer = out.get(i);
                out.set(i, (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(),
                        new Class<?>[]{Renderer.class},
                        new SubtitleOffsetRendererHandler(renderer, subtitleDelayProvider)));
            }
        }
    }

    private static final class SubtitleOffsetRendererHandler implements InvocationHandler {
        private final Renderer renderer;
        private final SubtitleDelayProvider subtitleDelayProvider;

        SubtitleOffsetRendererHandler(Renderer renderer, SubtitleDelayProvider subtitleDelayProvider) {
            this.renderer = renderer;
            this.subtitleDelayProvider = subtitleDelayProvider;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] invokeArgs = args;
            if ("render".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof Long) {
                invokeArgs = args.clone();
                long positionUs = (Long) invokeArgs[0];
                invokeArgs[0] = Math.max(0, positionUs - subtitleDelayProvider.getDelayUs());
            }
            try {
                return method.invoke(renderer, invokeArgs);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
