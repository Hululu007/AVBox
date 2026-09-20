package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.KV;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

/**
 * 引擎层预载持有者（预载方案第一期,见 skill/avbox-preload-next-episode-spec.md §5.4）。
 *
 * <p>持有全局唯一的 {@link DefaultPreloadManager}（media3 1.9.0,与播放进程同生命周期）,
 * 把「下一集」的数据预载进内存。与播放侧的 header 语义统一:
 * 预载 MediaItem 由 {@link ExoMediaSourceHelper#buildMediaItem} 构建（headers 进 requestMetadata.extras）,
 * MediaSource 由 {@link PreloadMediaSourceFactory} 构建——它直接调
 * {@link ExoMediaSourceHelper#getMediaSource},与正片播放 100% 同源构建。
 *
 * <p>命中注入（app ExoPlayer.prepareAsync 覆写）：url+headers 逐字节一致才命中,
 * 避免「同 url 不同 headers」被 media3 的 MediaItem.equals 判等后注入错误 headers 的预载数据
 * （MediaItem.equals 不比较 headers 内容,故自行维护 key→MediaItem registry）。
 *
 * <p>内存水位:32MB（规格 §8.2:64MB 对 2GB 以下盒子偏大,首期取 32MB,配合开关默认关闭）。
 */
public final class PreloadManagerHolder {
    private static final String TAG = "PreloadManager";

    /** 预载时长(秒)兜底值/边界:设置项「预载时长」20~120 步长 10(第二期参数化) */
    private static final int PRELOAD_SECONDS_DEFAULT = 60;
    private static final int PRELOAD_SECONDS_MIN = 20;
    private static final int PRELOAD_SECONDS_MAX = 120;
    /** 预载内存水位上限 32MB(预载时长对应的数据量超过水位即停,两者取小) */
    private static final int TARGET_BUFFER_BYTES = 32 << 20;

    /** 当前预载请求的起始位置（ms,片头跳过/历史进度对齐,预载线程经 TargetPreloadStatusControl 读取） */
    private static volatile long sStartPosMs = 0L;
    /** 当前预载请求的数据时长（ms,设置项「预载时长」,预载线程经 TargetPreloadStatusControl 读取） */
    private static volatile long sRangeMs = PRELOAD_SECONDS_DEFAULT * 1000L;

    private static DefaultPreloadManager sManager;
    /** 预载/播放共享线程(进程级单例,见 preloadLooper) */
    private static HandlerThread sPreloadThread;
    /** key(url+headers) → 预载中的 MediaItem,命中查找用 */
    private static final Map<String, MediaItem> sRegistry = new HashMap<>();
    /** 命中后待移除的条目(等播放器真正接管 source 后再 remove,见 confirmTaken) */
    private static MediaItem sPendingItem;
    /**
     * 本播放页会话内「已预载过的 url」(LRU 8,第二期磁盘兜底):
     * 切到这些 url 时播放侧换用 cache 版 MediaSource 从共享 SimpleCache 读盘命中——
     * 内存预载数据被清理(clearAll)后磁盘数据仍在,起播不必网络冷启动。
     * clearAll 不清(磁盘数据与 manager 生命周期无关),release 清。
     */
    private static final Map<String, Boolean> sPreloadTargets =
            new LinkedHashMap<String, Boolean>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 8;
                }
            };
    /** 预载完成回调(第二期 UI 提示「下一集已就绪」;由 PlayContainer 注入,任意线程调用) */
    private static volatile ReadyListener sReadyListener;

    /** 预载就绪监听(第二期 UI 提示用) */
    public interface ReadyListener {
        void onPreloadReady(String url);
    }

    private PreloadManagerHolder() {
    }

    /** 预载总开关（设置页「下一集预载」,默认关） */
    public static boolean enabled() {
        return KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false);
    }

    /**
     * 发起一次预载。同 url+headers 已在预载中时幂等跳过。
     *
     * @param startPosMs 预载起点（对齐片头跳过 max(st, 历史进度),< 0 时按 0 处理）
     */
    public static synchronized void preload(Context context, String url, Map<String, String> headers, long startPosMs) {
        if (!enabled() || url == null || url.isEmpty()) {
            return;
        }
        try {
            DefaultPreloadManager manager = get(context.getApplicationContext());
            String key = keyOf(url, headers);
            if (sRegistry.containsKey(key)) {
                LOG.i("echo-preload-already: " + url);
                return;
            }
            sStartPosMs = Math.max(0L, startPosMs);
            sRangeMs = preloadRangeMs();
            sPreloadTargets.put(url, Boolean.TRUE);
            MediaItem item = ExoMediaSourceHelper.buildMediaItem(url, headers);
            sRegistry.put(key, item);
            manager.add(item, 0); // rankingData 仅用于多项排序,本项目恒只预载 1 项
            // 注意:BasePreloadManager.add() 不触发重新排序,必须手动 invalidate 才会真正开始预载
            manager.invalidate();
            LOG.i("echo-preload-start: " + url);
        } catch (Throwable th) {
            LOG.e("echo-preload-error: " + url + " " + th);
        }
    }

    /**
     * 命中查找:返回与 url+headers 完全一致的预载 MediaSource 并移出预载队列（转正片使用）。
     * 未命中返回 null。命中后预载数据随 MediaSource 一并交给播放器,无缝接续。
     */
    public static synchronized MediaSource tryAcquire(String url, Map<String, String> headers) {
        if (sManager == null) {
            return null;
        }
        String key = keyOf(url, headers);
        MediaItem item = sRegistry.remove(key);
        if (item == null) {
            // 诊断:队列里有预载但 url+headers 对不上(规格 §8.4 风险:url 不一致则永不命中)
            if (!sRegistry.isEmpty()) {
                LOG.i("echo-preload-miss: key mismatch, registry=" + sRegistry.size() + ", url=" + url);
            }
            return null;
        }
        try {
            MediaSource source = sManager.getMediaSource(item);
            if (source != null) {
                // 不在此处 remove:官方顺序为「取源 → 播放器 setMediaSource/prepare 接管 → 再 remove」,
                // 提前 remove 会触发 releasePreloadMediaSource() 把已预载的 period 释放掉(数据浪费),
                // 改由 confirmTaken() 在 prepareAsync 提交后收尾
                sPendingItem = item;
                LOG.i("echo-preload-hit: " + url + " class=" + source.getClass().getSimpleName());
                return source;
            }
        } catch (Throwable th) {
            LOG.e("echo-preload-acquire-error " + th);
        }
        // 取源失败:条目放回 registry(2026-09-12 修复 Bug)。原先先 remove 后取源、失败直接 return,
        // 条目既没被消费也再拿不到,本集将永久无法命中/重试
        sRegistry.put(key, item);
        return null;
    }

    /**
     * 播放器已 setMediaSource/prepare 后调用(tryAcquire 返回非 null 时必调一次):
     * 此时 source 已由播放器接管(isUsedByPlayer=true),再 remove 通知 manager 释放该条目——
     * 此状态下 PreloadMediaSource.releaseSourceInternal() 会跳过真正释放,不影响在用播放。
     */
    public static synchronized void confirmTaken() {
        MediaItem item = sPendingItem;
        sPendingItem = null;
        if (item != null && sManager != null) {
            try {
                sManager.remove(item);
            } catch (Throwable th) {
                LOG.e("echo-preload-confirm-error " + th);
            }
        }
    }

    /** 是否存在预载中的条目（仅用于日志/诊断,不参与命中判断） */
    public static synchronized boolean hasActivePreload() {
        return !sRegistry.isEmpty();
    }

    /**
     * 该 url 是否为本播放页会话内预载过的目标（第二期磁盘兜底判定）:
     * app ExoPlayer.setDataSource 命中时换用 cache 版 MediaSource,从共享 SimpleCache 读盘。
     */
    public static synchronized boolean isPreloadTargetUrl(String url) {
        return url != null && !url.isEmpty() && sPreloadTargets.containsKey(url);
    }

    /** 注入预载完成回调（第二期 UI 提示） */
    public static void setReadyListener(ReadyListener listener) {
        sReadyListener = listener;
    }

    /** 注销回调：仅当当前回调仍为传入实例时清空（防多播放容器交错销毁时误清后来者的回调） */
    public static synchronized void clearReadyListener(ReadyListener listener) {
        if (listener != null && sReadyListener == listener) {
            sReadyListener = null;
        }
    }

    /** 预载时长(ms):读设置项「预载时长」,越界兜底;每次发起预载时快照,预载线程经 volatile 读取 */
    private static long preloadRangeMs() {
        int seconds = PRELOAD_SECONDS_DEFAULT;
        try {
            seconds = KV.get(HawkConfig.PRELOAD_DURATION, PRELOAD_SECONDS_DEFAULT);
        } catch (Throwable th) {
            LOG.e("PreloadManagerHolder", "preload duration KV read failed, use default", th);
        }
        seconds = Math.max(PRELOAD_SECONDS_MIN, Math.min(PRELOAD_SECONDS_MAX, seconds));
        return seconds * 1000L;
    }

    /** 清空全部预载（切集/换线/换源/暂停策略变化等失效事件,见规格 §6）,保留 manager 可复用 */
    public static synchronized void clearAll() {
        if (sManager == null) {
            return;
        }
        // 交接中的条目先单独移除:避免 reset() 波及已被播放器接管的 source
        if (sPendingItem != null) {
            try {
                sManager.remove(sPendingItem);
            } catch (Throwable th) {
                LOG.e("PreloadManagerHolder", "remove pending preload item failed", th);
            }
            sPendingItem = null;
        }
        if (!sRegistry.isEmpty()) {
            LOG.i("echo-preload-clear: " + sRegistry.size());
            sRegistry.clear();
        }
        try {
            sManager.reset();
        } catch (Throwable th) {
            LOG.e("echo-preload-clear-error " + th);
        }
    }

    /** 释放 manager（退出播放页）,下次预载重新构建 */
    public static synchronized void release() {
        if (sManager != null) {
            try {
                sManager.release();
            } catch (Throwable th) {
                LOG.e("PreloadManagerHolder", "preload manager release failed", th);
            }
            sManager = null;
        }
        sRegistry.clear();
        sPendingItem = null;
        sPreloadTargets.clear();
    }

    private static DefaultPreloadManager get(Context appContext) {
        if (sManager == null) {
            TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus> control =
                    rankingData -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(sStartPosMs, sRangeMs);
            sManager = new DefaultPreloadManager.Builder(appContext, control)
                    .setMediaSourceFactory(new PreloadMediaSourceFactory(appContext))
                    .setLoadControl(new DefaultLoadControl.Builder()
                            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                            // 内存水位优先:到 32MB 即停,预载不挤占正片内存预算
                            .setPrioritizeTimeOverSizeThresholds(false)
                            .build())
                    // 预载线程须与播放器 playback looper 同一(PreloadMediaSource 硬校验,见 preloadLooper)
                    .setPreloadLooper(preloadLooper())
                    .build();
            sManager.addListener(new PreloadManagerListener() {
                @Override
                public void onCompleted(MediaItem mediaItem) {
                    String url = mediaItem == null || mediaItem.localConfiguration == null
                            ? null : mediaItem.localConfiguration.uri.toString();
                    ReadyListener listener = sReadyListener;
                    LOG.i("echo-preload-complete: " + url + ", listener=" + (listener != null));
                    if (listener != null && url != null) {
                        try {
                            listener.onPreloadReady(url);
                        } catch (Throwable th) {
                            LOG.e("PreloadManagerHolder", "preload ready callback failed", th);
                        }
                    }
                }

                @Override
                public void onError(PreloadException exception) {
                    LOG.e("echo-preload-error: " + exception);
                }
            });
        }
        return sManager;
    }

    /**
     * 预载线程(2026-09-12 预载方案修正):PreloadMediaSource.prepareSourceInternal() 硬校验
     * 「播放该源的 player 的 looper == preloadHandler 的 looper」,不满足则交接预载源瞬间抛
     * IllegalStateException 导致播放失败。故播放器(app ExoPlayer)与预载管理器共享此进程级线程。
     *
     * <p>常驻不退出:DefaultPreloadManager 以「外部注入 looper」模式持有(构造 PlaybackLooperProvider
     * 传入外部 looper),release() 只减引用计数、不会 quit 该线程;手动 quit 会造成
     * 「looper 已退出但播放器实例仍绑定」的崩溃(GET 消息丢失/IllegalStateException)。
     */
    public static synchronized Looper preloadLooper() {
        if (sPreloadThread == null || !sPreloadThread.isAlive()) {
            sPreloadThread = new HandlerThread("avbox-preload", android.os.Process.THREAD_PRIORITY_AUDIO);
            sPreloadThread.start();
        }
        return sPreloadThread.getLooper();
    }

    /** url + 规范化(headers) 作为命中 key,headers 逐项一致才命中 */
    private static String keyOf(String url, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder(url).append('\n');
        if (headers != null) {
            Map<String, String> sorted = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    sorted.put(entry.getKey().trim(), entry.getValue().trim());
                }
            }
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                sb.append(entry.getKey()).append(':').append(entry.getValue()).append(';');
            }
        }
        return sb.toString();
    }

    /**
     * 预载侧 MediaSource 工厂:与播放侧同源——直接复用 ExoMediaSourceHelper.getMediaSource,
     * headers 从 MediaItem 的 requestMetadata.extras 取回（buildMediaItem 写入）。
     */
    private static final class PreloadMediaSourceFactory implements MediaSource.Factory {
        private final Context appContext;

        PreloadMediaSourceFactory(Context context) {
            appContext = context;
        }

        @Override
        public MediaSource createMediaSource(MediaItem mediaItem) {
            String uri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri.toString()
                    : mediaItem.mediaId;
            Map<String, String> headers = ExoMediaSourceHelper.getHeadersFrom(mediaItem);
            // isCache=true(第二期):预载数据经共享 SimpleCache 落盘,播放侧对同一 url 用 cache 版源读盘命中
            return ExoMediaSourceHelper.getInstance(appContext).getMediaSource(uri, headers, true);
        }

        @Override
        public int[] getSupportedTypes() {
            return new int[]{
                    C.CONTENT_TYPE_OTHER,
                    C.CONTENT_TYPE_HLS,
                    C.CONTENT_TYPE_DASH,
                    C.CONTENT_TYPE_RTSP};
        }

        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(androidx.media3.exoplayer.drm.DrmSessionManagerProvider provider) {
            // 预载不做 DRM(可预载判定已排除解析源/DRM 场景)
            return this;
        }

        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy policy) {
            return this;
        }
    }
}
