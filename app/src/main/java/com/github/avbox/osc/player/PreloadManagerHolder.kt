package com.github.avbox.osc.player

import android.content.Context
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.engine.PlayerCache
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TreeMap

object PreloadManagerHolder {

    private const val TAG = "PreloadManager"

    private const val PRELOAD_SECONDS_DEFAULT = 60
    private const val PRELOAD_SECONDS_MIN = 20
    private const val PRELOAD_SECONDS_MAX = 120

    @Volatile
    private var sPreloadHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var sStartPosMs = 0L

    @Volatile
    private var sRangeMs = PRELOAD_SECONDS_DEFAULT * 1000L

    private var sManager: DefaultPreloadManager? = null

    private var sPreloadThread: HandlerThread? = null

    private val sRegistry: MutableMap<String, MediaItem> = HashMap()

    private val sPreloadTargets: MutableMap<String, String> =
        object : LinkedHashMap<String, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > 8
        }

    private val _completedUrl = MutableStateFlow<String?>(null)

    val completedUrl: StateFlow<String?> = _completedUrl.asStateFlow()

    @JvmStatic
    fun enabled(): Boolean = KV.get(KVKeys.PRELOAD_NEXT_EPISODE, false)

    @JvmStatic
    @Synchronized
    fun preload(context: Context, url: String?, headers: Map<String, String>?, startPosMs: Long) {
        if (!enabled() || url.isNullOrEmpty()) {
            return
        }
        if (MediaSources.isoHeaders(headers)) {
            AppLog.i("echo-iso-preload-skip: " + url)
            return
        }
        try {
            val manager = get(context.applicationContext)
            val key = keyOf(url, headers)
            if (sRegistry.containsKey(key)) {
                AppLog.i("echo-preload-already: " + url)
                return
            }
            sStartPosMs = maxOf(0L, startPosMs)
            sRangeMs = preloadRangeMs()
            _completedUrl.value = null
            sPreloadHeaders = if (headers == null) emptyMap() else HashMap(headers)
            sPreloadTargets[url] = headersSignature(headers)
            val item = MediaSources.buildPreloadMediaItem(url, headers)
            sRegistry[key] = item
            manager.add(item, 0)
            manager.invalidate()
            AppLog.i("echo-preload-start: " + url)
        } catch (th: Throwable) {
            AppLog.e("echo-preload-error: " + url + " " + th)
        }
    }

    @JvmStatic
    @Synchronized
    fun hasActivePreload(): Boolean = sRegistry.isNotEmpty()

    @JvmStatic
    @Synchronized
    fun isPreloadTargetUrl(url: String?, headers: Map<String, String>?): Boolean {
        if (url.isNullOrEmpty()) {
            return false
        }
        val preloadSignature = sPreloadTargets[url] ?: return false
        return preloadSignature == headersSignature(headers)
    }

    private fun preloadRangeMs(): Long {
        var seconds = PRELOAD_SECONDS_DEFAULT
        try {
            seconds = KV.get(KVKeys.PRELOAD_DURATION, PRELOAD_SECONDS_DEFAULT)
        } catch (th: Throwable) {
            AppLog.e("PreloadManagerHolder", "preload duration KV read failed, use default", th)
        }
        seconds = maxOf(PRELOAD_SECONDS_MIN, minOf(PRELOAD_SECONDS_MAX, seconds))
        return seconds * 1000L
    }

    @JvmStatic
    @Synchronized
    fun clearAll() {
        _completedUrl.value = null
        val manager = sManager ?: return
        if (sRegistry.isNotEmpty()) {
            AppLog.i("echo-preload-clear: " + sRegistry.size)
            sRegistry.clear()
        }
        try {
            manager.reset()
        } catch (th: Throwable) {
            AppLog.e("echo-preload-clear-error " + th)
        }
    }

    @JvmStatic
    @Synchronized
    fun release() {
        val manager = sManager
        if (manager != null) {
            try {
                manager.release()
            } catch (th: Throwable) {
                AppLog.e("PreloadManagerHolder", "preload manager release failed", th)
            }
            sManager = null
        }
        sRegistry.clear()
        sPreloadTargets.clear()
        sPreloadHeaders = emptyMap()
        _completedUrl.value = null
    }

    private fun get(appContext: Context): DefaultPreloadManager {
        var manager = sManager
        if (manager == null) {
            val control = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
                DefaultPreloadManager.PreloadStatus.specifiedRangeCached(sStartPosMs, sRangeMs)
            }
            manager = DefaultPreloadManager.Builder(appContext, control)
                .setMediaSourceFactory(PreloadMediaSourceFactory(appContext))
                .setCache(PlayerCache.getSharedCache(appContext))
                .setDataSourceFactory(PreloadDataSourceFactory(appContext))
                .setPreloadLooper(preloadLooper())
                .build()
            manager.addListener(object : PreloadManagerListener {
                override fun onCompleted(mediaItem: MediaItem) {
                    val configuration = mediaItem.localConfiguration
                    val url = if (configuration == null) null else configuration.uri.toString()
                    _completedUrl.value = url
                    AppLog.i("echo-preload-complete: " + url)
                }

                override fun onError(exception: PreloadException) {
                    AppLog.e("echo-preload-error: " + exception + ", cause=" + exception.cause)
                }
            })
            sManager = manager
        }
        return manager
    }

    @JvmStatic
    @Synchronized
    fun preloadLooper(): Looper {
        var thread = sPreloadThread
        if (thread == null || !thread.isAlive) {
            thread = HandlerThread("avbox-preload", android.os.Process.THREAD_PRIORITY_AUDIO)
            thread.start()
            sPreloadThread = thread
        }
        return thread.looper
    }

    private fun keyOf(url: String, headers: Map<String, String>?): String =
        StringBuilder(url).append('\n').append(headersSignature(headers)).toString()

    private fun headersSignature(headers: Map<String, String>?): String {
        if (headers == null || headers.isEmpty()) {
            return ""
        }
        val sorted = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for ((key, value) in headers) {
            if (key != null && value != null) {
                sorted[key.trim { it <= ' ' }] = value.trim { it <= ' ' }
            }
        }
        val sb = StringBuilder()
        for ((key, value) in sorted) {
            sb.append(key).append(':').append(value).append(';')
        }
        return sb.toString()
    }

    private class PreloadDataSourceFactory(private val appContext: Context) : DataSource.Factory {

        override fun createDataSource(): DataSource =
            MediaSources.getInstance(appContext)
                .createDataSourceFactory(sPreloadHeaders)
                .createDataSource()
    }

    private class PreloadMediaSourceFactory(private val appContext: Context) : MediaSource.Factory {

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val configuration = mediaItem.localConfiguration
            val uri = if (configuration != null) configuration.uri.toString() else mediaItem.mediaId
            val headers = MediaSources.headersFrom(mediaItem)
            return MediaSources.getInstance(appContext).getMediaSource(uri, headers, true)
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(
            C.CONTENT_TYPE_OTHER,
            C.CONTENT_TYPE_HLS,
            C.CONTENT_TYPE_DASH,
            C.CONTENT_TYPE_RTSP,
        )

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
            return this
        }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
            return this
        }
    }
}
