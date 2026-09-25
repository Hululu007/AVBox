package com.github.tvbox.osc.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Base64;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.PreloadManagerHolder;
import com.github.tvbox.osc.ui.player.PreloadCoordinator;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.WatchProgressStore;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.ui.player.PlayerTipBridge;
import com.github.tvbox.osc.viewmodel.SourceViewModel;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * 播放会话与派生数据层:播什么(vod/sourceKey/sourceBean/播放器配置)、进度与字幕缓存键、
 * 线路/剧集匹配、清晰度、投屏地址改写、header 提取;视图交互一律经 {@link PlaybackViewBridge}。
 * 取流/解析调度见 {@link PlayUrlResolver},尝试/意图状态见 {@link PlaybackAttemptState}。
 */
public class PlaybackController {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    // ==================== 会话数据 ====================

    private VodInfo vod;
    private JSONObject playerCfg;
    private String sourceKey = "";
    private SourceBean sourceBean;

    /** 当前集进度键(源+片+线路+集+集名) */
    private String progressKey;
    /** 进度键的归属(源|片id),与 progressKey 同处更新;进度键取 MD5 后无法反推归属 */
    private String progressOwner;
    /** 当前集字幕缓存键 */
    private String subtitleCacheKey;
    private String playSubtitle;
    private String playLyric;
    private String lyricCacheKey;

    /** 当前清晰度列表原始结果(多清晰度源的 url 数组;null=该源无多清晰度) */
    private JSONObject qualityResult;

    /** 净化后的 m3u8 代理地址与其原始地址(投屏时换回源地址) */
    private String m3u8ProxyUrl;
    private String m3u8SourceUrl;

    /** 换源/换线时"接着看"的进度(新键无历史时才写入) */
    private String inheritProgressKey;
    private long inheritProgress;

    // ==================== 会话生命周期 ====================

    /**
     * 开启一次播放会话:接管页面组装的 {@link PlaybackSession} 并初始化播放器配置。
     * 调用方随后需自行把 {@link #playerCfg()} 刷到控制器(原 `initPlayerCfg` 末尾那次调用)。
     */
    public void startSession(PlaybackSession session) {
        // **会话边界清场**:在途的解析/嗅探/取流/超时属于上一个会话,其结果不得作用到新会话。
        // 收尾必须放在会话边界而非"页面销毁":两者先后不确定,"快速返回再进入"时旧页面会把新会话
        // 刚发起的取流一起撤掉。
        cancelInFlight();
        // 作废上一条"播完待撤会话"的待判消息(见 handlePendingCompletionDrop),避免落到新会话上。
        timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
        // 代际复位:上一会话的迟到回调不得作用到新会话
        resolver.resetGen();
        // 封面属于上一个会话的内容,换内容必须清:playArtwork 只写一次(见 updateMusicSession 的 isEmpty 守卫)、
        // currentArtwork 只在取流结果里被覆盖 ⇒ 影视源不给 cover 时会残留上一首的值("音乐 → 影视 → 再进音乐页")。
        // 同片接管不能清,否则封面会白到下一次取流结果。
        if (currentSession == null
                || !TextUtils.equals(currentSession.playbackKey(), session.playbackKey())) {
            playArtwork = null;
            currentArtwork = null;
            // 换内容 ⇒ 上一份内容的"纯音频"确认作废(同片接管不清:内容没变)
            st.audioOnlyConfirmed = false;
        }
        this.currentSession = session;
        // 本次会话的内容尚未真正交给播放器:先清掉"已起播内容"标记 ——
        // 否则"切到 B 但取流失败(播放器里其实还是 A)"后重进 B,会被 D6 误判成同片接管(播错内容)
        startedPlaybackKey = null;
        // 会话级状态的统一复位。
        // 这些字段原来的复位点全在 play() 里,而 **D6 同片接管不走 play()** —— 退出页面再进同一部时
        // 会带着上一轮的陈旧值:
        //  · playbackStarted 陈旧 true ⇒ 续播失败被 errorWithRetry 静默吞掉(黑屏、无提示、不重试);
        //  · switchStopPending 残留 ⇒ 在途取流结果被静默丢弃;
        //  · m3u8 代理地址残留 ⇒ 投屏地址可能拿到上一部的源地址。
        st.beginSession();
        clearM3u8ProxyUrl();
        this.vod = session.vod();
        this.sourceKey = session.sourceKey();
        this.sourceBean = ApiConfig.get().getSource(sourceKey);
        ApiConfig.get().setCurrentPlaySourceKey(sourceKey);
        initPlayerCfg();
    }

    /**
     * 初始化/补全播放器配置(内核 pl、渲染 pr/ijk/sc/sp/st/et)。
     * 与原实现一致:优先沿用 vod.playerCfg 里已存的值,缺失项回落全局设置。
     */
    public void initPlayerCfg() {
        try {
            playerCfg = new JSONObject(vod.playerCfg);
        } catch (Throwable th) {
            playerCfg = new JSONObject();
        }
        try {
            if (!playerCfg.has("pl")) {
                // sourceBean 可能为空(切源窗口期 / 源被删):
                // 原写法在这里 NPE,而本块 catch(Throwable) 是空的 —— 会静默跳过下面
                // pr/ijk/sc/sp/st/et 全部设置,播放器配置只剩半截。改为退回全局播放器设置。
                int sourcePlayerType = sourceBean == null ? -1 : sourceBean.getPlayerType();
                playerCfg.put("pl", (sourcePlayerType == -1) ? (int) KV.get(HawkConfig.PLAY_TYPE, 2) : sourcePlayerType);
            }
            if (playerCfg.optInt("pl", 2) == 0) {
                playerCfg.put("pl", 2);
            }
            playerCfg.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1));
            // 解码方式(两个内核各记一份):以**全局设置**为准,只有用户在该内核下
            // 于本剧播放器里显式选过(ijkSet / exoSet,见 ComposeVideoController.onIjkClicked)才按剧记忆 ——
            // 否则播放记录里持久化的旧 "ijk"/"exo" 会一直压过设置页的新值,"设置里改成软解、这部剧却永远硬解"。
            if (playerCfg.optInt("ijkSet", 0) == 0) {
                playerCfg.put("ijk", KV.get(HawkConfig.IJK_CODEC, "硬解码")); // i18n: keep
            }
            if (playerCfg.optInt("exoSet", 0) == 0) {
                playerCfg.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")); // i18n: keep
            }
            if (!playerCfg.has("sc")) {
                playerCfg.put("sc", KV.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!playerCfg.has("sp")) {
                playerCfg.put("sp", 1.0f);
            }
            if (!playerCfg.has("st")) {
                playerCfg.put("st", 0);
            }
            if (!playerCfg.has("et")) {
                playerCfg.put("et", 0);
            }
        } catch (Throwable th) {
            // 与原实现一致:补全失败不阻断播放(配置保持已解析出的部分)
            LOG.d("PlaybackController", "initPlayerCfg fill-up failed, keep parsed part");
        }
    }

    // ==================== 进度与缓存键 ====================

    public long getSavedProgress(String url) {
        int st = (playerCfg == null) ? 0 : playerCfg.optInt("st", 0);
        long skip = st * 1000L;
        // 无痕:旧记录连读都不读 —— 只拦写的话,重进仍会从上次留下的位置接着播,隐身等于没开
        if (HistoryHelper.isIncognito()) return skip;
        Object theCache = CacheManager.getCache(MD5.string2MD5(url));
        if (theCache == null) {
            return skip;
        }
        long rec = 0;
        if (theCache instanceof Long) {
            rec = (Long) theCache;
        } else if (theCache instanceof String) {
            try {
                rec = Long.parseLong((String) theCache);
            } catch (NumberFormatException e) {
                LOG.i("echo-String value is not a valid long.");
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.");
        }
        return Math.max(rec, skip);
    }

    /**
     * 记录"接着看"的进度(换源点击即停/自动换线时调用)。
     * 新键已有历史记录时不覆盖(见 {@link #inheritProgressIfNeeded()})。
     */
    public void inheritProgressFrom(String key, long position) {
        this.inheritProgressKey = key;
        this.inheritProgress = position;
    }

    /** 把"接着看"的进度写进新键(仅当新键无历史);无论结果如何都清掉待继承状态 */
    public void inheritProgressIfNeeded() {
        try {
            WatchProgressStore.inherit(progressOwner(), inheritProgressKey, progressKey, inheritProgress);
        } finally {
            inheritProgressKey = null;
            inheritProgress = 0;
        }
    }

    /** 进度索引的归属键(源|片id):与 {@link #progressKey()} 成对,未起播过则为 null(此时只落进度、不维护索引) */
    @Nullable
    public String progressOwner() {
        return progressOwner;
    }

    // 线路/剧集匹配见 EpisodeMatcher

    @Nullable
    public VodInfo.VodSeries currentSeries(String flag, int index) {
        if (flag == null || vod == null || vod.seriesMap == null) {
            return null;
        }
        List<VodInfo.VodSeries> currentList = vod.seriesMap.get(flag);
        if (currentList == null || currentList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(index, currentList.size() - 1));
        return currentList.get(safeIndex);
    }

    /**
     * 取流结果是否已过期(切集/换线/换源后,旧源在途结果不得拉起播放)。
     */
    public boolean isStalePlayResult(JSONObject info) {
        if (vod == null || vod.seriesMap == null || TextUtils.isEmpty(progressKey)) return false;
        String resultKey = info.optString("proKey", "");
        if (!TextUtils.isEmpty(resultKey) && !progressKey.equals(resultKey)) return true;
        String resultFlag = info.optString("flag", "");
        if (!TextUtils.isEmpty(resultFlag) && !resultFlag.equals(vod.playFlag)) return true;
        String sourceUrl = info.optString("key", "");
        if (!TextUtils.isEmpty(sourceUrl)) {
            VodInfo.VodSeries vs = currentSeries(vod.playFlag, vod.playIndex);
            return vs != null && !sourceUrl.equals(vs.url);
        }
        return false;
    }

    // ==================== 清晰度 ====================

    /** 发布/清空清晰度列表(旧 publishQuality:仅改内存态 + EventBus 广播,不启动播放) */
    public void publishQuality(JSONObject info) {
        try {
            JSONArray urls = new JSONArray(info == null ? "" : info.optString("url"));
            if (urls.length() < 4 || urls.length() % 2 != 0) throw new JSONException("invalid quality urls");
            qualityResult = new JSONObject(info.toString());
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, qualityResult));
        } catch (Throwable th) {
            qualityResult = null;
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, null));
        }
    }

    @Nullable
    public JSONObject quality() {
        return qualityResult;
    }

    // ==================== 投屏地址改写 ====================

    /** m3u8 代理地址 → 源地址;本地代理地址 → 局域网地址(配合 ControlManager 的地址发现) */
    public String getCastUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (isM3u8ProxyUrl(url) && !TextUtils.isEmpty(m3u8SourceUrl)) return m3u8SourceUrl;
        String local = ControlManager.get().getAddress(true);
        String server = ControlManager.get().getAddress(false);
        if (!TextUtils.isEmpty(local) && !TextUtils.isEmpty(server) && url.startsWith(local)) {
            return server + url.substring(local.length());
        }
        return url;
    }

    public boolean isM3u8ProxyUrl(String url) {
        return !TextUtils.isEmpty(m3u8ProxyUrl) && url.equals(m3u8ProxyUrl);
    }

    public void setM3u8Urls(String proxyUrl, String sourceUrl) {
        this.m3u8ProxyUrl = proxyUrl;
        this.m3u8SourceUrl = sourceUrl;
    }

    @Nullable
    public String m3u8SourceUrl() {
        return m3u8SourceUrl;
    }

    public void clearM3u8ProxyUrl() {
        m3u8ProxyUrl = null;
        m3u8SourceUrl = null;
    }

    // ==================== 播放请求头 ====================

    /**
     * 提取播放请求头(与预载侧 `PreloadCoordinator.extractHeaders` 共用
     * `PlayerHelper.extractPlayHeaders` —— 两侧口径必须逐字一致,否则预载与播放的
     * keyOf(url,headers) 不匹配,共享 SimpleCache 的「下一集预载」永不命中)。
     */
    public static HashMap<String, String> extractHeaders(JSONObject object) {
        return PlayerHelper.extractPlayHeaders(object);
    }

    public static void putHeaders(JSONObject target, HashMap<String, String> headers) throws JSONException {
        if (target == null || headers == null) return;
        for (String key : headers.keySet()) {
            target.put(key, headers.get(key));
        }
    }

    @Nullable
    public static String headerValue(HashMap<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return headers.get(key);
            }
        }
        return null;
    }

    // ==================== 访问器 ====================

    @Nullable
    public VodInfo vod() {
        return vod;
    }

    @Nullable
    public JSONObject playerCfg() {
        return playerCfg;
    }

    @Nullable
    public SourceBean sourceBean() {
        return sourceBean;
    }

    public String sourceKey() {
        return sourceKey;
    }

    @Nullable
    public String progressKey() {
        return progressKey;
    }

    public void setProgressKey(String progressKey) {
        this.progressKey = progressKey;
        // 归属与键同处更新:换片时先 release 旧内核(那一刻视图里还是旧键),此处若按当前 vod 归属会把旧片的键记到新片名下
        this.progressOwner = WatchProgressStore.ownerOf(vod);
    }

    @Nullable
    public String subtitleCacheKey() {
        return subtitleCacheKey;
    }

    public void setSubtitleCacheKey(String subtitleCacheKey) {
        this.subtitleCacheKey = subtitleCacheKey;
    }

    @Nullable
    public String playSubtitle() {
        return playSubtitle;
    }

    public void setPlaySubtitle(String playSubtitle) {
        this.playSubtitle = playSubtitle;
    }

    @Nullable
    public String playLyric() {
        return playLyric;
    }

    public void setPlayLyric(String playLyric) {
        this.playLyric = playLyric;
    }

    @Nullable
    public String lyricCacheKey() {
        return lyricCacheKey;
    }

    public void setLyricCacheKey(String lyricCacheKey) {
        this.lyricCacheKey = lyricCacheKey;
    }

    // ==================== 调度:重试与换线决策 ====================
    // 一切视图交互都经 PlaybackViewBridge。

    /** 视图侧契约(页面内由 PlayContainer 提供匿名实现) */
    private PlaybackViewBridge view;

    public void setViewBridge(PlaybackViewBridge bridge) {
        this.view = bridge;
    }

    /** 取流超时/换线播放超时(与既有 mHandler 的三条定时消息拆开:解析超时留在页面/解析层) */
    private static final int MSG_RESOLVE_PLAY_URL_TIMEOUT = 101;
    private static final int MSG_SWITCH_LINE_PLAY_TIMEOUT = 102;
    /**
     * 本集播完后的**延后一拍**撤会话判定(见 {@link #handlePlayStateForMusicSession})。
     */
    private static final int MSG_DROP_SESSION_AFTER_COMPLETED = 103;
    private static final long RESOLVE_PLAY_URL_TIMEOUT_MS = 15 * 1000L;
    private static final long SWITCH_LINE_PLAY_TIMEOUT_MS = 20 * 1000L;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_RESOLVE_PLAY_URL_TIMEOUT:
                    handleResolvePlayUrlTimeout();
                    return true;
                case MSG_SWITCH_LINE_PLAY_TIMEOUT:
                    handleSwitchLinePlayTimeout();
                    return true;
                case MSG_DROP_SESSION_AFTER_COMPLETED:
                    handlePendingCompletionDrop();
                    return true;
                default:
                    return false;
            }
        }
    });

    /** 解析/嗅探调度(见 PlayUrlResolver) */
    private final PlayUrlResolver resolver = new PlayUrlResolver(new PlayUrlResolver.Host() {
        @Override
        public PlaybackViewBridge view() {
            return PlaybackController.this.view;
        }

        @Override
        public SourceBean sourceBean() {
            return PlaybackController.this.sourceBean();
        }

        @Override
        public HashMap<String, String> webHeaderMap() {
            return PlaybackController.this.webHeaderMap;
        }

        @Override
        public void setWebHeaderMap(HashMap<String, String> headers) {
            PlaybackController.this.webHeaderMap = headers;
        }

        @Override
        public String webUserAgent() {
            return PlaybackController.this.webUserAgent;
        }

        @Override
        public void setWebUserAgent(String userAgent) {
            PlaybackController.this.webUserAgent = userAgent;
        }

        @Override
        public void playUrl(String url, HashMap<String, String> headers) {
            PlaybackController.this.playUrl(url, headers);
        }

        @Override
        public void playUrl(int gen, String url, HashMap<String, String> headers) {
            PlaybackController.this.playUrl(gen, url, headers);
        }
    });

    /** 尝试/换线/解码/会话标记状态(见 PlaybackAttemptState) */
    private final PlaybackAttemptState st = new PlaybackAttemptState();

    // -------------------- 状态开关(供页面在既有流程点调用) --------------------

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记(内容边界标记仍在调用方) */
    public void beginNewPlay() {
        st.beginNewPlay();
        // 新内容开始 ⇒ 上一条"播完待撤会话"的判定作废(否则那条迟到的消息会打到本次新会话上)
        timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
        // 换内容(换集/换线/换源/重播)⇒ 上一次确认的"纯音频"作废,由新内容自己重新确认
        // (自动重试不走本方法,见 retryAfterStartedError:同一内容的确认必须留着)
        st.audioOnlyConfirmed = false;
    }

    /** 换源点击即停:清"播放中"标记与复用开关,并置"在途结果作废"标记(下一次 play 清除) */
    public void markStoppedForSourceSwitch() {
        st.stoppedForSourceSwitch();
    }

    /** 取出并复位"复用播放器"意图 */
    public boolean consumeReusePlayerOnSwitch() {
        return st.consumeReuseIntent();
    }

    public void setReusePlayerOnSwitch(boolean reuse) {
        st.setReuseIntent(reuse);
    }

    public void setReleasePlayerOnSwitch(boolean release) {
        st.setReleaseIntent(release);
    }

    /** 切集/换线:清空"已尝试线路" */
    public void clearTriedLines() {
        st.clearTriedLines();
    }

    public void setUserPickedLine(boolean picked) {
        st.userPickedLine = picked;
    }

    public void setAllowSwitchPlayer(boolean allow) {
        st.allowSwitchPlayer = allow;
        if (!allow) {
            // 用户手动切内核:自动切内核态作废 —— ①用户的选择要能落库(playerCfgForPersist 不再回填原值)
            // ②后续换线也不再自动回滚成"自动切换前的内核"(用户的选择优先)。
            // ⚠️ 调用方必须在 updatePlayerCfg()(落库)**之前**调用本方法,否则本次落库仍会带回填值。
            st.autoSwitchedPlayerType = -1;
        }
    }

    /**
     * 用户手动选过解码方式(播放器解码按钮):本次播放不再自动回退软解,且"自动软解"态作废 ——
     * 后者是为了让用户显式选的值能正常落进播放记录(见 {@link #playerCfgForPersist()})。
     */
    public void setAllowDecodeFallback(boolean allow) {
        if (allow) return;
        st.hasAutoSwitchedDecode = true;
        st.autoSwitchedDecodeOld = null;
    }

    /**
     * 落库用的播放器配置快照:**自动容错态不得进入播放记录**。
     *
     * <p>自动切内核(pl)与自动切软解(ijk)都只是本次会话的临时回退,但覆盖层任一设置改动都会经
     * {@code PlayContainer.updatePlayerCfg()} 把当时的 playerCfg 整体写进记录/发 EventBus ——
     * 于是临时回退变成"按剧记忆",把用户的设置永久顶掉。这里返回剔除自动态后的**副本**,
     * 内存中的 {@link #playerCfg()} 不受影响(播放仍按自动态跑)。
     */
    @Nullable
    public JSONObject playerCfgForPersist() {
        if (playerCfg == null) return null;
        try {
            JSONObject copy = new JSONObject(playerCfg.toString());
            if (st.autoSwitchedPlayerType >= 0) {
                copy.put("pl", st.autoSwitchedPlayerType);
            }
            // 只看"自动态是否仍在生效"(autoSwitchedDecodeOld),不看每次播放的阻断标记 hasAutoSwitchedDecode ——
            // 后者会被 beginNewPlay(换集)复位,若一并作为条件,换集后下一次落库就会把自动软解写进记录
            if (st.autoSwitchedDecodeOld != null) {
                copy.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld);
            }
            return copy;
        } catch (Throwable th) {
            return playerCfg;
        }
    }

    /**
     * 未按剧锁定时,让 cfg 的解码键跟随全局设置(换集入口调用)。
     *
     * <p>背景:cfg 里的 "ijk"/"exo" 是**会话开始时**由 {@link #initPlayerCfg()} 从全局写下的副本;
     * 用户在换集期间去设置页改了解码方式,不刷新的话要等下一部片才生效(IJK 侧因有"推给存活内核"
     * 反而会立刻生效,两个内核行为还不一致)。这里在换集入口重写一次,让两边都按最新设置起播。
     *
     * <p>两种不能刷新:①按剧锁定(ijkSet / exoSet == 1,用户在该内核下显式选过);②**自动软解态**
     * (autoSwitchedDecodeOld != null)是本次会话的回退结果,用全局值顶掉就等于把回退作废 ——
     * 只跳过被回退改过的那个键,另一个键照常跟随全局。
     */
    private void syncDecodeFromGlobal() {
        if (playerCfg == null) return;
        boolean autoIjk = st.autoSwitchedDecodeOld != null && "ijk".equals(st.autoSwitchedDecodeKey);
        boolean autoExo = st.autoSwitchedDecodeOld != null && "exo".equals(st.autoSwitchedDecodeKey);
        try {
            if (playerCfg.optInt("ijkSet", 0) == 0 && !autoIjk) {
                playerCfg.put("ijk", KV.get(HawkConfig.IJK_CODEC, "硬解码")); // i18n: keep
            }
            if (playerCfg.optInt("exoSet", 0) == 0 && !autoExo) {
                playerCfg.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")); // i18n: keep
            }
        } catch (Throwable th) {
            // 与 initPlayerCfg 一致:刷新失败不阻断播放
            LOG.d("PlaybackController", "syncDecodeFromGlobal failed, keep current cfg");
        }
    }

    /** 用户自救(重播/切解析/切内核/切解码)后:允许再兜一次底 */
    public void resetAutoRetryState() {
        st.userSelfRescue();
    }

    public void setPlaybackStarted(boolean started) {
        st.playbackStarted = started;
    }

    public void setPlayTimeoutBasePosition(long position) {
        st.playTimeoutBasePosition = position;
    }

    /** 取流起播的基准位置(跳播/转圈判定用) */
    public long playTimeoutBasePosition() {
        return st.playTimeoutBasePosition;
    }

    /** 是否首次取流(autoRetryCount==0):只有首次才记录 webPlayUrl 作为重播地址 */
    public boolean isFirstAttempt() {
        return st.autoRetryCount == 0;
    }

    public boolean isStartedPlayState(int state) {
        return state == VideoView.STATE_PREPARED || state == VideoView.STATE_BUFFERED || state == VideoView.STATE_PLAYING;
    }

    public void markPlaybackStarted() {
        st.playbackStarted = true;
        cancelPlayTimeout();
    }

    public boolean isPlaybackStarted() {
        if (st.playbackStarted) return true;
        if (view == null) return false;
        return isStartedPlayState(view.currentPlayState()) || hasPlaybackProgress(view.currentPosition()) || view.isPlaying();
    }

    private boolean hasPlaybackProgress(long progress) {
        return progress > Math.max(st.playTimeoutBasePosition, 0) + 1000;
    }

    // -------------------- 三处超时 --------------------

    public void startResolvePlayUrlTimeout() {
        cancelPlayTimeout();
        timeoutHandler.sendEmptyMessageDelayed(MSG_RESOLVE_PLAY_URL_TIMEOUT, getResolvePlayUrlTimeoutMs());
    }

    private long getResolvePlayUrlTimeoutMs() {
        if (sourceBean() == null) return RESOLVE_PLAY_URL_TIMEOUT_MS;
        return Math.max(RESOLVE_PLAY_URL_TIMEOUT_MS, (sourceBean().getPlayTimeoutSeconds() + 1L) * 1000L);
    }

    public void startSwitchLinePlayTimeout() {
        if (!st.allowAutoSwitchLine) {
            cancelPlayTimeout();
            return;
        }
        cancelPlayTimeout();
        LOG.i("echo-switchLinePlay start timeout");
        timeoutHandler.sendEmptyMessageDelayed(MSG_SWITCH_LINE_PLAY_TIMEOUT, SWITCH_LINE_PLAY_TIMEOUT_MS);
    }

    public void cancelSwitchLinePlayTimeout() {
        cancelPlayTimeout();
    }

    public void cancelPlayTimeout() {
        timeoutHandler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT);
        timeoutHandler.removeMessages(MSG_SWITCH_LINE_PLAY_TIMEOUT);
    }

    /** 只取消"取流超时"(取流结果已到达时;换线播放超时另计,不能一起取消) */
    public void cancelResolvePlayUrlTimeout() {
        timeoutHandler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT);
    }

    /** 预览态启用/全屏禁用自动换线(全屏时用户在看画面,不该被换线打断) */
    public void setAutoSwitchLineEnabled(boolean enabled) {
        // 值未变就直接返回:页面每次进入/重进都会下发一遍,重复的"禁用"不能再去动在途取流超时与换线记录
        if (st.allowAutoSwitchLine == enabled) return;
        st.allowAutoSwitchLine = enabled;
        if (!enabled) {
            cancelPlayTimeout();
            st.clearTriedLines();
        }
    }

    // -------------------- 重试与换线 --------------------

    /** 自动重试回滚:把"自动切成别的内核"还原成用户配置(只改内存态 + 通知 UI) */
    private void restoreAutoSwitchedPlayer() {
        if (st.autoSwitchedPlayerType < 0) return;
        st.releasePlayerOnSwitch = true;
        try {
            LOG.i("echo-autoRetry restore player: " + playerCfg().optInt("pl", -1) + " -> " + st.autoSwitchedPlayerType);
            playerCfg().put("pl", st.autoSwitchedPlayerType);
            if (view != null) view.applyPlayerConfig(playerCfg());
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            st.autoSwitchedPlayerType = -1;
        }
    }

    /**
     * 自动切"硬解→软解"的回滚(与 restoreAutoSwitchedPlayer 同语义):把 cfg.ijk 还原成用户设置的值(只改内存)。
     *
     * <p>⚠️ 判定只看 {@code autoSwitchedDecodeOld}(确有"自动软解"可回滚),**不能**看
     * {@code hasAutoSwitchedDecode} —— 后者同时兼任"用户显式选过解码 ⇒ 本次播放不再自动回退"的**阻断标记**
     * (见 {@link #setAllowDecodeFallback(boolean)},此时 autoSwitchedDecodeOld 为 null);按它判定会在
     * 换线/超时等失败路径上把用户的阻断一并清掉,此后自动软解又会把用户显式选的硬解顶掉。
     */
    private void restoreAutoSwitchedDecode() {
        if (st.autoSwitchedDecodeOld == null) return;
        st.hasAutoSwitchedDecode = false;
        try {
            if (playerCfg != null) {
                LOG.i("echo-autoRetry restore decode: " + playerCfg.optString(st.autoSwitchedDecodeKey, "") + " -> " + st.autoSwitchedDecodeOld);
                playerCfg.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld);
                // 与 restoreAutoSwitchedPlayer 同款:同步覆盖层 UI(解码按钮文案/状态读的是 cfg)
                if (view != null) view.applyPlayerConfig(playerCfg);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            st.autoSwitchedDecodeOld = null;
        }
    }

    /** 当前实际生效的播放内核(1=IJK / 2=EXO):按存活内核实例判断(与 currentTrackInfo 同款取法), 拿不到时回落 cfg.pl */
    private int liveKernel() {
        try {
            AbstractPlayer live = (view == null) ? null : view.mediaPlayer();
            if (live instanceof IjkMediaPlayer) return 1;
            if (live instanceof ExoPlayer) return 2;
        } catch (Throwable ignored) {
            LOG.d("PlaybackController", "live kernel probe failed, fallback cfg.pl");
        }
        return playerCfg == null ? 2 : playerCfg.optInt("pl", 2);
    }

    /**
     * 起播失败的"硬解→软解"回退(每次播放最多触发一次,软解态在本次会话内持续)。
     *
     * <p>为什么必须做在**内核内部**而不是靠阶梯里的"切内核到 EXO":设备硬解不了的格式(HEVC 10bit/高 profile、
     * 老设备 AV1 等)最有效的兜底是软解 —— IJK 自带 ffmpeg 软解;EXO 走系统软件解码器(c2.android.*)。
     * 而阶梯里的"切内核"另一侧同样是 MediaCodec 硬解,命中率低。
     *
     * <p>两个内核共用本方法,改动的是各自的解码键:IJK 改 {@code cfg.ijk},EXO 改 {@code cfg.exo}
     * (EXO 侧由 PlayerHelper 下发到 media3 的视频解码选择器,见 ExoPlayer.EXO_VIDEO_CODEC_SELECTOR)。
     *
     * <p>与"自动切内核"同语义:只改本次会话的内存配置({@code playerCfg}),**不落播放记录**
     * (落库侧由 {@link #playerCfgForPersist()} 兜底剔除),换线时由 {@link #restoreAutoSwitchedDecode()} 回滚,
     * 用户显式点解码按钮时由 {@link #setAllowDecodeFallback(boolean)} 作废。换集不还原(会话内持续用软解,
     * 与自动切内核一致),但阻断标记会随 {@link #beginNewPlay()} 复位,即新一集仍可获得一次回退机会。
     */
    private boolean trySoftDecodeFallback() {
        if (st.hasAutoSwitchedDecode || playerCfg == null) return false;
        // 生效内核按**存活内核**判断:cfg.pl 与实际内核可能不一致 —— DASH 源会强制 EXO
        // (goPlayUrl → applyPlayerConfigToView(2))、rtmp 会在 MyVideoView.setUrl 里强制 IJK,
        // 按 cfg.pl 判定会把"软解"写到另一侧的键上,回退等于没生效
        int kernel = liveKernel();
        if (kernel != 1 && kernel != 2) return false;                        // 只有 IJK / EXO 内核才有软解路径
        String decodeKey = (kernel == 1) ? "ijk" : "exo";
        if (!"硬解码".equals(playerCfg.optString(decodeKey, ""))) return false; // i18n: keep —— 已经是软解,不再回退
        if (TextUtils.isEmpty(webPlayUrl)) return false;                      // 没拿到可播地址(解析/嗅探失败)不适用
        String oldDecode = playerCfg.optString(decodeKey, "");
        try {
            playerCfg.put(decodeKey, "软解码"); // i18n: keep
        } catch (Throwable th) {
            return false;
        }
        LOG.i("echo-autoRetry hard->soft decode: kernel=" + kernel + " " + webPlayUrl);
        st.autoSwitchedDecodeOld = oldDecode;
        st.autoSwitchedDecodeKey = decodeKey;
        st.hasAutoSwitchedDecode = true;
        // 覆盖层"解码"按钮的文案读的是 cfg,这里同步一次(与自动切内核一致,见 restoreAutoSwitchedPlayer)
        if (view != null) view.applyPlayerConfig(playerCfg);
        stopParse();
        initParseLoadFound();
        if (view != null && view.isPageAlive()) {
            final PlaybackViewBridge aliveView = view;
            view.runOnUi(() -> aliveView.toast(str(R.string.player_decode_fallback_tip)));
        }
        if (view != null) view.releasePlayer();
        if (view != null) playUrl(webPlayUrl, webHeaderMap);
        return true;
    }

    /**
     * 起播后(已 PREPARED/PLAYING 过)报错的兜底:此前 errorWithRetry 对这类错误静默吞掉 ——
     * 无提示、不重试,表现为"黑屏死在那"(源上游不稳,播放中重拉 m3u8
     * 播放列表拿到网关 HTML 错误页)。这里自动"同内核同地址重播"一次;已试过或无可播地址时
     * 返回 false,由页面给可见提示。
     *
     * <p>复位点:{@link #beginNewPlay()}(换集/换线/换源)与 {@link #resetAutoRetryState()}
     * (手动重播/切内核/切解码/换解析)—— 用户的主动自救动作之后允许再兜一次底。
     */
    public boolean retryAfterStartedError() {
        if (st.hasRetriedAfterStart) return false;
        if (TextUtils.isEmpty(webPlayUrl)) return false;
        st.hasRetriedAfterStart = true;
        LOG.i("echo-autoRetry retry after started error: " + webPlayUrl);
        if (view != null && view.isPageAlive()) {
            final PlaybackViewBridge aliveView = view;
            view.runOnUi(() -> aliveView.toast(str(R.string.player_play_error_retry)));
        }
        stopParse();
        initParseLoadFound();
        // 复位"已起播"标记:重播若在起播前就再次失败,后续 errorWithRetry 应走 autoRetry 阶梯
        // (切内核/换线)而不是再次落入 started=true 的兜底分支(与 PlayContainer.replay 的复位一致)
        st.playbackStarted = false;
        if (view != null) view.releasePlayer();
        if (view != null) playUrl(webPlayUrl, webHeaderMap);
        return true;
    }

    /**
     * 自动重试(播放出错/超时后):依次尝试 ①嗅探到的新地址 ②硬解→软解重播当前地址(仅 IJK 硬解,每次播放一次)
     * ③切换播放内核重播当前地址 ④下一条线路。
     *
     * @return true = 已发起重试;false = 无路可走(调用方负责提示与收尾)
     */
    public boolean autoRetry() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - st.lastRetryTime > 60_000) {
            LOG.i("echo-reset-autoRetryCount");
            st.resetAutoRetryLadder();
        }
        st.lastRetryTime = currentTime;
        // ConcurrentLinkedQueue.size() 是 O(n) 遍历且弱一致(并发 add 时可能读到 0);isEmpty() 为 O(1) 且更准确
        if (resolver.hasFoundUrls()) {
            resolver.consumeFoundUrl();
            return true;
        }
        // ② 硬解→软解:解码类起播失败覆盖面最广的兜底,排在换内核之前(先保住用户选的内核)
        if (trySoftDecodeFallback()) return true;
        if (webPlayUrl != null) {
            if (st.allowSwitchPlayer && !st.hasAutoSwitchedPlayer) {
                LOG.i("echo-autoRetry switch player and replay current url");
                int playerType = playerCfg().optInt("pl", -1);
                boolean switchSkipped = view != null && view.switchPlayerKernel();
                st.hasAutoSwitchedPlayer = true;
                st.allowSwitchPlayer = false;
                if (!switchSkipped) {
                    st.autoSwitchedPlayerType = playerType;
                    stopParse();
                    initParseLoadFound();
                    if (view != null) view.releasePlayer();
                    if (view != null) playUrl(webPlayUrl, webHeaderMap);
                    return true;
                }
            }
            LOG.i("echo-autoRetry current url failed after player switch, try next line");
            return tryNextLineIfEnabled();
        }
        return tryNextLineIfEnabled();
    }

    /** 自动换线开关判断(已在尝试换线时先回滚内核与解码方式) */
    public boolean tryNextLineIfEnabled() {
        restoreAutoSwitchedPlayer();
        restoreAutoSwitchedDecode();
        if (st.allowAutoSwitchLine && KV.get(HawkConfig.AUTO_SWITCH_LINE, false)) return tryNextLine();
        LOG.i("echo-autoRetry line switching disabled");
        st.resetAutoRetryLadder();
        return false;
    }

    /** 切到"下一条未尝试过且有剧集"的线路,集号按集名匹配(换线不换集) */
    public boolean tryNextLine() {
        if (vod() == null || vod().seriesMap == null || vod().seriesMap.isEmpty()) {
            st.linesExhausted();
            return false;
        }
        String currentFlag = vod().playFlag;
        int currentIndex = Math.max(vod().playIndex, 0);
        VodInfo.VodSeries currentSeries = currentSeries(currentFlag, currentIndex);
        if (!TextUtils.isEmpty(currentFlag)) {
            st.triedLineFlags.add(currentFlag);
        }
        List<String> lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod());
        int currentLineIndex = EpisodeMatcher.lineFlagIndex(lineFlags, currentFlag);
        int startLineIndex = currentLineIndex >= 0 ? currentLineIndex + 1 : 0;
        String nextFlag = null;
        int nextIndex = 0;
        for (int i = startLineIndex; i < lineFlags.size(); i++) {
            String flag = lineFlags.get(i);
            List<VodInfo.VodSeries> seriesList = vod().seriesMap.get(flag);
            if (!st.triedLineFlags.contains(flag) && seriesList != null && !seriesList.isEmpty()) {
                nextFlag = flag;
                nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, seriesList, currentIndex);
                break;
            }
        }
        if (nextFlag == null) {
            LOG.i("echo-autoRetry all lines exhausted");
            st.linesExhausted();
            return view != null && view.onLinesExhausted();
        }
        final String flagToSwitch = nextFlag;
        final String preProgressKey = progressKey();
        final long savedProgress = TextUtils.isEmpty(preProgressKey) ? 0 : getSavedProgress(preProgressKey);
        final long preProgress = Math.max(savedProgress, view == null ? 0 : view.currentPosition());
        LOG.i("echo-autoRetry switch line: " + vod().playFlag + " -> " + flagToSwitch);
        if (view != null && view.isPageAlive()) {
            view.runOnUi(() -> view.toast(str(R.string.player_switch_line, flagToSwitch)));
        }
        vod().playFlag = flagToSwitch;
        vod().playIndex = nextIndex;
        st.onLineSwitched();
        inheritProgressFrom(preProgressKey, preProgress);
        play(false);
        return true;
    }

    // -------------------- 超时/失败处理 --------------------

    public void handleResolvePlayUrlTimeout() {
        LOG.i("echo-resolvePlayUrl timeout, try next line");
        cancelPlayRequest();
        stopParse();
        if (st.userPickedLine) {
            st.userPickedLine = false;
            stopMusicSessionForFailedPlayback();
            showErrorTip(str(R.string.player_get_url_timeout));
            return;
        }
        if (!tryNextLineIfEnabled()) {
            stopMusicSessionForFailedPlayback();
            showErrorTip(str(R.string.player_get_url_timeout));
        }
    }

    public void handleResolvePlayUrlFailed(String err) {
        LOG.i("echo-resolvePlayUrl failed, try next line: " + err);
        cancelPlayRequest();
        stopParse();
        if (st.userPickedLine) {
            st.userPickedLine = false;
            cancelPlayTimeout();
            stopMusicSessionForFailedPlayback();
            showErrorTip(err);
            return;
        }
        if (tryNextLineIfEnabled()) return;
        cancelPlayTimeout();
        stopMusicSessionForFailedPlayback();
        showErrorTip(err);
    }

    public void handleSwitchLinePlayTimeout() {
        int state = view == null ? -1 : view.currentPlayState();
        LOG.i("echo-switchLinePlay timeout state: " + state + ", started: " + st.playbackStarted);
        if (isPlaybackStarted()) {
            cancelPlayTimeout();
            if (view != null) view.hideTipOnUiThread();
            return;
        }
        LOG.i("echo-switchLinePlay timeout, try next line");
        stopParse();
        if (st.hasAutoSwitchedPlayer) {
            if (!tryNextLineIfEnabled()) {
                stopMusicSessionForFailedPlayback();
                showErrorTip(str(R.string.player_play_timeout));
            }
            return;
        }
        if (!autoRetry()) {
            stopMusicSessionForFailedPlayback();
            showErrorTip(str(R.string.player_play_timeout));
        }
    }


    private void showErrorTip(String err) {
        if (view != null) view.showTip(err, false, true);
    }

    // ==================== 取流状态与结果观察者 ====================


    /** 已解析出的可播地址与请求头(重试/换内核重播用) */
    private String webPlayUrl;
    private HashMap<String, String> webHeaderMap;
    private String webUserAgent;


    /**
     * 最近一次**通过校验**的起播请求所属的代际(仅主线程读写):{@link #goPlayUrl} 入口签发,
     * 该方法的 UI 落地闭包用它比对 —— 排队期(回调 → runOnUi)若换了集,排队中的旧地址会被丢弃。
     *
     * <p>签发点必须在 {@code goPlayUrl} 入口(不能用"解析产物入口"的字段串):M3U8 净化结果是主线程
     * 直接进 {@code goPlayUrl} 的,否则会带着过期字段被误判为陈旧。
     */
    private int playUrlGeneration;

    private SourceViewModel sourceViewModel;
    private Observer<JSONObject> playResultObserver;

    /** 当前会话(页面 setData 交进来的那一份;D6 接管与"已起播内容"判定都基于它) */
    private PlaybackSession currentSession;
    /**
     * 最近一次**真正把内容交给播放器**的会话归属键(D6 接管的唯一可信依据)。
     *
     * <p>与"会话"区分开:`startSession` 只是登记要播什么,取流可能失败、也可能被外部播放器接走 ——
     * 那些情况下播放器里的内容**不属于**该会话,D6 必须拒绝接管(真机 bug:点播页播着直播)。
     */
    private String startedPlaybackKey;

    /** 内容真正起播(地址交给播放器)时调用:记录归属,供 D6 接管判定 */
    public void markContentStarted() {
        startedPlaybackKey = currentSession == null ? null : currentSession.playbackKey();
    }

    /** 内容不再属于当前会话(直播接管等):清空归属标记 */
    public void clearStartedContent() {
        startedPlaybackKey = null;
    }

    @Nullable
    public String startedPlaybackKey() {
        return startedPlaybackKey;
    }

    /** 建立取流结果观察者(预载协调器仍归页面) */
    public void initFetch() {
        sourceViewModel = new SourceViewModel();
        playResultObserver = new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject info) {
                if (info == null) publishQuality(null);
                if (info != null) {
                    try {
                        if (isStalePlayResult(info)) {
                            LOG.i("echo-ignore stale play result");
                            return;
                        }
                        if (view != null && st.switchStopPending) {
                            // 换源点击即停后,旧源在途的取流结果不得再拉起播放
                            LOG.i("echo-ignore play result while source switching");
                            return;
                        }
                        cancelResolvePlayUrlTimeout();
                        publishQuality(info);
                        webPlayUrl = null;
                        setProgressKey(info.optString("proKey", null));
                        boolean parse = info.optString("parse", "1").equals("1");
                        boolean jx = info.optString("jx", "0").equals("1");
                        setPlaySubtitle(info.optString("subt", ""));
                        setPlayLyric(info.optString("lyric", ""));
                        setLyricCacheKey(info.optString("lyricKey", null));
                        if (TextUtils.isEmpty(lyricCacheKey()) && !TextUtils.isEmpty(progressKey())) {
                            setLyricCacheKey(progressKey() + "-lyric");
                        }
                        JSONArray lyrics = info.optJSONArray("lyrics");
                        if (lyrics != null && lyrics.length() > 0) {
                            setPlayLyric(getSubtitleUrl(lyrics.optJSONObject(0)));
                        }
                        JSONArray subtitles = info.optJSONArray("subs");
                        if (subtitles != null) {
                            for (int i = 0; i < subtitles.length(); i++) {
                                JSONObject obj = subtitles.optJSONObject(i);
                                if (obj == null) continue;
                                String url = getSubtitleUrl(obj);
                                String name = obj.optString("name", "");
                                if (isLyricSubtitle(name)) {
                                    if (TextUtils.isEmpty(playLyric())) setPlayLyric(url);
                                } else if (TextUtils.isEmpty(playSubtitle())) {
                                    setPlaySubtitle(url);
                                }
                            }
                        }
                        setSubtitleCacheKey(info.optString("subtKey", null));
                        String lyricPick = playLyric();
                        LOG.i("echo-lyric pick: " + (TextUtils.isEmpty(lyricPick) ? "none"
                                : lyricPick.startsWith("data:") ? "inline len=" + lyricPick.length() : lyricPick));
                        String playUrl = info.optString("playUrl", "");
                        String flag = info.optString("flag");
                        Object rawUrl = info.opt("url");
                        String url = rawUrl instanceof JSONArray ? rawUrl.toString() : String.valueOf(rawUrl);
                        if (url.startsWith("[") && view != null) {
                            url = view.firstUrlByArray(url);
                        }
                        // 音乐源取流结果的封面字段常是 cover 而不是 artwork;漏读会让换集后海报不刷新
                        String artwork = info.optString("artwork", "");
                        if (TextUtils.isEmpty(artwork)) artwork = info.optString("cover", "");
                        if (TextUtils.isEmpty(artwork) && !TextUtils.isEmpty(playLyric()) && vod() != null) {
                            artwork = vod().pic;
                        }
                        currentArtwork = artwork;
                        if (view != null) view.setArtwork(artwork);
                        String msg = info.optString("msg", "");
                        if (!TextUtils.isEmpty(msg)) {
                            handleResolvePlayUrlFailed(msg);
                            return;
                        }
                        // 取流成功,手动选线标记完成使命,后续失败恢复走正常自动策略
                        st.userPickedLine = false;
                        String danmaku = info.optString("danmaku", "").trim();
                        final String danmuProgressKey = progressKey();
                        setWebUserAgent(null);
                        setWebHeaderMap(null);
                        HashMap<String, String> headers = extractHeaders(info);
                        if (headers != null) {
                            setWebHeaderMap(headers);
                            String ua = headerValue(headers, "user-agent");
                            setWebUserAgent(ua == null ? null : ua.trim());
                        }
                        if (parse || jx) {
                            boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                            initParse(flag, userJxList, playUrl, url);
                        } else {
                            if (view != null) view.showParse(false);
                            if (view != null) playUrl(playUrl + url, headers);
                        }
                        if (TextUtils.isEmpty(danmaku)) {
                            checkDanmu("", null);
                            searchDanmu("");
                        } else {
                            checkDanmu(danmaku, () -> {
                                if (TextUtils.equals(danmuProgressKey, progressKey())) {
                                    searchDanmu("");
                                }
                            });
                        }
                    } catch (Throwable th) {
                        handleResolvePlayUrlFailed(str(R.string.player_get_info_error));
                    }
                } else {
                    // 获取播放信息错误后只需再重试一次
                    handleResolvePlayUrlFailed(str(R.string.player_get_info_error));
                }
            }
        };
        sourceViewModel.playResult.observeForever(playResultObserver);
    }

    /** 页面销毁时注销观察者(对应原 hostDestroy 的 removeObserver) */
    public void releaseFetch() {
        if (sourceViewModel != null && playResultObserver != null) {
            sourceViewModel.playResult.removeObserver(playResultObserver);
            playResultObserver = null;
        }
    }

    /** 页面侧预载协调器需要它取流(PreloadCoordinator 构造参数) */
    /** 把“已准备好的取流结果”直接喂给解析链(页面 play() 命中预载数据时调用) */
    public void deliverPlayResult(JSONObject info) {
        if (playResultObserver != null) playResultObserver.onChanged(info);
    }

    public SourceViewModel sourceViewModel() {
        return sourceViewModel;
    }

    /** 取消在途取流请求 */
    public void cancelPlayRequest() {
        if (sourceViewModel != null) sourceViewModel.cancelPlayRequest();
    }

    @Nullable
    public String webPlayUrl() {
        return webPlayUrl;
    }

    public void setWebPlayUrl(String webPlayUrl) {
        this.webPlayUrl = webPlayUrl;
    }

    @Nullable
    public HashMap<String, String> webHeaderMap() {
        return webHeaderMap;
    }

    public void setWebHeaderMap(HashMap<String, String> webHeaderMap) {
        this.webHeaderMap = webHeaderMap;
    }

    @Nullable
    public String webUserAgent() {
        return webUserAgent;
    }

    public void setWebUserAgent(String webUserAgent) {
        this.webUserAgent = webUserAgent;
    }

    // -------------------- 字幕/歌词地址与弹幕搜索 --------------------

    private String getSubtitleUrl(JSONObject object) {
        if (object == null) return "";
        String format = object.optString("format", "");
        String name = object.optString("name", str(R.string.player_menu_subtitle));
        String ext = ".srt";
        if ("text/x-ssa".equals(format)) {
            ext = ".ass";
        } else if ("text/vtt".equals(format)) {
            ext = ".vtt";
        } else if ("text/lrc".equals(format)) {
            ext = ".lrc";
        }
        String filename = name + (name.toLowerCase(Locale.ROOT).endsWith(ext) ? "" : ext);
        String url = object.optString("url", "");
        String data = object.optString("data", "");
        // 本地代理 URL 要靠爬虫的内存态现取,拿不到就整段没有字幕/歌词;同一份内容已在 data 里时直接用
        if (!TextUtils.isEmpty(data) && (TextUtils.isEmpty(url) || PlayerHelper.isLocalProxyUrl(url))) {
            url = "data:text/plain;base64," + Base64.encodeToString(data.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            // data: URI 的文件名只能靠 fragment 带(内容里出现的点会让 hasExtension 误判)
            return view == null ? url : url + "#" + view.encodeUrl(filename);
        }
        if (TextUtils.isEmpty(url) || FileUtils.hasExtension(url)) return url;
        return view == null ? url : url + "#" + view.encodeUrl(filename);
    }

    private boolean isLyricSubtitle(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("lyric") || value.contains("lrc") || name.contains("歌词"); // i18n: keep
    }

    /** 取流结果没带弹幕地址时联网搜一份(与进度键绑定:切集后旧结果作废) */
    private void searchDanmu(String danmaku) {
        if (!TextUtils.isEmpty(danmaku) || !DanmakuApi.canSearch(sourceBean()) || vod() == null) return;
        VodInfo.VodSeries series = currentSeries(vod().playFlag, vod().playIndex);
        String key = progressKey();
        DanmakuApi.search(vod().name, series == null ? "" : series.name, new DanmakuApi.SearchCallback() {
            @Override
            public void onFound(String url) {
                if (!TextUtils.equals(key, progressKey())) return;
                checkDanmu(url, null);
            }

            @Override
            public void onNotFound() {
                if (!TextUtils.equals(key, progressKey())) return;
                checkDanmu("", null);
            }
        });
    }

    private void checkDanmu(String danmaku, Runnable onFailed) {
        if (view != null) view.checkDanmu(danmaku, onFailed);
    }

    // -------------------- 解析/嗅探门面(见 PlayUrlResolver) --------------------

    /** 按解析规则发起解析(直链/json/聚合/超级解析) */
    public void initParse(String flag, boolean useParse, String playUrl, final String url) {
        resolver.initParse(flag, useParse, playUrl, url);
    }

    /** 解析入口 */
    public void doParse(ParseBean pb) {
        resolver.doParse(pb);
    }

    /** 停止解析/嗅探 */
    public void stopParse() {
        resolver.stopParse();
    }

    /** 重置嗅探结果容器 */
    public void initParseLoadFound() {
        resolver.initParseLoadFound();
    }

    /** 本轮解析/嗅探是否仍有效(代际闸门) */
    public boolean isParseResultCurrent(int gen) {
        return resolver.isParseResultCurrent(gen);
    }

    public void stopLoadWebView(boolean destroy) {
        resolver.stopLoadWebView(destroy);
    }


    @SuppressLint("SetJavaScriptEnabled")

    // ==================== 取流入口 ====================
    // play/playUrl/goPlayUrl 是"调度 → 视图"的分界线:决策(外部播放器、dash 强制 EXO、纯音频渲染、
    // 进度继承、预载命中)在调度层,真正操作 MyVideoView 的连招交给 view.startVideoPlayback(...)。


    public boolean isSwitchStopPending() {
        return st.switchStopPending;
    }

    /** 换源点击即停时记下"接着看"的进度(play 时写进新键) */
    public void setPendingInherit(String key, long progress) {
        st.pendingInheritKey = key;
        st.pendingInheritProgress = progress;
    }

    /**
     * 把当前会话的标题下发到视图(播放器顶栏 / 暂停浮层)。
     *
     * <p>单独抽成方法是因为 D6「同片接管」**不经过** {@link #play(boolean)} —— 播放器里已经是这一集,
     * 不再取流重播;而标题原先只在 play() 里下发,导致"退出详情页 → 重新进入同一部"顶栏标题为空
     * (顶栏标题为空的场景)。接管路径必须自己补一次。
     */
    public void publishTitle() {
        if (view == null || vod() == null) return;
        VodInfo.VodSeries vs = currentSeries(vod().playFlag, vod().playIndex);
        if (vs == null) return;
        view.setTitle(vod().name + " " + vs.name);
    }

    /**
     * 播放当前集的唯一入口(切集/换线/换源/重播都走它)。
     *
     * @param reset true = 清除已有进度从头发起(重播)
     */
    public void play(boolean reset) {
        // 新播放是用户显式请求(换源落地/回滚重播):解除换源停播抑制
        st.switchStopPending = false;
        // 入口即失效(见 parseGeneration):上一集的在途结果不得再拉起播放。必须在下面的 early return 之前
        resolver.nextGen();
        // 预载失效事件(切集/换线/换源/重播):作废在途预解析与预载数据,稳定播放后重新评估
        invalidatePreload();
        if (view != null) view.hidePreloadReadyTip();
        if (vod() == null) return;
        boolean reusePlayer = consumeReusePlayerOnSwitch();
        st.switchingPlayback = true;
        st.audioPlayback = false;
        if (view != null) {
            view.onNewPlayStarted();
            view.clearArtwork();
        }
        // 逐级判空 + 集号 clamp(与 goPlayUrl 同源防护):历史恢复的线路在
        // 当前源不存在、或源更新后集数变少时,裸链式取值会 NPE/IOOBE 直接崩在主线程;
        // 走失败链路(自动换线兜底)而不是崩溃
        VodInfo.VodSeries vs = currentSeries(vod().playFlag, vod().playIndex);
        if (vs == null) {
            handleResolvePlayUrlFailed(str(R.string.player_get_info_error));
            return;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, vod()));
        if (reusePlayer) {
            // 复用播放器时提示已由上一集留着,直接清空(与原实现一致:绕过 setTip 的页面存活判断)
            PlayerTipBridge.setTip("", true, false);
        } else if (view != null) {
            view.showTip(str(R.string.player_getting_info), true, false);
        }
        publishTitle();

        stopParse();
        beginNewPlay();
        syncDecodeFromGlobal();
        setWebPlayUrl(null);
        setWebHeaderMap(null);
        initParseLoadFound();

        if (view != null) {
            view.stopOtherPlayers();
            view.resetDanmu();
            view.clearLyric();
            if (reusePlayer) {
                long previousPosition = view.currentPosition();
                if (previousPosition > 0 && !TextUtils.isEmpty(progressKey())) {
                    WatchProgressStore.save(progressOwner(), progressKey(), previousPosition, view.duration());
                }
                view.clearVideoFrame();
            } else {
                view.releasePlayer();
            }
        }
        ImgUtil.clearMemoryCache();
        setSubtitleCacheKey(vod().sourceKey + "-" + vod().id + "-" + vod().playFlag + "-" + vod().playIndex + "-" + vs.name + "-subt");
        setProgressKey(vod().sourceKey + vod().id + vod().playFlag + vod().playIndex + vs.name);
        // 这一集是真的重新起播:删除时下的"作废"到此为止(否则用户重看一遍也不再记进度)
        WatchProgressStore.onPlayStart(progressKey());
        startResolvePlayUrlTimeout();
        // 换源点击即停前记下的进度:新源进度键不同,写进新键缓存接着看(新键已有历史记录则不覆盖);
        // 回滚原源时键相同,停播 release 已落盘,该方法会直接跳过
        if (st.pendingInheritProgress > 0 && !TextUtils.isEmpty(st.pendingInheritKey)) {
            inheritProgressFrom(st.pendingInheritKey, st.pendingInheritProgress);
            LOG.i("echo-switchSource inherit progress " + st.pendingInheritProgress + "ms from " + st.pendingInheritKey);
        }
        st.pendingInheritKey = null;
        st.pendingInheritProgress = 0;
        // 重新播放清除现有进度
        if (reset) {
            // 重播不消费待继承进度,留着会被下一次非重播播放写进别的集
            inheritProgressKey = null;
            inheritProgress = 0;
            WatchProgressStore.clear(progressOwner(), progressKey());
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey()), 0);
        } else {
            inheritProgressIfNeeded();
            try {
                int playerType = playerCfg().getInt("pl");
                if (view != null) view.setSubtitleViewVisible(playerType == 1);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (Jianpian.isJpUrl(vs.url)) {// 荐片地址特殊判断
            String jp_url = vs.url;
            if (view != null) view.showParse(false);
            if (vs.url.startsWith("tvbox-xg:")) {
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
            } else {
                playUrl(Jianpian.JPUrlDec(jp_url), null);
            }
            return;
        }
        // p2p 取流是异步回调(可能数十秒):同样带发起时的代际,切集后旧地址不得起播
        final int thunderGen = resolver.currentGen();
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (view != null) view.showTip(info, code >= 0, code < 0);
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(thunderGen, url, null);
            }
        })) {
            if (view != null) view.showParse(false);
            return;
        }

        if (preloadCoordinator != null) {
            JSONObject preResult = preloadCoordinator.consumeResult(progressKey());
            if (preResult != null) {
                deliverPlayResult(preResult);
                return;
            }
            // 未复用 = 切到的不是预载目标集(或缓存过期):预载数据失效,清掉
            preloadCoordinator.dropPreloadData();
        }
        if (sourceViewModel != null) {
            sourceViewModel.getPlay(sourceKey(), vod().playFlag, progressKey(), vs.url, subtitleCacheKey());
        }
    }

    /**
     * 解析/嗅探产物入口:入口校验挡"回调已跑起来"的旧结果(此时若已切集,连 RefreshEvent 播放地址与
     * 换线超时都不该被改写);{@link #goPlayUrl} 里那道校验挡"回调 → UI 线程排队"期间的切集。
     *
     * <p>自动重试/重播兜底/自动换线走的都是 2 参 {@link #playUrl}(与 goPlayUrl 同帧同代际)⇒ 不会误杀。
     */
    private void playUrl(int gen, String url, HashMap<String, String> headers) {
        if (!resolver.isParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale parse result");
            return;
        }
        playUrlGeneration = gen;
        playUrl(url, headers);
    }

    /** 取流结果入口:先按 M3U8 去广告规则分流,再交给 goPlayUrl 起播 */
    public void playUrl(String url, HashMap<String, String> headers) {
        startSwitchLinePlayTimeout();
        url = attachProxySiteKey(url);
        if (!url.startsWith("data:application")) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, url));//更新播放地址
        }
        if (!KV.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(url, headers);
            return;
        }
        if (url.startsWith("http://127.0.0.1") || !url.contains(".m3u8")) {
            goPlayUrl(url, headers);
            return;
        }
        if (vod() != null && DefaultConfig.noAd(vod().playFlag)) {
            goPlayUrl(url, headers);
            return;
        }
        LOG.i("echo-playM3u8:" + url);
        // 净化链是唯一不走 goPlayUrl 的起播路径(净化完成回调 startPlayUrl),起播前由页面桥校验代际
        if (view != null) view.playM3u8(url, headers, playUrlGeneration);
        // 净化期间先记下起点地址,否则净化源上 autoRetry/retryAfterStartedError 找不到可重播地址
        if (isFirstAttempt()) setWebPlayUrl(url);
    }

    /** 真正起播一个可播地址(外部播放器 / dash 强制 EXO / 复用播放器换集都在这里分流) */
    public void goPlayUrl(String url, HashMap<String, String> headers) {
        LOG.i("echo-goPlayUrl:" + url);
        if (TextUtils.isEmpty(url)) {
            handleResolvePlayUrlFailed(str(R.string.player_play_url_empty));
            return;
        }
        if (view == null || !view.isPageAlive()) return;
        // 调用方与解析回调同帧或同线程 ⇒ 本地址归属当前轮,在排队前签发代际(见 playUrlGeneration 注释)
        playUrlGeneration = resolver.currentGen();
        final String finalUrl = url;
        view.runOnUi(new Runnable() {
            @Override
            public void run() {
                if (st.switchStopPending) {
                    // 换源点击即停后,已排队的取流结果(含嗅探/解析回调)不得再拉起播放
                    LOG.i("echo-ignore goPlayUrl while source switching");
                    return;
                }
                if (playUrlGeneration != resolver.currentGen()) {
                    // 上一轮的产物(排队期已切集/换线/换源/重播)⇒ 丢弃,并撤掉旧链超时(否则到期会触发一次换线)
                    LOG.i("echo-ignore goPlayUrl of stale parse result");
                    resolver.cancelParseTimeout();
                    return;
                }
                // 地址在归属确认之后才记录,否则被丢弃的旧地址会留在 webPlayUrl 上被 autoRetry 拿去重播
                if (isFirstAttempt()) setWebPlayUrl(finalUrl);
                stopParse();
                if (view == null || finalUrl == null) return;
                String url = finalUrl;
                try {
                    int playerType = playerCfg().getInt("pl");
                    if (playerType >= 10) {
                        view.releasePlayer();
                        // 历史恢复的线路在当前源不存在、或换源与切集交错时,
                        // seriesMap 链式取值可能 NPE,逐级判空后回退仅用片名
                        List<VodInfo.VodSeries> series = (vod() == null || vod().seriesMap == null) ? null : vod().seriesMap.get(vod().playFlag);
                        VodInfo.VodSeries vs = (series == null || vod().playIndex < 0 || vod().playIndex >= series.size()) ? null : series.get(vod().playIndex);
                        String playTitle = vod().name + (vs == null ? "" : " " + vs.name);
                        view.showTip(str(R.string.player_call_external_play, PlayerHelper.getPlayerName(playerType)), true, false);
                        long progress = getSavedProgress(progressKey());
                        boolean callResult = view.playExternalPlayer(playerType, url, playTitle, playSubtitle(), headers, progress);
                        view.showTip(str(R.string.player_call_external_result, PlayerHelper.getPlayerName(playerType), callResult ? str(R.string.common_success) : str(R.string.common_failed)), callResult, !callResult);
                        return;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                setPlayTimeoutBasePosition(getSavedProgress(progressKey()));
                boolean forceExoPlayer = url.startsWith("data:application/dash+xml;base64,")
                        || url.contains(".mpd") || url.contains("type=mpd");
                if (url.startsWith("data:application/dash+xml;base64,")) {
                    view.applyPlayerConfigToView(2);
                    App.getInstance().setDashData(url.split("base64,")[1]);
                    url = ControlManager.get().getAddress(true) + "dash/proxy.mpd";
                } else if (url.contains(".mpd") || url.contains("type=mpd")) {
                    view.applyPlayerConfigToView(2);
                } else {
                    view.applyPlayerConfigToView(0);
                }
                // 纯音频 URL 预判:音乐直链没有视频帧,SurfaceView 渲染会"洞穿"应用窗口 ——
                // 任务快照里播放器区域变白、回前台透视桌面(详见 MyVideoView.switchRenderToTexture)。
                // 这里直接改用 TextureView 起播,补住「起播 → 轨道信息就绪」之间退后台的空窗;
                // 误判(音频后缀实为视频)无功能损失,TextureView 照常渲染画面。
                if (looksLikeAudioUrl(url)) {
                    view.useTextureRenderForAudio();
                }
                view.startVideoPlayback(url, headers, forceExoPlayer);
            }
        });
    }

    /** 本地代理地址补 siteKey(播放侧代理需要它定位源) */
    private String attachProxySiteKey(String url) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sourceKey())) return url;
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url;
        if (url.contains("siteKey=")) return url;
        try {
            return url + (url.contains("?") ? "&" : "?") + "siteKey=" + URLEncoder.encode(sourceKey(), "UTF-8");
        } catch (Throwable th) {
            return url + (url.contains("?") ? "&" : "?") + "siteKey=" + sourceKey();
        }
    }

    // ==================== 预载调度 ====================
    // 目标评估时机(正片稳定/缓冲让路/缓冲结束补枪)、结果取用与冷却期都在 PreloadCoordinator;
    // 调度层负责"何时喂快照、何时取结果、何时作废",页面只提供快照(需上下文与真实内核实例)与 Toast。

    private PreloadCoordinator preloadCoordinator;
    private PreloadManagerHolder.ReadyListener preloadReadyListener;

    /** 建立预载协调器与"下一集已就绪"回调(页面 init 时调用一次,须在 initFetch 之后) */
    public void initPreload() {
        if (sourceViewModel == null) initFetch();
        preloadCoordinator = new PreloadCoordinator(sourceViewModel);
        preloadReadyListener = new PreloadManagerHolder.ReadyListener() {
            @Override
            public void onPreloadReady(String url) {
                if (view == null || !view.isPageAlive()) return;
                view.runOnUi(() -> {
                    if (view != null) view.showPreloadReadyTip();
                });
            }
        };
        PreloadManagerHolder.setReadyListener(preloadReadyListener);
    }

    /**
     * 播放状态变化驱动预载评估(页面状态回调里调用):
     * STATE_PLAYING 正片稳定 → 延迟评估;STATE_BUFFERING 弱网 → 让路(清数据 + 冷却);
     * STATE_BUFFERED 缓冲结束 → 补一次评估(dkplayer 的 STATE_PLAYING 只在首帧发一次,不补枪则拖一次进度条就永久停摆)。
     */
    public void onPlayerStateForPreload(int playState) {
        if (preloadCoordinator == null) return;
        // 无页面(仅引擎)时快照为空:跳过评估(预载需要页面上下文与集信息)
        if (view == null) return;
        if (playState == VideoView.STATE_PLAYING || playState == VideoView.STATE_BUFFERED) {
            preloadCoordinator.scheduleEvaluate(view == null ? null : view.buildPreloadSnapshot());
        } else if (playState == VideoView.STATE_BUFFERING) {
            preloadCoordinator.onMainPlayerBuffering();
        }
    }

    /** 切集/换线/换源/重播:作废在途预解析与预载数据(稳定播放后重新评估) */
    public void invalidatePreload() {
        if (preloadCoordinator != null) preloadCoordinator.invalidate();
    }

    /** 页面销毁:停协调器 + 注销就绪回调(防页面销毁后回调/Toast 残留) */
    public void destroyPreload() {
        PreloadManagerHolder.clearReadyListener(preloadReadyListener);
        preloadReadyListener = null;
        if (preloadCoordinator != null) {
            preloadCoordinator.destroy();
            preloadCoordinator = null;
        }
    }

    // ==================== 音乐会话/媒体通知 ====================


    /**
     * 本集播完会自动续下一集时提前登记切换中(须在 {@link #play(boolean)} 之前调)。
     *
     * <p>⚠️ 事件同步派发且引擎监听先注册,故 COMPLETED 到达时 {@link #updateMusicSession} 先跑:
     * 此刻仍为 false 就会按"播完"撤掉会话与通知(表现:一首放完通知消失,下一首在放却没通知)。
     * 自然播完那条路径靠 {@link #handlePendingCompletionDrop} 延后一拍判定,本方法顺带撤销该消息。
     */
    public void beginSwitchPlayback() {
        st.switchingPlayback = true;
        timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
    }
    /** 纯音频封面地址(影视绝不设置:否则视频被压成海报) */
    private String playArtwork;
    /** 当前集的弹幕地址(取流结果或弹幕搜索的产物;退页面重进时页面要重新拿一份) */
    private String playDanmu;

    @Nullable
    public String playArtwork() {
        return playArtwork;
    }

    /** 取流结果里的封面(音乐页等"后挂载页面"读它拿封面;通知用的 {@link #playArtwork} 只允许纯音频) */
    private String currentArtwork;

    @Nullable
    public String currentArtwork() {
        return currentArtwork;
    }

    @Nullable
    public String playDanmu() {
        return playDanmu;
    }

    public void setPlayDanmu(String danmu) {
        this.playDanmu = danmu == null ? "" : danmu;
    }

    /**
     * 页面退出(返回上一级 / 回首页)的统一收尾:**"退页面即停"**(与 fongmi 默认语义一致)。
     *
     * <p>停播本身由引擎做(并且**保留播放器实例**,不 release,以保住"跨页不重建内核"的收益);
     * 这里负责把"还在跑的东西"收干净:撤在途取流与三处超时、清会话标记、停媒体会话(撤通知 + 放 wake/wifi 锁)。
     * 不做这些的话:退出页面后取流仍会继续并在后台起播(没声音才怪)、通知也不会消失。
     */
    /**
     * 撤掉所有在途动作:三处超时 + 取流请求 + 解析/嗅探(WebView 保留复用,销毁留给引擎释放)。
     *
     * <p>这是"共享调度层"唯一的在途收口,由**会话边界**({@link #startSession})与**停播**
     * ({@link #stopPlaybackForPageExit})共同调用 —— 页面销毁不再直接碰它(架构评审第 3 项)。
     */
    public void cancelInFlight() {
        cancelPlayTimeout();
        cancelSwitchLinePlayTimeout();
        cancelResolvePlayUrlTimeout();
        cancelPlayRequest();
        stopParse();
    }

    public void stopPlaybackForPageExit() {
        st.clearSessionFlags();
        // 与 onHostDestroy 同属会话边界:一并作废"播完待撤会话"的待判消息
        timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
        cancelInFlight();
        // 页面退出即"没有正在播的源"
        ApiConfig.get().setCurrentPlaySourceKey("");
        stopMusicSession();
    }

    /** 起播失败/换源点击即停:清会话标记并停掉通知 */
    public void stopMusicSessionForFailedPlayback() {
        st.clearSessionFlags();
        timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
        stopMusicSession();
    }

    /** 停掉媒体会话与前台通知 */
    public void stopMusicSession() {
        if (view == null) return;
        PlaybackService.stopSession(view.context(), view.playbackHost());
    }

    /** 页面销毁:清会话标记 + 停通知 + 收预载(对应原 hostDestroy 的音乐/预载段) */
    public void onHostDestroy() {
        st.clearSessionFlags();
        timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
        // 引擎已释放:三处超时消息若留着,到期仍会走"换线/报错"链路并打到视图桥(见 detach 的桥切换)
        cancelPlayTimeout();
        cancelResolvePlayUrlTimeout();
        stopParse();
        stopMusicSession();
        destroyPreload();
    }

    /** 退后台是否保留播放:本次会话确认过纯音频才保留(粘滞标记见 {@link PlaybackAttemptState#audioOnlyConfirmed}) */
    public boolean isConfirmedAudioOnly() {
        return Boolean.TRUE.equals(isAudioOnlyPlayback()) || st.audioOnlyConfirmed;
    }

    /**
     * 播放状态回调里的"音乐会话"部分(页面状态监听里调用)。
     *
     * @return true = 切换集期间本集已播完(仅保留会话,调用方应直接 return,不再走弹幕等后续逻辑)
     */
    public boolean handlePlayStateForMusicSession(int playState) {
        if (st.switchingPlayback) {
            if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                LOG.i("echo-music keep session while resolving next episode");
                return true;
            } else if (playState == VideoView.STATE_ERROR) {
                // 只解除"切换中"抑制:在此清 audioPlayback 会让后续既不能重试也不能重建会话
                st.switchingPlayback = false;
            } else if (isStartedPlayState(playState)) {
                // 起播成功:有音频轨则维护会话/通知(影视同样,见 updateMusicSession 的语义拆分)
                if (hasPlayableAudio() || st.audioPlayback) {
                    st.switchingPlayback = false;
                    st.audioPlayback = true;
                }
            }
        }
        if (!st.switchingPlayback) {
            if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                // ⚠️ **不能在此直接 updateMusicSession()**。
                // 引擎的状态监听器注册在页面之前(见 PlaybackEngine.createPlayerView 与
                // MusicPlayerActivity.initView),所以 COMPLETED 到达时**本方法总是先跑**,
                // 而"要续播下一集"的登记(beginSwitchPlayback)在页面监听器里(onSongCompleted
                // → playAt/replayCurrent),此刻尚未执行 ⇒ switchingPlayback 读到的必然是 false,
                // 于是按"播完"撤了会话。后果不是"少一条通知"这么轻:
                //   ① stopForeground(true) 撤掉唯一的前台通知;
                //   ② 服务随之失去前台身份;紧接着下一集起播要重新进前台,而此刻 App 通常已在后台
                //      ⇒ 系统拒绝(真机原文 Service.startForeground() not allowed due to
                //      mAllowStartForeground false)⇒ **通知永久回不来**;
                //   ③ audioPlayback 被清 ⇒ 退后台判定也不再豁免。
                // 真机复现:后台播完一首自动切歌,19 秒后
                // 通知消失、连两次 startForeground 被拒、回到页面点击无反应。
                // 改为**延后一拍**再判:让同一次状态分发里页面的 beginSwitchPlayback() 有机会先执行。
                timeoutHandler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
                timeoutHandler.sendEmptyMessage(MSG_DROP_SESSION_AFTER_COMPLETED);
                return false;
            }
            updateMusicSession();
        }
        return false;
    }

    /**
     * 本集播完后的**延后一拍**撤会话判定(配合 {@link #handlePlayStateForMusicSession} 的播完分支)。
     *
     * <p>执行时页面侧的收尾已经跑完,可据三件事决定是否真的撤会话:
     * ① 页面是否登记了"切换中"({@link #beginSwitchPlayback} 会清掉本消息);
     * ② 内核是否已经进入新的起播态(实时读,不依赖事件到达顺序);
     * ③ 页面是否已不存活。
     */
    private void handlePendingCompletionDrop() {
        if (st.switchingPlayback) {
            LOG.i("echo-music completion drop skipped: page registered switching");
            return;
        }
        if (view == null) return;
        // ⚠️ 必须与 updateMusicSession 同一道支持性前置:旧路径的撤会话
        // 是经 updateMusicSession 走的,那里有 `if (!PlaybackService.isSupported(context)) return;`。
        // 本方法直接调 stopSession 会绕过它 —— 在 isSupported()==false 的设备(电视盒子 / API<26)
        // 上就变成"每次播完都去 release 一个 onCreate 建好、与页面同生命周期的 MediaSessionCompat
        // 并把 owner 置空"(后续用法都有判空,不会崩,但媒体键会话被无谓拆掉),属本轮引入的行为变化。
        if (!PlaybackService.isSupported(view.context())) return;
        int state = view.currentPlayState();
        if (isStartedPlayState(state)) {
            LOG.i("echo-music completion drop skipped: kernel already started, state=" + state);
            return;
        }
        // 页面**已销毁**时让位给既有收尾路径(页面退出会走 onHostDestroy/stopPlaybackForPageExit,
        // 那两条自己撤会话并放锁):这里不再插手,以免与它们重复撤会话、或撤在"随后 attach 的新会话"上。
        // ⚠️ 本判据**不**负责"防止迟到消息打到新会话" —— 那是各会话边界 removeMessages 的职责:
        // startSession / beginNewPlay / beginSwitchPlayback / stopMusicSessionForFailedPlayback /
        // stopPlaybackForPageExit / onHostDestroy。
        if (!view.isPageAlive()) {
            LOG.i("echo-music completion drop skipped: page not alive, state=" + state);
            return;
        }
        // 页面还活着且内核仍停在"播完"⇒ 这是真的没人接续(队列末尾 / 非音乐页的影视播完),照旧撤会话。
        // 这一步与原实现等价(原实现是在 COMPLETED 时同步走 updateMusicSession 的撤会话分支)。
        if (state != VideoView.STATE_PLAYBACK_COMPLETED) {
            LOG.i("echo-music completion drop skipped: state moved on, state=" + state);
            return;
        }
        LOG.i("echo-music session drop after completed (deferred): no next episode registered");
        PlaybackService.stopSession(view.context(), view.playbackHost());
        st.audioPlayback = false;
    }

    /**
     * 当前媒体是否有音频轨(与"是否纯音频"是两个判定)。
     *
     * <p>拆分的理由:两件事被混在了一个判定里 ——
     * ① **要不要建 MediaSession / 前台服务通知**(用户要求播放影视也能下拉看到)→ 只要**有音频轨**即可;
     * ② **退后台是否保持播放**(见 [PlaybackController.isConfirmedAudioOnly])→ 只有**纯音频**才保留。
     */
    private boolean hasPlayableAudio() {
        TrackInfo trackInfo = currentTrackInfo();
        return trackInfo != null && !trackInfo.getAudio().isEmpty();
    }

    /**
     * 是否为「纯音频」——**三态**:TRUE=有音轨且无视频轨、FALSE=确定是影视、**null=取不到轨道信息(未知)**。
     *
     * <p>「未知」必须与「假」分开(继承自旧的 `getAudioOnlyPlayback()` 语义)。各调用点的正确用法:
     * <ul>
     *   <li>[isConfirmedAudioOnly] 退后台是否保持播放:用 `Boolean.TRUE.equals(...)` —— 只有确定是纯音频才不停,
     *       null 落到 pause 分支(与迁移前一致);</li>
     *   <li>播放器封面兜底(见 [updateMusicSession]):同一口径 —— 只有确定是纯音频才显示封面。</li>
     * </ul>
     */
    private Boolean isAudioOnlyPlayback() {
        TrackInfo trackInfo = currentTrackInfo();
        if (trackInfo == null || trackInfo.getAudio().isEmpty()) return null;
        return trackInfo.getVideo().isEmpty();
    }

    /** 取当前播放器的轨道信息;拿不到(未起播/不支持)返回 null */
    private TrackInfo currentTrackInfo() {
        if (view == null) return null;
        try {
            AbstractPlayer mediaPlayer = view.mediaPlayer();
            if (mediaPlayer instanceof IjkMediaPlayer) {
                return ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
            } else if (mediaPlayer instanceof ExoPlayer) {
                return ((ExoPlayer) mediaPlayer).getTrackInfo();
            }
        } catch (Throwable ignored) {
            LOG.d("PlaybackController", "track info unavailable");
        }
        return null;
    }

    /**
     * 渲染类型与轨道类型对齐(双向兜底):
     * 确认纯音频 → 热切 TextureView(URL 预判漏网的无后缀音乐直链);确认有视频轨 → 按用户设置恢复渲染视图
     * (回放走复用路径时 fork 的 replay 不重建 RenderView,纯音频热切后播视频集会一直留在 TextureView)。
     * 轨道信息未知(null:未起播/不支持)时两边都不动,避免误切。
     */
    public void ensureAudioOnlyRender() {
        if (view == null) return;
        Boolean audioOnly = isAudioOnlyPlayback();
        if (Boolean.TRUE.equals(audioOnly)) {
            view.switchRenderToTexture();
        } else if (Boolean.FALSE.equals(audioOnly)) {
            view.ensureRenderViewMatchesConfig();
        }
    }

    /**
     * 维护媒体会话与前台通知(有音频轨就维护,影视/音乐一视同仁)。
     *
     * <p>⚠️ 封面只给「纯音频」兜底,绝不能给影视占位(一旦 setArtwork,视频被压成海报):①实时读轨道确定纯音频
     * (**不能用** {@link PlaybackAttemptState#audioOnlyConfirmed} 粘滞标记);②画面未就绪;③audioPlayback。
     *
     * <p>audioPlayback 在此**只置位不清零**(清零只在会话边界),否则一次读取失败会让通知永久消失。
     */
    public void updateMusicSession() {
        if (view == null || !view.isPageAlive()) return;
        Context context = view.context();
        if (!PlaybackService.isSupported(context)) return;
        if (st.switchingPlayback) return;
        TrackInfo trackInfo = currentTrackInfo();
        Boolean hasAudio = trackInfo != null && !trackInfo.getAudio().isEmpty();
        Boolean audioOnly = trackInfo == null || trackInfo.getAudio().isEmpty()
                ? null : trackInfo.getVideo().isEmpty();
        // 只置位不清零:"读到轨道列表但 audio 为空"≠"没有音频"(Exo 在 IDLE/重取流期、音频渲染器
        // 未选中时同样给空 audio 列表),据此清零会让通知与退后台判定双双失效。清零只在会话边界。
        if (Boolean.TRUE.equals(hasAudio)) {
            st.audioPlayback = true;
            if (Boolean.TRUE.equals(audioOnly)) st.audioOnlyConfirmed = true;
        }
        int state = view.currentPlayState();
        LOG.i("echo-music session gate: state=" + state + " playing=" + view.isPlaying()
                + " hasAudio=" + hasAudio + " audioOnly=" + audioOnly + " audioPlayback=" + st.audioPlayback
                + " audioOnlyConfirmed=" + st.audioOnlyConfirmed + " switching=" + st.switchingPlayback
                + " pos=" + view.currentPosition());
        if (st.audioPlayback && Boolean.TRUE.equals(audioOnly)
                && !isStartedPlayState(state)
                && TextUtils.isEmpty(playArtwork) && vod() != null && !TextUtils.isEmpty(vod().pic)) {
            playArtwork = vod().pic;
            view.setArtwork(playArtwork);
        }
        if ((state == VideoView.STATE_ERROR && st.audioOnlyConfirmed) && retryAfterStartedError()) {
            // 已确认纯音频的会话遇可重试错误:同地址重播一次并**保留会话**(撤会话会连锁清 audioPlayback,
            // 而重建只认 STATE_PLAYING 事件 ⇒ 后台失败后点播放再也不出通知)。重试无路可走则照旧撤会话。
            // ⚠️ 判据不得放宽成 audioPlayback:影视也带音轨,会抢在详情页 errorWithRetry 之前
            // 消耗掉 hasRetriedAfterStart(引擎状态监听先注册 ⇒ 总是本方法先跑),使影视丢失"同地址重播"这一档。
            LOG.i("echo-music session keep: auto retry after started error (audio-only)");
            return;
        }
        if (vod() == null || !st.audioPlayback
                || state == VideoView.STATE_ERROR
                || state == VideoView.STATE_PLAYBACK_COMPLETED) {
            LOG.i("echo-music session drop: vod=" + (vod() != null) + " audioPlayback=" + st.audioPlayback
                    + " state=" + state + " (ERROR=" + VideoView.STATE_ERROR
                    + " COMPLETED=" + VideoView.STATE_PLAYBACK_COMPLETED + ")");
            PlaybackService.stopSession(context, view.playbackHost());
            st.audioPlayback = false;
            return;
        }
        // 通知权限兜底(启动时已在 MainActivity 申请过一次):覆盖"启动那次被拒、后来手动开启"的路径
        view.requestNotificationPermission();
        VodInfo.VodSeries currentSeries = currentSeries(vod().playFlag, vod().playIndex);
        String episode = currentSeries == null || TextUtils.isEmpty(currentSeries.name) ? "" : currentSeries.name;
        PlaybackService.updateSession(context, view.playbackHost(),
                TextUtils.isEmpty(vod().name) ? "TVBox" : vod().name,
                episode, vod().pic, view.currentPosition(), view.duration(), view.isPlaying());
    }

    /** 切换清晰度(多清晰度源 url 数组下标;取流链路与首次起播一致) */
    public boolean selectQuality(int position) {
        if (quality() == null) return false;
        try {
            JSONArray urls = new JSONArray(quality().optString("url"));
            String url = urls.optString(position * 2 + 1);
            if (TextUtils.isEmpty(url)) return false;
            String playUrl = quality().optString("playUrl", "");
            String flag = quality().optString("flag");
            boolean parse = quality().optString("parse", "1").equals("1");
            boolean jx = quality().optString("jx", "0").equals("1");
            HashMap<String, String> headers = extractHeaders(quality());
            if (parse || jx) {
                boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                initParse(flag, userJxList, playUrl, url);
            } else {
                if (view != null) view.showParse(false);
                playUrl(playUrl + url, headers);
            }
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 常见纯音频直链后缀预判(仅用于起播前选渲染视图;误判无功能损失 —— TextureView 照常渲染视频)。
     * 注意只看去 query/fragment 后的后缀:音乐直链常带签名参数(.mp3?sign=...), playlist(m3u8) 绝不能命中。
     */
    public static boolean looksLikeAudioUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        int fragment = lower.indexOf('#');
        if (fragment >= 0) lower = lower.substring(0, fragment);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".opus") || lower.endsWith(".wma");
    }
}
