package com.github.tvbox.osc.player.danmu;

import android.text.TextUtils;
import android.view.View;

import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.PlayerControlApi;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.LOG;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.VideoView;

public class DanmuLoadController {
    public interface LoadCallback {
        void onFailed();
    }

    private MyVideoView videoView;
    private final PlayerControlApi controller;
    private final DanmakuView danmuView;
    private final DanmakuContext danmakuContext;
    private final AtomicInteger loadSeq = new AtomicInteger();
    private ExecutorService executor;
    private String danmuText = "";
    private String danmuTitle = "";
    private String danmuEpisode = "";
    private int startedSeq = -1;
    private boolean pendingPrepare;
    private boolean temporarilyClosed;
    private LoadCallback loadCallback;

    public DanmuLoadController(MyVideoView videoView, PlayerControlApi controller, DanmakuView danmuView) {
        this.videoView = videoView;
        this.controller = controller;
        this.danmuView = danmuView;
        this.danmakuContext = DanmakuContext.create();
        // 不能用库默认的 updateMethod=0(时钟跟屏幕刷新率走):它把每帧推进下限写死 16ms,面板 120Hz 时弹幕会跑到 ~1.9x
        this.danmakuContext.updateMethod = 2;
        if (this.videoView != null) {
            this.videoView.setDanmuView(this.danmuView);
        }
        applySettings(false);
    }

    /**
     * 换绑播放器实例(空闲 TTL 释放后页面重建引擎时用,见 `PlaybackEngine.IDLE_RELEASE_DELAY_MS`)。
     * 弹幕视图属于页面,但必须挂到**当前**播放器上才会被驱动 —— 否则重建后弹幕静默失效。
     */
    public void setVideoView(MyVideoView videoView) {
        this.videoView = videoView;
        if (videoView != null && danmuView != null) {
            videoView.setDanmuView(danmuView);
        }
    }

    public void applySettings(boolean reload) {
        if (danmuView == null || danmakuContext == null) return;
        if (!DanmuHelper.isOpen()) {
            releaseView();
            if (controller != null) controller.setHasDanmu(!TextUtils.isEmpty(danmuText));
            return;
        }
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        int maxLine = DanmuHelper.getMaxLine();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, maxLine);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, maxLine);
        danmakuContext.setMaximumLines(maxLines)
                .setScrollSpeedFactor(DanmuHelper.getSpeed())
                .setDanmakuTransparency(DanmuHelper.getAlpha())
                .setScaleTextSize(DanmuHelper.getSizeScale());
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3)
                .setDanmakuMargin(8);
        if (reload && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()) {
            prepare(danmuText);
        }
    }

    public void check(String danmu) {
        check(danmu, "", "");
    }

    public void check(String danmu, String title, String episode) {
        check(danmu, title, episode, null);
    }

    public void check(String danmu, String title, String episode, LoadCallback callback) {
        loadCallback = callback;
        temporarilyClosed = false;
        danmuText = TextUtils.isEmpty(danmu) ? "" : danmu.trim();
        danmuTitle = TextUtils.isEmpty(title) ? "" : title;
        danmuEpisode = TextUtils.isEmpty(episode) ? "" : episode;
        releaseView();
        boolean hasDanmu = !TextUtils.isEmpty(danmuText);
        if (controller != null) controller.setHasDanmu(hasDanmu);
        if (!hasDanmu || !DanmuHelper.isOpen()) {
            if (danmuView != null) danmuView.setVisibility(View.GONE);
            return;
        }
        if (danmuView != null) danmuView.setVisibility(View.VISIBLE);
        if (!isVideoReady()) {
            pendingPrepare = true;
            return;
        }
        prepare(danmuText);
    }

    public void startIfReady() {
        if (pendingPrepare && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen() && isVideoReady()) {
            pendingPrepare = false;
            prepare(danmuText);
            return;
        }
        startIfReady(loadSeq.get());
    }

    public void reset() {
        DanmakuApi.cancel();
        temporarilyClosed = false;
        danmuText = "";
        danmuTitle = "";
        danmuEpisode = "";
        pendingPrepare = false;
        loadCallback = null;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        if (controller != null) controller.setHasDanmu(false);
        releaseView();
    }

    public void close() {
        DanmakuApi.cancel();
        loadSeq.incrementAndGet();
        startedSeq = -1;
        pendingPrepare = false;
        releaseView();
    }

    public boolean toggle() {
        if (temporarilyClosed) {
            temporarilyClosed = false;
            reloadForPlayback();
            startIfReady();
            return true;
        }
        temporarilyClosed = true;
        close();
        return false;
    }

    public void reloadForPlayback() {
        temporarilyClosed = false;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        releaseView();
        pendingPrepare = !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen();
    }

    public void destroy() {
        reset();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void prepare(String danmu) {
        if (TextUtils.isEmpty(danmu)) return;
        pendingPrepare = false;
        int seq = loadSeq.incrementAndGet();
        startedSeq = -1;
        LOG.i("echo-danmu load title: " + safeLog(danmuTitle) + ", episode: " + safeLog(danmuEpisode) + ", source: " + getSourceSummary(danmu));
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        executor.execute(() -> {
            Parser parser = new Parser(danmu, () -> seq != loadSeq.get());
            if (seq != loadSeq.get()) return;
            int danmuCount = parser.getDanmuCount();
            LOG.i("echo-danmu parsed count: " + danmuCount);
            if (danmuView == null) return;
            danmuView.post(() -> {
                if (seq != loadSeq.get() || danmakuContext == null) return;
                try {
                    danmuView.release();
                    if (videoView != null) videoView.setDanmuView(danmuView);
                    if (danmuCount <= 0) {
                        LOG.e("echo-danmu empty after parse");
                        danmuView.setVisibility(View.GONE);
                        notifyLoadFailed(seq);
                        return;
                    }
                    danmuView.prepare(parser, danmakuContext);
                    clearLoadCallback(seq);
                    danmuView.setVisibility(DanmuHelper.isOpen() ? View.VISIBLE : View.GONE);
                    startIfReady(seq);
                    danmuView.postDelayed(() -> startIfReady(seq), 300);
                    danmuView.postDelayed(() -> startIfReady(seq), 1000);
                } catch (Throwable th) {
                    LOG.e("echo-danmu prepare error: " + th.getMessage());
                    danmuView.setVisibility(View.GONE);
                    notifyLoadFailed(seq);
                }
            });
        });
    }

    private void clearLoadCallback(int seq) {
        if (seq == loadSeq.get()) loadCallback = null;
    }

    private void notifyLoadFailed(int seq) {
        if (seq != loadSeq.get() || loadCallback == null) return;
        LoadCallback callback = loadCallback;
        loadCallback = null;
        callback.onFailed();
    }

    private void startIfReady(int seq) {
        if (seq != loadSeq.get()
                || seq == startedSeq
                || videoView == null
                || !videoView.isPlaying()
                || danmuView == null
                || !danmuView.isPrepared()
                || !DanmuHelper.isOpen()) {
            return;
        }
        long position = videoView.getCurrentPosition();
        danmuView.setVisibility(View.VISIBLE);
        danmuView.seekTo(position);
        danmuView.start(position);
        startedSeq = seq;
        LOG.i("echo-danmu start at: " + position);
    }

    private boolean isVideoReady() {
        if (videoView == null) return false;
        int state = videoView.getCurrentPlayState();
        return state == VideoView.STATE_PREPARED
                || state == VideoView.STATE_BUFFERED
                || state == VideoView.STATE_PLAYING;
    }

    private void releaseView() {
        if (danmuView == null) return;
        try {
            danmuView.release();
        } catch (Throwable th) {
            LOG.e("DanmuLoadController", "danmu view release failed", th);
        }
        danmuView.setVisibility(View.GONE);
    }

    private String getSourceSummary(String danmu) {
        if (TextUtils.isEmpty(danmu)) return "";
        if (danmu.startsWith("http") || danmu.startsWith("file")) return danmu;
        return "inline xml length=" + danmu.length();
    }

    private String safeLog(String text) {
        return TextUtils.isEmpty(text) ? "" : text;
    }
}
