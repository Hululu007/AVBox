package com.github.avbox.osc.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.WatchProgressStore
import com.github.avbox.osc.net.Preconnect
import com.github.avbox.osc.sourcedata.SourceHelper
import com.github.avbox.osc.sourcedata.SourceViewModel
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class PreloadCoordinator(
    private val sourceViewModel: SourceViewModel?,
    private val onReplayReady: () -> Unit,
) {

    class Snapshot(
        @JvmField val context: Context,
        @JvmField val sourceKey: String,
        @JvmField val playFlag: String?,
        @JvmField val currentKey: String?,
        @JvmField val nextKey: String,
        @JvmField val nextUrl: String?,
        @JvmField val nextSubtitleKey: String,
        @JvmField val startSkipMs: Long,
        @JvmField val exoKernel: Boolean,
    )

    private class CachedEntry(val info: JSONObject, val at: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val evaluatePending = AtomicBoolean(false)

    private var requestToken: String? = null
    private var gaveUpKey: String? = null
    private var preloadedKey: String? = null
    private var activeSnapshot: Snapshot? = null
    private var bufferingCooldownUntil = 0L
    private var replayReadyPending = false

    private val cache = LinkedHashMap<String, CachedEntry>()

    private val bufferingYield = Runnable {
        AppLog.i("echo-preload-yield: sustained buffering")
        PreloadManagerHolder.clearAll()
        preloadedKey = null
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS
    }

    fun scheduleEvaluate(snapshot: Snapshot?) {
        if (snapshot == null || !PreloadManagerHolder.enabled()) {
            AppLog.i("echo-preload-skip: " + if (snapshot == null) "no next episode" else "switch off")
            return
        }
        handler.removeCallbacks(bufferingYield)
        postEvaluate(snapshot, EVALUATE_DELAY_MS)
    }

    private fun postEvaluate(snapshot: Snapshot, delayMs: Long) {
        if (!evaluatePending.compareAndSet(false, true)) return
        handler.postDelayed({
            evaluatePending.set(false)
            evaluate(snapshot)
        }, delayMs)
    }

    fun invalidate() {
        handler.removeCallbacksAndMessages(null)
        evaluatePending.set(false)
        replayReadyPending = false
        requestToken = null
        activeSnapshot = null
    }

    fun dropPreloadData() {
        preloadedKey = null
        PreloadManagerHolder.clearAll()
    }

    fun onMainPlayerBuffering() {
        if (!PreloadManagerHolder.enabled()) return
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS
        replayReadyPending = true
        handler.removeCallbacks(bufferingYield)
        handler.postDelayed(bufferingYield, BUFFERING_YIELD_MS)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        evaluatePending.set(false)
        replayReadyPending = false
        requestToken = null
        activeSnapshot = null
        clearCache()
        PreloadManagerHolder.release()
    }

    private fun evaluate(snapshot: Snapshot) {
        if (!PreloadManagerHolder.enabled()) return
        if (!snapshot.exoKernel) {
            AppLog.i("echo-preload-skip: non-exo kernel")
            return
        }
        if (replayReadyPending) {
            replayReadyPending = false
            if (snapshot.nextKey == preloadedKey && PreloadManagerHolder.completedUrl.value != null) {
                AppLog.i("echo-preload-ready-replay")
                onReplayReady()
            }
        }
        val cooldownRemain = bufferingCooldownUntil - System.currentTimeMillis()
        if (cooldownRemain > 0) {
            AppLog.i("echo-preload-skip: buffering cooldown, retry in " + cooldownRemain + "ms")
            postEvaluate(snapshot, cooldownRemain)
            return
        }
        if (snapshot.nextKey == snapshot.currentKey) return
        if (gaveUpKey != null && snapshot.currentKey == gaveUpKey) return
        if (snapshot.nextKey == preloadedKey) {
            AppLog.i("echo-preload-skip: already preloaded")
            return
        }
        if (preloadedKey != null) {
            dropPreloadData()
        }
        if (snapshot.nextKey == requestToken) {
            AppLog.i("echo-preload-skip: resolving in-flight")
            return
        }
        val nextUrl = requireNotNull(snapshot.nextUrl)
        activeSnapshot = snapshot
        requestToken = snapshot.nextKey + PRELOAD_KEY_SUFFIX
        AppLog.i("echo-preload-resolve: " + nextUrl)
        val vm = requireNotNull(sourceViewModel)
        vm.getPlayForPreload(
            snapshot.sourceKey,
            snapshot.playFlag,
            snapshot.nextKey + PRELOAD_KEY_SUFFIX,
            nextUrl,
            snapshot.nextSubtitleKey + PRELOAD_KEY_SUFFIX,
        )
    }

    suspend fun handlePreloadResult(info: JSONObject?) {
        val snapshot = activeSnapshot
        val token = requestToken
        requestToken = null
        if (snapshot == null || token == null) {
            AppLog.i("echo-preload-result-drop: no active snapshot (late result)")
            return
        }
        if (info == null || token != info.optString("proKey", "")) {
            AppLog.i("echo-preload-giveup: stale result, target=" + token)
            gaveUp(snapshot)
            return
        }
        val msg = info.optString("msg", "")
        val parse = info.optString("parse", "1") == "1"
        val jx = info.optString("jx", "0") == "1"
        val playUrl = info.optString("playUrl", "")
        val rawUrl = info.opt("url")
        val url = if (rawUrl is JSONArray) {
            rawUrl.toString()
        } else if (rawUrl == null) {
            ""
        } else {
            rawUrl.toString()
        }
        if (parse || jx || playUrl.isNotEmpty() || msg.isNotEmpty() ||
            url.isEmpty() ||
            url.startsWith("[") ||
            url.startsWith("data:application")
        ) {
            val reason = if (parse) {
                "parse=1"
            } else if (jx) {
                "jx=1"
            } else if (playUrl.isNotEmpty()) {
                "playUrl=" + playUrl
            } else if (msg.isNotEmpty()) {
                "msg=" + msg
            } else if (url.isEmpty()) {
                "empty url"
            } else if (url.startsWith("[")) {
                "array url"
            } else {
                "data: url"
            }
            AppLog.i("echo-preload-giveup: " + reason)
            gaveUp(snapshot)
            return
        }
        if (isLocalProxyUrl(url)) {
            AppLog.i("echo-preload-giveup: local proxy url")
            gaveUp(snapshot)
            return
        }
        if (url.contains(".m3u8") &&
            KV.get(KVKeys.M3U8_PURIFY, false) &&
            !DefaultConfig.noAd(snapshot.playFlag)
        ) {
            AppLog.i("echo-preload-giveup: m3u8 purify on, url=" + url)
            gaveUp(snapshot)
            return
        }
        val headers = extractHeaders(info)
        var startPos = snapshot.startSkipMs
        if (!HistoryHelper.isIncognito()) {
            try {
                val history = WatchProgressStore.submitAndAwait {
                    AppGraph.cacheRepository.get(MD5.string2MD5(snapshot.nextKey))
                }
                var rec = 0L
                if (history is Long) {
                    rec = history
                } else if (history is String) {
                    rec = history.toLong()
                }
                startPos = maxOf(startPos, rec)
            } catch (ignored: Throwable) {
                AppLog.d("PreloadCoordinator", "read saved progress failed, use snapshot start")
            }
        }
        preloadedKey = snapshot.nextKey
        AppLog.i("echo-preload-resolve-ok: " + url)
        Preconnect.warm(url, headers)
        try {
            info.put("proKey", snapshot.nextKey)
            info.put("subtKey", snapshot.nextSubtitleKey)
        } catch (ignored: Throwable) {
            AppLog.d("PreloadCoordinator", "mark preload result keys failed")
        }
        putCache(snapshot.nextKey, info)
        PreloadManagerHolder.preload(snapshot.context, url, headers, startPos)
    }

    fun consumeResult(realKey: String?): JSONObject? {
        if (realKey == null) return null
        if (!PreloadManagerHolder.enabled()) {
            if (cache.isNotEmpty()) cache.clear()
            return null
        }
        val entry = cache.remove(realKey) ?: return null
        if (PreloadCachePolicy.isExpired(System.currentTimeMillis(), entry.at)) {
            AppLog.i("echo-preload-cache-expired: " + realKey)
            return null
        }
        AppLog.i("echo-preload-cache-hit: " + realKey + " size=" + cache.size)
        return entry.info
    }

    private fun clearCache() {
        cache.clear()
    }

    private fun putCache(key: String, info: JSONObject) {
        val now = System.currentTimeMillis()
        val entries = cache.entries.iterator()
        while (entries.hasNext()) {
            if (PreloadCachePolicy.isExpired(now, entries.next().value.at)) entries.remove()
        }
        cache[key] = CachedEntry(info, now)
        val keys = cache.keys.iterator()
        while (PreloadCachePolicy.sizeExceeded(cache.size) && keys.hasNext()) {
            keys.next()
            keys.remove()
        }
        AppLog.i("echo-preload-cache-put: " + key + " size=" + cache.size)
    }

    private fun gaveUp(snapshot: Snapshot) {
        gaveUpKey = snapshot.currentKey
    }

    companion object {
        private const val EVALUATE_DELAY_MS = 2000L

        private const val BUFFERING_YIELD_MS = 4000L
        private const val BUFFERING_COOLDOWN_MS = 5_000L
        private const val PRELOAD_KEY_SUFFIX = "-preload"

        private fun isLocalProxyUrl(url: String): Boolean =
            url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1") ||
                url.startsWith("http://localhost") || url.startsWith("https://localhost")

        private fun extractHeaders(info: JSONObject): HashMap<String, String>? =
            SourceHelper.extractPlayHeaders(info)
    }
}
