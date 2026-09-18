package com.github.tvbox.osc.ui.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import android.widget.FrameLayout;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.dlna.CastVideo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.PageHost;
import com.github.tvbox.osc.player.PlaybackEngine;
import com.github.tvbox.osc.player.PlaybackService;
import com.github.tvbox.osc.player.PlaybackController;
import com.github.tvbox.osc.player.PlaybackHostApi;
import com.github.tvbox.osc.player.PlaybackPage;
import com.github.tvbox.osc.player.PlaybackSession;
import com.github.tvbox.osc.player.PlaybackViewBridge;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.ComposeVideoController;
import com.github.tvbox.osc.player.controller.PlayerControlApi;
import com.github.tvbox.osc.player.controller.VodControlListener;
import com.github.tvbox.osc.player.danmu.DanmuLoadController;
import com.github.tvbox.osc.player.state.CastSheetState;
import com.github.tvbox.osc.player.state.DanmuSearchSheetState;
import com.github.tvbox.osc.player.state.DanmuSettingSheetState;
import com.github.tvbox.osc.player.state.EpisodeSheetState;
import com.github.tvbox.osc.player.state.PlayerUiState;
import com.github.tvbox.osc.player.state.SelectDialogState;
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState;
import com.github.tvbox.osc.player.state.SubtitleSheetState;
import me.jessyan.autosize.internal.CustomAdapt;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.KV;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import me.jessyan.autosize.AutoSize;
import master.flame.danmaku.ui.widget.DanmakuView;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayContainer extends FrameLayout implements CustomAdapt, PlaybackHostApi, PlaybackPage {

    private final AtomicInteger trackSwitchSeq = new AtomicInteger(0);
    private PlaybackController scheduler;
    private FrameLayout surfaceSlot;
    private PlaybackEngine engine;
    private PageHost pageHost;
    private Activity mActivity;
    private final Context mContext;

    public PlayContainer(@NonNull Activity activity) {
        super(activity);
        mActivity = activity;
        mContext = activity;
        engine = PlaybackService.engine(activity);
        scheduler = engine.controller();
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth());
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true);
        PlayerTipBridge.hide();
        init();
        scheduler.setViewBridge(viewBridge);
        if (engine != null) engine.attach(this);
    }

    public PlaybackViewBridge viewBridge() {
        return viewBridge;
    }

    @Override
    public ViewGroup renderSlot() {
        return surfaceSlot;
    }

    public void onServiceStopped() {
        mVideoView = null;
        engine = null;
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    private boolean isAttached() {
        if (pageHost != null) return pageHost.isPageAlive();
        return mActivity != null && !mActivity.isFinishing();
    }

    public void setPageHost(PageHost host) {
        this.pageHost = host;
    }

    private final PlaybackViewBridge viewBridge = new PlaybackViewBridge() {
        @Override
        public boolean isPageAlive() {
            return isAttached();
        }

        @Override
        public void runOnUi(Runnable action) {
            if (isAttached() && mActivity != null) mActivity.runOnUiThread(action);
        }

        @Override
        public void toast(CharSequence text) {
            Toast.makeText(mContext, text, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void showTip(String msg, boolean loading, boolean error) {
            setTip(msg, loading, error);
        }

        @Override
        public void hideTipOnUiThread() {
            PlayContainer.this.hideTipOnUiThread();
        }

        @Override
        public int currentPlayState() {
            return mVideoView == null ? -1 : mVideoView.getCurrentPlayState();
        }

        @Override
        public long currentPosition() {
            return mVideoView == null ? 0 : mVideoView.getCurrentPosition();
        }

        @Override
        public boolean isPlaying() {
            return mVideoView != null && mVideoView.isPlaying();
        }

        @Override
        public long duration() {
            return mVideoView == null ? 0 : mVideoView.getDuration();
        }

        @Override
        public AbstractPlayer mediaPlayer() {
            return mVideoView == null ? null : mVideoView.getMediaPlayer();
        }

        @Override
        public Context context() {
            return mContext;
        }

        @Override
        public PlaybackHostApi playbackHost() {
            return PlayContainer.this;
        }

        @Override
        public void requestNotificationPermission() {
            if (pageHost != null) {
                pageHost.requestNotificationPermission();
            } else if (mActivity != null) {
                PermissionHelper.requestNotificationIfNeeded(mActivity);
            }
        }

        @Override
        public void switchRenderToTexture() {
            if (mVideoView != null && mVideoView.isSurfaceRenderActive()) {
                mVideoView.switchRenderToTexture();
            }
        }

        @Override
        public void ensureRenderViewMatchesConfig() {
            if (mVideoView != null) mVideoView.ensureRenderViewMatchesConfig();
        }

        @Override
        public void releasePlayer() {
            releasePlayerKernel();
        }

        @Override
        public void setTitle(String title) {
            if (mController != null) mController.setTitle(title);
        }

        @Override
        public void stopOtherPlayers() {
            if (mController != null) mController.stopOther();
        }

        @Override
        public void resetDanmu() {
            resetDanmuState();
        }

        @Override
        public void startDanmuIfReady() {
            PlayContainer.this.startDanmuIfReady();
        }

        @Override
        public void clearLyric() {
            clearLyricView();
        }

        @Override
        public void clearArtwork() {
            if (mVideoView != null) mVideoView.clearArtwork();
        }

        @Override
        public void clearVideoFrame() {
            if (mVideoView != null) mVideoView.clearVideoFrame();
        }

        @Override
        public void setSubtitleViewVisible(boolean visible) {
            if (mController == null) return;
            mController.getSubtitleView().setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onNewPlayStarted() {
            exitingPreview = false;
        }

        @Override
        public void applyPlayerConfigToView(int forceKernel) {
            if (mVideoView == null) return;
            if (forceKernel > 0) {
                PlayerHelper.updateCfg(mVideoView, scheduler.playerCfg(), forceKernel);
            } else {
                PlayerHelper.updateCfg(mVideoView, scheduler.playerCfg());
            }
        }

        @Override
        public void useTextureRenderForAudio() {
            if (mVideoView != null) mVideoView.setRenderViewFactory(TextureRenderViewFactory.create());
        }

        @Override
        public boolean playExternalPlayer(int playerType, String url, String title, String subtitle,
                                         HashMap<String, String> headers, long progress) {
            if (mActivity == null) return false;
            return PlayerHelper.runExternalPlayer(playerType, mActivity, url, title, subtitle, headers, progress);
        }

        @Override
        public void playM3u8(String url, HashMap<String, String> headers) {
            if (mController != null) mController.playM3u8(url, headers);
        }

        @Override
        public void playM3u8(String url, HashMap<String, String> headers, int gen) {
            if (!scheduler.isParseResultCurrent(gen)) {
                LOG.i("echo-ignore stale m3u8 result");
                return;
            }
            playM3u8(url, headers);
        }

        @Override
        public void startVideoPlayback(String url, HashMap<String, String> headers, boolean forceExoPlayer) {
            if (mVideoView == null) return;
            mController.hidePauseRoot();
            // EXO 解码方式变更标记(2026-09-17,见 MyVideoView.requireKernelRebuild):复用内核不会重选解码器,
            // 必须走非复用路径先释放再新建;无条件消费一次,避免标记残留到下一次无关起播
            boolean rebuildKernel = mVideoView.consumeKernelRebuildRequired();
            boolean reusePlayer = !forceExoPlayer && mVideoView.getMediaPlayer() != null && !rebuildKernel;
            if (!reusePlayer) hideTip();
            if (!reusePlayer && mVideoView.getMediaPlayer() != null) {
                releasePlayerKernel();
            }
            mVideoView.setProgressKey(scheduler.progressKey());
            scheduler.markContentStarted();
            if (headers != null) {
                mVideoView.setUrl(url, headers);
            } else {
                mVideoView.setUrl(url);
            }
            scheduler.startSwitchLinePlayTimeout();
            if (reusePlayer) {
                mVideoView.skipPositionWhenPlay((int) scheduler.playTimeoutBasePosition());
                mVideoView.replay(false);
            } else {
                mVideoView.start();
            }
            mController.resetSpeed();
        }

        @Override
        public PreloadCoordinator.Snapshot buildPreloadSnapshot() {
            return PlayContainer.this.buildPreloadSnapshot();
        }

        @Override
        public void showPreloadReadyTip() {
            PlayContainer.this.showPreloadReady();
        }

        @Override
        public void hidePreloadReadyTip() {
            PlayContainer.this.hidePreloadReady();
        }

        @Override
        public String firstUrlByArray(String url) {
            return mController == null ? url : mController.firstUrlByArray(url);
        }

        @Override
        public void setArtwork(String url) {
            if (mVideoView != null) mVideoView.setArtwork(url);
        }

        @Override
        public void showParse(boolean show) {
            if (mController != null) mController.showParse(show);
        }

        @Override
        public void checkDanmu(String danmaku, Runnable onFailed) {
            PlayContainer.this.checkDanmu(danmaku, onFailed == null ? null : onFailed::run);
        }

        @Override
        public String encodeUrl(String url) {
            return mController == null ? url : mController.encodeUrl(url);
        }

        @Override
        public void evaluateScript(String url, WebView webView) {
            if (mController != null) mController.evaluateScript(scheduler.sourceBean(), url, webView);
        }

        @Override
        public WebView newSniffWebView() {
            return new MyWebView(mContext);
        }

        @Override
        public void attachSniffWebView(WebView webView) {
            if (isAttached() && mActivity != null) {
                mActivity.addContentView(webView, new ViewGroup.LayoutParams(1, 1));
            }
        }

        @Override
        public void showErrorWithRetry(String err, boolean finish) {
            PlayContainer.this.errorWithRetry(err, finish);
        }

        @Override
        public boolean switchPlayerKernel() {
            return mController != null && mController.switchPlayer();
        }

        @Override
        public void applyPlayerConfig(JSONObject cfg) {
            if (mController != null) mController.setPlayerConfig(cfg);
        }

        @Override
        public boolean onLinesExhausted() {
            return pageHost != null && pageHost.onPlaybackLinesExhausted();
        }
    };

    private boolean lifecyclePaused;

    public void hostResume() {
        exitingPreview = false;
        if (mController != null) mController.setLifecyclePaused(false);
        reattachIfOwnedByOther();
        if (mVideoView != null && lifecyclePaused) {
            lifecyclePaused = false;
            mVideoView.resume();
        }
    }

    private void reattachIfOwnedByOther() {
        if (engine == null || surfaceSlot == null) return;
        if (engine.attachedPage() == this) return;
        if (engine.isReleased()) return;
        if (engine.isLiveMode()) engine.exitLive();
        engine.attach(this);
        // 重新接管后本页恢复"退出即停播"的职责:交接标记是给"交出去后本页就销毁"准备的,
        // 音乐页返回(影视内容)这条路径本页仍存活,不清掉会让 hostDestroy 漏掉 detach —— 退出后声音不停
        handedOver = false;
        if (mVideoView != null && mController != null) {
            mVideoView.setVideoController((BaseVideoController) mController);
            int state = mVideoView.getCurrentPlayState();
            if (mVideoView.getMediaPlayer() != null
                    && state != VideoView.STATE_IDLE && state != VideoView.STATE_ERROR) {
                rebindPlaybackOverlay();
            }
        }
        LOG.i("echo-p4 re-attach after live/other page");
    }

    public void hostPause() {
        if (mVideoView != null && !exitingPreview && !scheduler.isConfirmedAudioOnly()) {
            lifecyclePaused = mVideoView.isPlaying();
            if (mController != null) mController.setLifecyclePaused(true);
            mVideoView.pause();
        }
    }

    private boolean handedOver;

    /** 交给音乐播放页接管:引擎摘视图但不停播,随后的 hostDestroy 不得再 detach(会停掉刚交接的音频) */
    public void handOverToNextPage() {
        if (engine == null) return;
        handedOver = true;
        engine.detachForHandover(this);
    }

    public void hostDestroy() {
        LOG.i("echo-music destroy: hostDestroy enter");
        if (engine != null && !handedOver) engine.detach(this);
        cancelPreloadToast();
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        trackSwitchSeq.incrementAndGet();
        if (danmuLoadController != null) {
            danmuLoadController.destroy();
            danmuLoadController = null;
        }
        mVideoView = null;
        if (mController != null) mController.stopOther();
        mActivity = null;
        LOG.i("echo-music destroy: hostDestroy done");
    }

    @Override
    public float getSizeInDp() {
        return (mActivity instanceof CustomAdapt) ? ((CustomAdapt) mActivity).getSizeInDp() : 0;
    }

    @Override
    public boolean isBaseOnWidth() {
        return !(mActivity instanceof CustomAdapt) || ((CustomAdapt) mActivity).isBaseOnWidth();
    }

    private static final int MSG_PARSE_TIMEOUT = 100;
    private static final long PRELOAD_TOAST_REFRESH_DELAY_MS = 1500L;
    private MyVideoView mVideoView;
    private PlayerControlApi mController;
    private Toast preloadReadyToast;
        private Handler mHandler;
    private boolean exitingPreview = false;
    private boolean previewMode;
    private DanmakuView mDanmuView;
    private DanmuLoadController danmuLoadController;
    private final List<Cue> exoCues = new ArrayList<>();
    private boolean exoInternalSubtitle;

    private final long videoDuration = -1;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            applySubtitleTextSize();
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            setDanmuViewSettings(event.obj instanceof Boolean && (Boolean) event.obj);
        } else if (event.type == RefreshEvent.TYPE_DANMU_REFRESH) {
            checkDanmu(event.obj instanceof String ? (String) event.obj : "");
        }
    }

    private void init() {
        initView();
        initDanmuView();
    }

    private void initDanmuView() {
        mDanmuView = findViewById(R.id.danmaku);
        danmuLoadController = new DanmuLoadController(mVideoView, mController, mDanmuView);
    }

    private void setDanmuViewSettings(boolean reload) {
        if (danmuLoadController != null) danmuLoadController.applySettings(reload);
    }

    private void checkDanmu(String danmu) {
        checkDanmu(danmu, null);
    }

    private void checkDanmu(String danmu, DanmuLoadController.LoadCallback callback) {
        scheduler.setPlayDanmu(danmu);
        if (danmuLoadController != null) {
            VodInfo.VodSeries series = scheduler.vod() == null ? null : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
            danmuLoadController.check(danmu, scheduler.vod() == null ? "" : scheduler.vod().name, series == null ? "" : series.name, callback);
        }
    }

    private void startDanmuIfReady() {
        if (danmuLoadController != null) danmuLoadController.startIfReady();
    }

    private void resetDanmuState() {
        if (danmuLoadController != null) danmuLoadController.reset();
    }

    private void reloadDanmuForPlayback() {
        if (danmuLoadController != null) danmuLoadController.reloadForPlayback();
    }

        private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_PARSE_TIMEOUT:
                        scheduler.stopParse();
                        errorWithRetry("嗅探错误", false);
                        break;
                }
                return false;
            }
        });
        surfaceSlot = findViewById(R.id.surfaceSlot);
        mController = new ComposeVideoController(mActivity);

        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        mVideoView = engine == null ? null : engine.player();
        mController.setListener(new VodControlListener() {
            @Override
            public void showDanmuSetting() {
                if (!isAttached()) return;
                mController.getUiState().setDanmuSettingSheet(new DanmuSettingSheetState(() -> {
                    openDanmuSearchSheet();
                    return kotlin.Unit.INSTANCE;
                }));
            }

            @Override
            public boolean toggleDanmu() {
                return danmuLoadController != null && danmuLoadController.toggle();
            }

            @Override
            public void searchDanmuUi(boolean longClick) {
                VodInfo.VodSeries series = scheduler.vod() == null ? null : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
                ApiConfig.get().searchDanmuUi(scheduler.vod() == null ? "" : scheduler.vod().name, series == null ? "" : series.name, longClick);
            }

            @Override
            public void playNext(boolean rmProgress) {
                String preProgressKey = scheduler.progressKey();
                PlayContainer.this.playNext(rmProgress);
                if (rmProgress && preProgressKey != null)
                    CacheManager.delete(MD5.string2MD5(preProgressKey), 0);
            }

            @Override
            public void playPre() {
                PlayContainer.this.playPrevious();
            }

            @Override
            public void showEpisodeDialog() {
                PlayContainer.this.showEpisodeDialog();
            }

            @Override
            public void changeParse(ParseBean pb) {
                scheduler.resetAutoRetryState();
                scheduler.clearTriedLines();
                scheduler.doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                JSONObject persistCfg = scheduler.playerCfgForPersist();
                if (persistCfg == null) return;
                scheduler.vod().playerCfg = persistCfg.toString();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, persistCfg));
            }

            @Override
            public void replay(boolean replay) {
                reviveEngineIfReleased();
                scheduler.resetAutoRetryState();
                scheduler.clearTriedLines();
                scheduler.setPlaybackStarted(false);
                if(replay){
                    playViaScheduler(true);
                }else {
                    reloadDanmuForPlayback();
                    if(scheduler.webPlayUrl()!=null && !scheduler.webPlayUrl().isEmpty()) {
                        scheduler.stopParse();
                        scheduler.initParseLoadFound();
                        releasePlayerKernel();
                        scheduler.goPlayUrl(scheduler.webPlayUrl(),scheduler.webHeaderMap());
                    }else {
                        playViaScheduler(false);
                    }
                }
            }

            @Override
            public void errReplay() {
                errorWithRetry("视频播放出错", false);
            }

            @Override
            public void selectSubtitle() {
                try {
                    selectMySubtitle();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void selectVideoTrack() {
                selectMyVideoTrack();
            }

            @Override
            public void prepared() {
                initSubtitleView();
                if (mVideoView != null) mVideoView.prepared();
                startDanmuIfReady();
            }
            @Override
            public void startPlayUrl(String url, HashMap<String, String> headers) {
                if (!TextUtils.isEmpty(scheduler.m3u8SourceUrl()) && !scheduler.isM3u8ProxyUrl(url)) scheduler.clearM3u8ProxyUrl();
                scheduler.goPlayUrl(url, headers);
            }

            @Override
            public void onM3u8ProxyUrl(String proxyUrl, String sourceUrl) {
                scheduler.setM3u8Urls(proxyUrl, sourceUrl);
            }

            @Override
            public void clickCast() {
                showCastDialog();
            }

            @Override
            public void setAllowSwitchPlayer(boolean isAllow){scheduler.setAllowSwitchPlayer(isAllow);}

            @Override
            public void setAllowDecodeFallback(boolean isAllow){scheduler.setAllowDecodeFallback(isAllow);}
        });
        if (mVideoView != null) mVideoView.setVideoController((BaseVideoController) mController);
    }

    public void showCast() {
        showCastDialog();
    }

    private void showCastDialog() {
        if (TextUtils.isEmpty(scheduler.webPlayUrl())) {
            Toast.makeText(mContext, "暂无可投屏播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAttached()) return;
        HashMap<String, String> headers = scheduler.webHeaderMap() == null ? null : new HashMap<>(scheduler.webHeaderMap());
        CastVideo video = new CastVideo(scheduler.getCastUrl(scheduler.webPlayUrl()), getCastTitle(), headers, getCastPosition());
        PlayerUiState uiState = mController.getUiState();
        uiState.setCastSheet(new CastSheetState(video, () -> {
            if (mVideoView != null) mVideoView.pause();
            return kotlin.Unit.INSTANCE;
        }));
    }

    private void openDanmuSearchSheet() {
        if (!isAttached()) return;
        VodInfo.VodSeries series = scheduler.vod() == null ? null : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
        PlayerUiState uiState = mController.getUiState();
        uiState.setDanmuSearchSheet(new DanmuSearchSheetState(
                series == null ? "" : series.name,
                scheduler.vod() == null ? "" : scheduler.vod().name,
                danmu -> {
                    if (!isAttached()) return kotlin.Unit.INSTANCE;
                    checkDanmu(danmu);
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private String getCastTitle() {
        if (scheduler.vod() == null) return "TVBox";
        try {
            VodInfo.VodSeries series = scheduler.vod().seriesMap.get(scheduler.vod().playFlag).get(scheduler.vod().playIndex);
            return scheduler.vod().name + " " + series.name;
        } catch (Exception e) {
            return TextUtils.isEmpty(scheduler.vod().name) ? "TVBox" : scheduler.vod().name;
        }
    }

    private long getCastPosition() {
        try {
            return mVideoView == null ? 0 : mVideoView.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    void setSubtitle(String path) {
        if (path != null && path .length() > 0) {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setVisibility(View.GONE);
            mController.getSubtitleView().setSubtitlePath(path);
            setSubtitleViewTextStyle(KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0));
            mController.getSubtitleView().setVisibility(View.VISIBLE);
        }
    }

    void selectMySubtitle() {
        try {
            if (!isAttached() || mVideoView == null) return;
            PlayerUiState uiState = mController.getUiState();
            AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
            boolean hasInternal = mController.getSubtitleView().hasInternal || hasExoInternalSubtitle(mediaPlayer);
            boolean exoInternal = mediaPlayer instanceof ExoPlayer && exoInternalSubtitle;
            uiState.setSubtitleSheet(new SubtitleSheetState(
                    exoInternal,
                    hasInternal,
                    () -> {
                        selectMyInternalSubtitle();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        openLocalSubtitleChooser();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        openSubtitleSearchSheet();
                        return kotlin.Unit.INSTANCE;
                    },
                    style -> {
                        KV.put(HawkConfig.SUBTITLE_TEXT_STYLE, style);
                        setSubtitleViewTextStyle(style);
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        applySubtitleTextSize();
                        return kotlin.Unit.INSTANCE;
                    }));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openLocalSubtitleChooser() {
        if (pageHost != null) pageHost.launchLocalSubtitlePicker();
    }

    public void onLocalSubtitlePicked(android.net.Uri uri) {
        final android.app.Activity activity = mActivity;
        if (activity == null || activity.isFinishing()) return;
        new Thread(() -> {
            try {
                String name = queryDisplayName(activity, uri);
                if (name == null || !name.contains(".")) name = "local_subtitle.srt";
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                File dst = new File(activity.getCacheDir(), "subtitle_" + System.currentTimeMillis() + "_" + name);
                try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                String path = dst.getAbsolutePath();
                activity.runOnUiThread(() -> {
                    if (!isAttached()) return;
                    LOG.i("echo-Local Subtitle Path: " + path);
                    setSubtitle(path);
                });
            } catch (Exception e) {
                LOG.e("echo-Local Subtitle copy err: " + e);
                activity.runOnUiThread(() -> {
                    if (isAttached()) {
                        android.widget.Toast.makeText(activity, "读取字幕文件失败", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private String queryDisplayName(android.app.Activity activity, android.net.Uri uri) {
        try (android.database.Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void openSubtitleSearchSheet() {
        if (!isAttached()) return;
        String word = (scheduler.vod().playFlag.contains("Ali") || scheduler.vod().playFlag.contains("parse"))
                ? scheduler.vod().playNote : scheduler.vod().name;
        PlayerUiState uiState = mController.getUiState();
        uiState.setSubtitleSearchSheet(new SubtitleSearchSheetState(word == null ? "" : word, subtitle -> {
            if (!isAttached()) return kotlin.Unit.INSTANCE;
            mActivity.runOnUiThread(() -> {
                String zimuUrl = subtitle.getUrl();
                LOG.i("echo-Remote Subtitle Url: " + zimuUrl);
                setSubtitle(zimuUrl);
            });
            return kotlin.Unit.INSTANCE;
        }));
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    void setSubtitleViewTextStyle(int style) {
        if (style == 0) {
            mController.getSubtitleView().setTextColor(getContext().getResources().getColorStateList(R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.getSubtitleView().setTextColor(getContext().getResources().getColorStateList(R.color.color_FFB6C1));
        }
        applyExoSubtitleStyle();
    }

    private boolean isSameTrack(TrackInfoBean left, TrackInfoBean right) {
        return left.renderId == right.renderId
                && left.trackGroupId == right.trackGroupId
                && left.trackId == right.trackId;
    }

    void selectMyAudioTrack() {
        if (mVideoView == null) return;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer)mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer)mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : bean) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                "切换音轨",
                names,
                trackInfo.getAudioSelected(false),
                pos -> {
                    if (pos < 0 || pos >= bean.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = bean.get(pos);
                    try {
                        for (TrackInfoBean audio : bean) {
                            audio.selected = isSameTrack(audio, value);
                        }
                        mediaPlayer.pause();
                        long progress = mediaPlayer.getCurrentPosition();
                        if (mediaPlayer instanceof IjkMediaPlayer) ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId, scheduler.progressKey());
                        if (mediaPlayer instanceof ExoPlayer) ((ExoPlayer) mediaPlayer).setTrack(value, scheduler.progressKey());
                        final int seq = trackSwitchSeq.incrementAndGet();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != trackSwitchSeq.get()) return;
                                if (mediaPlayer instanceof IjkMediaPlayer) mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                            }
                        }, 200);
                    } catch (Exception e) {
                        LOG.e("切换音轨出错");
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    void selectMyVideoTrack() {
        if (mVideoView == null) return;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
        } else if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null || trackInfo.getVideo().isEmpty()) {
            Toast.makeText(mContext, "没有视轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> tracks = trackInfo.getVideo();
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : tracks) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                "切换视轨",
                names,
                trackInfo.getVideoSelected(false),
                pos -> {
                    if (pos < 0 || pos >= tracks.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = tracks.get(pos);
                    try {
                        for (TrackInfoBean track : tracks) {
                            track.selected = isSameTrack(track, value);
                        }
                        mediaPlayer.pause();
                        long progress = mediaPlayer.getCurrentPosition();
                        if (mediaPlayer instanceof IjkMediaPlayer) {
                            ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId);
                        } else if (mediaPlayer instanceof ExoPlayer) {
                            ((ExoPlayer) mediaPlayer).setTrack(value, "");
                        }
                        final int seq = trackSwitchSeq.incrementAndGet();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != trackSwitchSeq.get()) return;
                                mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                            }
                        }, 200);
                    } catch (Exception e) {
                        LOG.e("echo-switch-video-track-error:" + e.getMessage());
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    void selectMyInternalSubtitle() {
        if (mVideoView == null) return;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
        } else if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有内置字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : bean) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                "切换内置字幕",
                names,
                trackInfo.getSubtitleSelected(false),
                pos -> {
                    if (pos < 0 || pos >= bean.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = bean.get(pos);
                    try {
                        for (TrackInfoBean subtitle : bean) {
                            subtitle.selected = isSameTrack(subtitle, value);
                        }
                        if (mediaPlayer instanceof IjkMediaPlayer) {
                            mediaPlayer.pause();
                            long progress = mediaPlayer.getCurrentPosition();
                            mController.getSubtitleView().destroy();
                            mController.getSubtitleView().clearSubtitleCache();
                            mController.getSubtitleView().isInternal = true;
                            ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId);
                            final int seq = trackSwitchSeq.incrementAndGet();
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (seq != trackSwitchSeq.get()) return;
                                    mediaPlayer.seekTo(progress);
                                    mediaPlayer.start();
                                }
                            }, 800);
                        } else if (mediaPlayer instanceof ExoPlayer) {
                            mController.getSubtitleView().setVisibility(View.GONE);
                            mController.getSubtitleView().destroy();
                            mController.getSubtitleView().clearSubtitleCache();
                            mController.getSubtitleView().isInternal = false;
                            exoInternalSubtitle = true;
                            ((ExoPlayer) mediaPlayer).setTrack(value, "");
                            ((ExoPlayer) mediaPlayer).setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                            mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                            applyExoSubtitleSettings();
                        }
                    } catch (Exception e) {
                        LOG.e("echo-switch-internal-subtitle-error:" + e.getMessage());
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private boolean hasExoInternalSubtitle(AbstractPlayer mediaPlayer) {
        if (!(mediaPlayer instanceof ExoPlayer)) return false;
        TrackInfo trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        return trackInfo != null && !trackInfo.getSubtitle().isEmpty();
    }

    private void hideExoInternalSubtitle() {
        exoInternalSubtitle = false;
        exoCues.clear();
        if (mController != null && mController.getExoSubtitleView() != null) {
            mController.getExoSubtitleView().setCues(exoCues);
            mController.getExoSubtitleView().setVisibility(View.GONE);
        }
    }

    private void onExoCues(List<Cue> cues) {
        if (!isAttached() || !exoInternalSubtitle) return;
        exoCues.clear();
        if (cues != null) exoCues.addAll(cues);
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyExoSubtitleSettings();
            }
        });
    }

    private void applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || mController == null || mController.getExoSubtitleView() == null) return;
        applyExoSubtitleStyle();
        float scale = SubtitleHelper.getExoSubtitleScale() / 100f;
        float position = SubtitleHelper.getExoSubtitlePosition();
        mController.getExoSubtitleView().setFractionalTextSize(0.0533f * scale);
        mController.getExoSubtitleView().setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f));

        List<Cue> displayCues = new ArrayList<>();
        for (Cue cue : exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue);
                continue;
            }
            Cue.Builder builder = cue.buildUpon();
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f));
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f));
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType);
            }
            displayCues.add(builder.build());
        }
        mController.getExoSubtitleView().setCues(displayCues);
    }

    private void applyExoSubtitleStyle() {
        if (mController == null || mController.getExoSubtitleView() == null) return;
        int style = KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0);
        int textColor = getContext().getResources().getColorStateList(
                style == 1 ? R.color.color_FFB6C1 : R.color.color_FFFFFF).getDefaultColor();
        mController.getExoSubtitleView().setStyle(new CaptionStyleCompat(
                textColor,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD));
    }

    private float limit(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    void setTip(String msg, boolean loading, boolean err) {
        if (!isAttached()) return;
        PlayerTipBridge.setTip(msg, loading, err);
    }

    void hideTip() {
        PlayerTipBridge.hide();
    }

    void hideTipOnUiThread() {
        if (!isAttached()) return;
        PlayerTipBridge.hide();
    }

    private void showPreloadReady() {
        final Activity activity = mActivity;
        if (activity == null || !isAttached() || mHandler == null) return;
        if (preloadReadyToast != null) preloadReadyToast.cancel();
        preloadReadyToast = Toast.makeText(activity, "下一集已就绪", Toast.LENGTH_LONG);
        preloadReadyToast.show();
        mHandler.removeCallbacks(refreshPreloadToastRunnable);
        mHandler.postDelayed(refreshPreloadToastRunnable, PRELOAD_TOAST_REFRESH_DELAY_MS);
    }

    private final Runnable refreshPreloadToastRunnable = new Runnable() {
        @Override
        public void run() {
            if (preloadReadyToast != null) preloadReadyToast.show();
        }
    };

    private void hidePreloadReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPreloadToast();
        } else if (mActivity != null) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelPreloadToast();
                }
            });
        }
    }

    private void cancelPreloadToast() {
        if (mHandler != null) mHandler.removeCallbacks(refreshPreloadToastRunnable);
        if (preloadReadyToast != null) {
            preloadReadyToast.cancel();
            preloadReadyToast = null;
        }
    }

    void errorWithRetry(String err, boolean finish) {
        if (scheduler.isPlaybackStarted()) {
            scheduler.cancelPlayTimeout();
            hideTipOnUiThread();
            if (scheduler.retryAfterStartedError()) return;
            scheduler.stopMusicSessionForFailedPlayback();
            if (!isAttached()) return;
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setTip(err, false, true);
                    if (finish) {
                        Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return;
        }
        if (!scheduler.autoRetry()) {
            scheduler.stopMusicSessionForFailedPlayback();
            if (!isAttached()) return;
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (finish) {
                        setTip(err, false, true);
                        Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show();
                    } else {
                        setTip(err, false, true);
                    }
                }
            });
        }
    }

                    private void initSubtitleView() {
        if (mVideoView == null) return;
        TrackInfo trackInfo = null;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        applySubtitleTextSize();
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().reset();
        mController.getLyricView().bindToMediaPlayer(mediaPlayer);
        mController.getLyricView().setMergeSameTime(true);
        mController.getLyricView().setLyricMode(true);
        mController.getLyricView().setPlaySubtitleCacheKey(scheduler.lyricCacheKey());
        mController.getSubtitleView().hasInternal = false;
        mController.getSubtitleView().isInternal = false;
        hideExoInternalSubtitle();
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer)mediaPlayer).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.getSubtitleView().hasInternal = true;
            }
            ((IjkMediaPlayer)mediaPlayer).loadDefaultTrack(trackInfo,scheduler.progressKey());
            ((IjkMediaPlayer)mediaPlayer).setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
                @Override
                public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
                    if(text==null)return;
                    if (mController.getSubtitleView().isInternal) {
                        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                        subtitle.content = text.getText();
                        mController.getSubtitleView().onSubtitleChanged(subtitle);
                    }
                }
            });
        }
        if (mediaPlayer instanceof ExoPlayer) {
            ExoPlayer exoPlayer = (ExoPlayer) mediaPlayer;
            trackInfo = exoPlayer.getTrackInfo();
            if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                mController.getSubtitleView().hasInternal = true;
                exoInternalSubtitle = true;
                mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                exoPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                exoPlayer.setOnCuesListener(new ExoPlayer.OnCuesListener() {
                    @Override
                    public void onCues(List<Cue> cues) {
                        onExoCues(cues);
                    }
                });
                applyExoSubtitleSettings();
            }
            exoPlayer.loadDefaultTrack(scheduler.progressKey());
        }
        // 歌词来源:内联 data: 在内存里、毫秒级;URL 歌词优先吃本集缓存,否则每次起播都要走网络(快慢全看源站,慢链还要等满 10s 超时)
        String lyric = scheduler.playLyric();
        String lyricPath = lyric;
        if (TextUtils.isEmpty(lyric) || !lyric.startsWith("data:")) {
            String cachedLyric = cachedPlayPath(scheduler.lyricCacheKey());
            if (!TextUtils.isEmpty(cachedLyric)) lyricPath = cachedLyric;
        }
        if (!TextUtils.isEmpty(lyricPath)) {
            mController.getLyricView().setSubtitlePath(lyricPath);
            mController.getLyricView().setVisibility(View.VISIBLE);
        }
        mController.getSubtitleView().bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.getSubtitleView().setPlaySubtitleCacheKey(scheduler.subtitleCacheKey());
        String subtitlePathCache = cachedPlayPath(scheduler.subtitleCacheKey());
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setSubtitlePath(subtitlePathCache);
        } else {
            if (scheduler.playSubtitle() != null && scheduler.playSubtitle() .length() > 0) {
                hideExoInternalSubtitle();
                mController.getSubtitleView().setSubtitlePath(scheduler.playSubtitle());
            } else {
                if (mController.getSubtitleView().hasInternal) {
                    if (mediaPlayer instanceof ExoPlayer) {
                        ((ExoPlayer) mediaPlayer).setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                        exoInternalSubtitle = true;
                        mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                        applyExoSubtitleSettings();
                    } else if (mediaPlayer instanceof IjkMediaPlayer && trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                        mController.getSubtitleView().isInternal = true;
                        List<TrackInfoBean> subtitleTrackList = trackInfo.getSubtitle();
                        int selectedIndex = trackInfo.getSubtitleSelected(true);
                        boolean hasMandarin = false;
                        for (TrackInfoBean subtitleTrackInfoBean : subtitleTrackList) {
                            if ("国语".equals(subtitleTrackInfoBean.language)) {
                                hasMandarin = true;
                                if (selectedIndex != subtitleTrackInfoBean.trackId) {
                                    ((IjkMediaPlayer) mediaPlayer).setTrack(subtitleTrackInfoBean.trackId);
                                    break;
                                }
                            }
                        }
                        if (!hasMandarin) {
                            ((IjkMediaPlayer) mediaPlayer).setTrack(subtitleTrackList.get(0).trackId);
                        }
                    }
                }
            }
        }
    }

    private void rebindPlaybackOverlay() {
        initSubtitleView();
        checkDanmu(scheduler.playDanmu());
    }

    /**
     * 某集已落盘的字幕/歌词来源:内联 data: 直接可用;本地文件要确认还在(系统可能清 /zimu/ 缓存目录,否则会静默无字幕);
     * 其余情况返回空,由调用方回退到本次起播的新地址。
     */
    private String cachedPlayPath(String cacheKey) {
        if (TextUtils.isEmpty(cacheKey)) return "";
        Object cached = CacheManager.getCache(MD5.string2MD5(cacheKey));
        if (!(cached instanceof String)) return "";
        String path = (String) cached;
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("data:")) return path;
        return new File(path).exists() ? path : "";
    }

            private void clearLyricView() {
        if (mController == null || mController.getLyricView() == null) return;
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().destroy();
        mController.getLyricView().setText("");
    }

    private void releasePlayerKernel() {
        if (engine != null) {
            engine.releasePlayer();
        } else if (mVideoView != null) {
            mVideoView.release();
        }
    }

    private boolean reviveEngineIfReleased() {
        if (engine != null && !engine.isReleased()) return false;
        if (mActivity == null || surfaceSlot == null) return false;
        if (scheduler != null) scheduler.stopPlaybackForPageExit();
        engine = PlaybackService.engine(mActivity);
        scheduler = engine.controller();
        mVideoView = engine.player();
        engine.attach(this);
        handedOver = false;
        if (mVideoView != null) {
            mVideoView.setVideoController((BaseVideoController) mController);
            if (danmuLoadController != null) danmuLoadController.setVideoView(mVideoView);
        }
        LOG.i("echo-p2 revive engine after release");
        return true;
    }

    @Override
    public void play(boolean reset) {
        reviveEngineIfReleased();
        scheduler.play(reset);
    }

    @Override
    public boolean selectQuality(int position) {
        return scheduler.selectQuality(position);
    }
                @Override
    public void setData(PlaybackSession session) {
        if (engine == null || engine.isReleased()) {
            if (!reviveEngineIfReleased()) {
                LOG.i("echo-p5 setData skipped: engine released");
                return;
            }
        }
        if (isSamePlaybackOwned(session)) {
            LOG.i("echo-p3 take over same playback: " + session.playbackKey());
            engine.setData(session);
            mController.setPlayerConfig(scheduler.playerCfg());
            scheduler.markContentStarted();
            scheduler.publishTitle();
            scheduler.clearTriedLines();
            scheduler.setUserPickedLine(session.userPickedLine());
            rebindPlaybackOverlay();
            if (mVideoView != null && !mVideoView.isPlaying()) mVideoView.start();
            return;
        }
        engine.setData(session);
        mController.setPlayerConfig(scheduler.playerCfg());
        scheduler.clearTriedLines();
        scheduler.setUserPickedLine(session.userPickedLine());
        playViaScheduler(false);
    }

    private void playViaScheduler(boolean reset) {
        reviveEngineIfReleased();
        scheduler.play(reset);
    }

    private boolean isSamePlaybackOwned(PlaybackSession session) {
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false;
        if (engine.isLiveMode()) return false;
        if (mVideoView == null || mVideoView.getMediaPlayer() == null) return false;
        int state = mVideoView.getCurrentPlayState();
        return state != VideoView.STATE_ERROR && state != VideoView.STATE_IDLE;
    }

    public boolean onBackPressed() {
        int requestedOrientation = mActivity.getRequestedOrientation();
        boolean portrait = requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        if (portrait) {
            if (mController.onBackPressed()) {
                return true;
            }
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            return true;
        }
        if (mController.onBackPressed()) {
            return true;
        }
        return false;
    }

    public void setExitingPreview(boolean exitingPreview) {
        this.exitingPreview = exitingPreview;
    }

        public void resumeFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.start();
            scheduler.updateMusicSession();
        }
    }

    public void pauseFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.pause();
            scheduler.updateMusicSession();
        }
    }

    public void stopFromMediaSession() {
        if (mVideoView != null) mVideoView.pause();
        scheduler.stopMusicSession();
    }

    public void seekFromMediaSession(long position) {
        if (mVideoView != null) {
            mVideoView.seekTo(position);
            scheduler.updateMusicSession();
        }
    }

                
    public void playNext(boolean isProgress) {
        scheduler.clearTriedLines();
        boolean hasNext;
        if (scheduler.vod() == null || scheduler.vod().seriesMap.get(scheduler.vod().playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = scheduler.vod().playIndex + 1 < scheduler.vod().seriesMap.get(scheduler.vod().playFlag).size();
        }
        if (!hasNext) {
            Toast.makeText(mActivity, "已经是最后一集了!", Toast.LENGTH_SHORT).show();
            return;
        }else {
            scheduler.vod().playIndex++;
        }
        scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    public void playPrevious() {
        scheduler.clearTriedLines();
        boolean hasPre = true;
        if (scheduler.vod() == null || scheduler.vod().seriesMap.get(scheduler.vod().playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = scheduler.vod().playIndex - 1 >= 0;
        }
        if (!hasPre) {
            Toast.makeText(mActivity, "已经是第一集了!", Toast.LENGTH_SHORT).show();
            return;
        }
        scheduler.vod().playIndex--;
        scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    private void showEpisodeDialog() {
        if (!isAttached() || scheduler.vod() == null || scheduler.vod().seriesMap == null || TextUtils.isEmpty(scheduler.vod().playFlag)) return;
        List<VodInfo.VodSeries> episodes = scheduler.vod().seriesMap.get(scheduler.vod().playFlag);
        if (episodes == null || episodes.isEmpty()) return;
        String title = TextUtils.isEmpty(scheduler.vod().name) ? "选集" : scheduler.vod().name + " 选集";
        mController.getUiState().setEpisodeSheet(new EpisodeSheetState(
                title,
                episodes,
                scheduler.vod().playIndex,
                position -> {
                    if (position < 0 || position >= episodes.size() || position == scheduler.vod().playIndex) return kotlin.Unit.INSTANCE;
                    scheduler.clearTriedLines();
                    scheduler.vod().playIndex = position;
                    scheduler.setReusePlayerOnSwitch(true);
                    playViaScheduler(false);
                    return kotlin.Unit.INSTANCE;
                }));
    }
    public void setPlayTitle(boolean show) {
        if (!show) {
            mController.setTitle("");
            return;
        }
        VodInfo vod = scheduler.vod();
        VodInfo.VodSeries vs = vod == null ? null : scheduler.currentSeries(vod.playFlag, vod.playIndex);
        mController.setTitle(vod == null ? "" : (vs == null ? vod.name : vod.name + " " + vs.name));
    }

        private PreloadCoordinator.Snapshot buildPreloadSnapshot() {
        try {
            if (scheduler.vod() == null || scheduler.vod().seriesMap == null) return null;
            List<VodInfo.VodSeries> episodes = scheduler.vod().seriesMap.get(scheduler.vod().playFlag);
            if (episodes == null || scheduler.vod().playIndex < 0 || scheduler.vod().playIndex + 1 >= episodes.size()) return null;
            VodInfo.VodSeries next = episodes.get(scheduler.vod().playIndex + 1);
            if (next == null || TextUtils.isEmpty(next.url)) return null;
            int nextIndex = scheduler.vod().playIndex + 1;
            String nextKey = scheduler.vod().sourceKey + scheduler.vod().id + scheduler.vod().playFlag + nextIndex + next.name;
            String nextSubtKey = scheduler.vod().sourceKey + "-" + scheduler.vod().id + "-" + scheduler.vod().playFlag + "-" + nextIndex + "-" + next.name + "-subt";
            long startSkipMs = scheduler.playerCfg() == null ? 0 : scheduler.playerCfg().optInt("st", 0) * 1000L;
            AbstractPlayer mediaPlayer = mVideoView == null ? null : mVideoView.getMediaPlayer();
            boolean exoKernel = mediaPlayer instanceof ExoPlayer;
            return new PreloadCoordinator.Snapshot(mContext, scheduler.sourceKey(), scheduler.vod().playFlag, scheduler.progressKey(), nextKey, next.url, nextSubtKey, startSkipMs, exoKernel);
        } catch (Throwable th) {
            LOG.i("echo-preload-skip: snapshot error " + th);
            return null;
        }
    }
    @Override
    public void setAutoSwitchLineEnabled(boolean enabled) {
        scheduler.setAutoSwitchLineEnabled(enabled);
    }

public void setPreviewMode(boolean previewMode) {
this.previewMode = previewMode;
if (mController != null) {
mController.setPreviewMode(previewMode);
mController.getLyricView().setTextSize(previewMode ? 16 : 24);
applySubtitleTextSize();
}
}

/** 字幕字号 = 设置值 × 当前形态(预览 0.6×/全屏 1×);统一走 setTextSize(float)=sp —— SimpleSubtitleView 只重写了 float 重载(描边层 backGroundText 随之同步),int 实参会被加宽到 float,同样落到该重载 */
private void applySubtitleTextSize() {
if (mController == null || mController.getSubtitleView() == null) return;
int size = SubtitleHelper.getTextSize(mActivity);
mController.getSubtitleView().setTextSize(previewMode ? size * 0.6f : (float) size);
}

public void toggleControllerControls() {
if (mController != null) {
mController.toggleControlBar();
}
}

    public void stopForSourceSwitch(String tip) {
        if (mVideoView == null) return;
        scheduler.cancelPlayTimeout();
        scheduler.stopParse();
        scheduler.markStoppedForSourceSwitch();
        scheduler.stopMusicSessionForFailedPlayback();
        
        long position = mVideoView.getCurrentPosition();
        scheduler.setPendingInherit(scheduler.progressKey(), position);
        mVideoView.pause();
        releasePlayerKernel();
        if (mController != null) mController.stopOther();
        resetDanmuState();
        scheduler.setWebPlayUrl(null);
        scheduler.setWebHeaderMap(null);
        scheduler.initParseLoadFound();
        LOG.i("echo-switchSource stop at " + position + "ms, key=" + scheduler.progressKey());
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false);
    }

    public void clearSourceSwitchTip() {
        if (!scheduler.isSwitchStopPending()) return;
        hideTipOnUiThread();
    }
                public MyVideoView getPlayer() {
        return mVideoView;
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayContainer.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }
}
