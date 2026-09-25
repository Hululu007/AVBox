package com.github.tvbox.osc.ui.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Observer;
import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.github.tvbox.osc.player.PreloadManagerHolder;
import com.github.tvbox.osc.util.KV;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PreloadCoordinator {
    private static final long EVALUATE_DELAY_MS = 2000L;
    private static final long BUFFERING_COOLDOWN_MS = 10_000L;
    private static final long CACHE_TTL_MS = 60_000L;
    private static final String PRELOAD_KEY_SUFFIX = "-preload";

    public static final class Snapshot {
        public final Context context;
        public final String sourceKey;
        public final String playFlag;
        public final String currentKey;
        public final String nextKey;
        public final String nextUrl;
        public final String nextSubtitleKey;
        public final long startSkipMs;
        public final boolean exoKernel;

        public Snapshot(Context context, String sourceKey, String playFlag, String currentKey,
                        String nextKey, String nextUrl, String nextSubtitleKey, long startSkipMs, boolean exoKernel) {
            this.context = context;
            this.sourceKey = sourceKey;
            this.playFlag = playFlag;
            this.currentKey = currentKey;
            this.nextKey = nextKey;
            this.nextUrl = nextUrl;
            this.nextSubtitleKey = nextSubtitleKey;
            this.startSkipMs = startSkipMs;
            this.exoKernel = exoKernel;
        }
    }

    private final SourceViewModel sourceViewModel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean evaluatePending = new AtomicBoolean(false);

    private String requestToken;
    private String gaveUpKey;
    private String preloadedKey;
    private Snapshot activeSnapshot;
    private long bufferingCooldownUntil;
    private boolean observing;
    private JSONObject cachedInfo;
    private String cachedKey;
    private long cachedAt;

    private final Observer<JSONObject> preloadResultObserver = new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            handlePreloadResult(info);
        }
    };

    public PreloadCoordinator(SourceViewModel sourceViewModel) {
        this.sourceViewModel = sourceViewModel;
        MutableLiveData<JSONObject> channel = sourceViewModel.preloadResult;
        if (channel != null) {
            channel.observeForever(preloadResultObserver);
            observing = true;
        }
    }

    public void scheduleEvaluate(Snapshot snapshot) {
        if (snapshot == null || !PreloadManagerHolder.enabled()) {
            LOG.i("echo-preload-skip: " + (snapshot == null ? "no next episode" : "switch off"));
            return;
        }
        postEvaluate(snapshot, EVALUATE_DELAY_MS);
    }

    private void postEvaluate(Snapshot snapshot, long delayMs) {
        if (!evaluatePending.compareAndSet(false, true)) return;
        handler.postDelayed(() -> {
            evaluatePending.set(false);
            evaluate(snapshot);
        }, delayMs);
    }

    public void invalidate() {
        handler.removeCallbacksAndMessages(null);
        evaluatePending.set(false);
        requestToken = null;
        activeSnapshot = null;
    }

    public void dropPreloadData() {
        preloadedKey = null;
        PreloadManagerHolder.clearAll();
    }

    public void onMainPlayerBuffering() {
        if (!PreloadManagerHolder.enabled()) return;
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS;
        PreloadManagerHolder.clearAll();
        preloadedKey = null;
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        evaluatePending.set(false);
        requestToken = null;
        activeSnapshot = null;
        clearCache();
        if (observing && sourceViewModel != null) {
            sourceViewModel.preloadResult.removeObserver(preloadResultObserver);
            observing = false;
        }
        PreloadManagerHolder.release();
    }

    private void evaluate(Snapshot snapshot) {
        if (!PreloadManagerHolder.enabled()) return;
        if (!snapshot.exoKernel) {
            LOG.i("echo-preload-skip: non-exo kernel");
            return;
        }
        long cooldownRemain = bufferingCooldownUntil - System.currentTimeMillis();
        if (cooldownRemain > 0) {
            LOG.i("echo-preload-skip: buffering cooldown, retry in " + cooldownRemain + "ms");
            postEvaluate(snapshot, cooldownRemain);
            return;
        }
        if (snapshot.nextKey.equals(snapshot.currentKey)) return;
        if (snapshot.currentKey.equals(gaveUpKey)) return;
        if (snapshot.nextKey.equals(preloadedKey)) {
            LOG.i("echo-preload-skip: already preloaded");
            return;
        }
        if (preloadedKey != null) {
            dropPreloadData();
        }
        if (snapshot.nextKey.equals(requestToken)) {
            LOG.i("echo-preload-skip: resolving in-flight");
            return;
        }
        if (Jianpian.isJpUrl(snapshot.nextUrl)) {
            gaveUp(snapshot);
            return;
        }
        activeSnapshot = snapshot;
        requestToken = snapshot.nextKey + PRELOAD_KEY_SUFFIX;
        LOG.i("echo-preload-resolve: " + snapshot.nextUrl);
        sourceViewModel.getPlayForPreload(
                snapshot.sourceKey,
                snapshot.playFlag,
                snapshot.nextKey + PRELOAD_KEY_SUFFIX,
                snapshot.nextUrl,
                snapshot.nextSubtitleKey + PRELOAD_KEY_SUFFIX);
    }

    private void handlePreloadResult(JSONObject info) {
        final Snapshot snapshot = activeSnapshot;
        String token = requestToken;
        requestToken = null;
        if (snapshot == null || token == null) {
            LOG.i("echo-preload-result-drop: no active snapshot (late result)");
            return;
        }
        if (info == null || !token.equals(info.optString("proKey", ""))) {
            LOG.i("echo-preload-giveup: stale result, target=" + token);
            gaveUp(snapshot);
            return;
        }
        String msg = info.optString("msg", "");
        boolean parse = info.optString("parse", "1").equals("1");
        boolean jx = info.optString("jx", "0").equals("1");
        String playUrl = info.optString("playUrl", "");
        Object rawUrl = info.opt("url");
        String url = rawUrl instanceof org.json.JSONArray ? rawUrl.toString()
                : (rawUrl == null ? "" : String.valueOf(rawUrl));
        if (parse || jx || !playUrl.isEmpty() || !msg.isEmpty()
                || url.isEmpty()
                || url.startsWith("[")
                || url.startsWith("data:application")
                || url.startsWith("tvbox-xg:")) {
            String reason = parse ? "parse=1" : jx ? "jx=1" : !playUrl.isEmpty() ? "playUrl=" + playUrl
                    : !msg.isEmpty() ? "msg=" + msg : url.isEmpty() ? "empty url"
                    : url.startsWith("[") ? "array url" : url.startsWith("data:application") ? "data: url" : "tvbox-xg";
            LOG.i("echo-preload-giveup: " + reason);
            gaveUp(snapshot);
            return;
        }
        if (isLocalProxyUrl(url)) {
            LOG.i("echo-preload-giveup: local proxy url");
            gaveUp(snapshot);
            return;
        }
        if (url.contains(".m3u8")
                && KV.get(HawkConfig.M3U8_PURIFY, false)
                && !DefaultConfig.noAd(snapshot.playFlag)) {
            LOG.i("echo-preload-giveup: m3u8 purify on, url=" + url);
            gaveUp(snapshot);
            return;
        }
        HashMap<String, String> headers = extractHeaders(info);
        long startPos = snapshot.startSkipMs;
        // 无痕:预载起点同样不认旧进度,否则自动连播的下一集会带着上次的位置起播
        if (!HistoryHelper.isIncognito()) {
            try {
                Object history = CacheManager.getCache(MD5.string2MD5(snapshot.nextKey));
                long rec = 0;
                if (history instanceof Long) {
                    rec = (Long) history;
                } else if (history instanceof String) {
                    rec = Long.parseLong((String) history);
                }
                startPos = Math.max(startPos, rec);
            } catch (Throwable ignored) {
                LOG.d("PreloadCoordinator", "read saved progress failed, use snapshot start");
            }
        }
        preloadedKey = snapshot.nextKey;
        LOG.i("echo-preload-resolve-ok: " + url);
        try {
            info.put("proKey", snapshot.nextKey);
            info.put("subtKey", snapshot.nextSubtitleKey);
        } catch (Throwable ignored) {
            LOG.d("PreloadCoordinator", "mark preload result keys failed");
        }
        cachedInfo = info;
        cachedKey = snapshot.nextKey;
        cachedAt = System.currentTimeMillis();
        PreloadManagerHolder.preload(snapshot.context, url, headers, startPos);
    }

    public JSONObject consumeResult(String realKey) {
        if (cachedInfo == null || cachedKey == null) return null;
        if (!cachedKey.equals(realKey)) return null;
        if (System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) {
            LOG.i("echo-preload-cache-expired: " + realKey);
            clearCache();
            return null;
        }
        JSONObject result = cachedInfo;
        clearCache();
        LOG.i("echo-preload-cache-hit: " + realKey);
        return result;
    }

    private void clearCache() {
        cachedInfo = null;
        cachedKey = null;
    }

    private void gaveUp(Snapshot snapshot) {
        gaveUpKey = snapshot.currentKey;
    }

    private static boolean isLocalProxyUrl(String url) {
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost");
    }

    private static HashMap<String, String> extractHeaders(JSONObject info) {
        return PlayerHelper.extractPlayHeaders(info);
    }
}
