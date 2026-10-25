package com.github.avbox.osc.player.engine

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.player.core.HttpHeaders
import com.github.avbox.osc.player.core.OkHttpDataSource
import com.github.avbox.osc.player.engine.iso.BdavDataSource
import com.github.avbox.osc.player.engine.iso.BdavLoadErrorPolicy
import com.github.avbox.osc.player.engine.iso.IsoDataSource
import com.github.avbox.osc.player.engine.iso.IsoExtractorsFactory
import com.github.avbox.osc.player.engine.iso.IsoPlaylistState
import com.github.avbox.osc.player.engine.iso.withPlaylistTargets
import com.github.avbox.osc.player.engine.iso.IsoThroughput
import com.github.avbox.osc.player.engine.iso.M2tsDataSource
import com.github.avbox.osc.util.AppLog
import java.util.HashMap
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient

class MediaSources(
    context: Context,
    private var client: OkHttpClient? = null,
    private var cache: Cache? = null,
) {

    private val appContext: Context = context.applicationContext

    fun getMediaSource(uri: String): MediaSource = getMediaSource(uri, null, false)

    fun getMediaSource(uri: String, headers: Map<String, String>?): MediaSource =
        getMediaSource(uri, headers, false)

    fun getMediaSource(uri: String, isCache: Boolean): MediaSource =
        getMediaSource(uri, null, isCache)

    fun getMediaSource(uri: String, headers: Map<String, String>?, isCache: Boolean): MediaSource =
        getMediaSource(uri, headers, isCache, false, inferContentType(uri, headers))

    fun preloadTargetMediaSource(uri: String, headers: Map<String, String>?): MediaSource =
        getMediaSource(uri, headers, true, true, inferContentType(uri, headers))

    fun hlsMediaSource(uri: String, headers: Map<String, String>?): MediaSource =
        getMediaSource(uri, headers, false, false, C.TYPE_HLS)

    private fun getMediaSource(
        uri: String,
        headers: Map<String, String>?,
        isCache: Boolean,
        useDefaultCacheKey: Boolean,
        contentType: Int,
    ): MediaSource {
        val contentUri = Uri.parse(uri)
        if ("rtsp" == contentUri.scheme) {
            return RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri))
        }
        if (isoHeaders(headers) || contentType == TYPE_ISO) {
            return createIsoMediaSource(uri, headers)
        }
        val requestHeaders = toRequestHeaders(headers)
        val mediaItem = buildMediaItem(uri, headers)
        var factory: DataSource.Factory = createDataSourceFactory(requestHeaders)
        if (isCache) {
            factory = cacheDataSourceFactory(factory, requestHeaders, useDefaultCacheKey)
        }
        return when (contentType) {
            C.TYPE_DASH -> DashMediaSource.Factory(factory).createMediaSource(mediaItem)
            C.TYPE_HLS -> HlsMediaSource.Factory(factory)
                .setLoadErrorHandlingPolicy(HlsErrorHandlingPolicy())
                .createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
        }
    }

    fun isoMediaSource(uri: String, headers: Map<String, String>?, seekMs: Long): MediaSource =
        createIsoMediaSource(uri, headers, seekMs)

    private fun createIsoMediaSource(
        uri: String,
        headers: Map<String, String>?,
        seekMs: Long = 0L,
    ): MediaSource {
        IsoThroughput.beginSession()
        IsoPlaylistState.reset()
        val mediaItem = buildMediaItem(uri, headers)
        val concatRef = AtomicReference<ConcatenatingMediaSource>()
        val appended = AtomicBoolean(false)
        val first = isoProgressiveSource(
            mediaItem,
            IsoDataSource.factory(headers, seekMs) { selection ->
                if (!selection.multiSegment) return@factory
                if (!appended.compareAndSet(false, true)) return@factory
                val concat = concatRef.get() ?: return@factory
                val children = selection.withPlaylistTargets().drop(1).map { segment ->
                    isoProgressiveSource(mediaItem, IsoDataSource.factory(headers, segment))
                }
                concat.addMediaSources(children)
                AppLog.i(
                    "echo-iso-playlist-append:${children.size}:total=${selection.segments.size}:" +
                        "duration=${selection.durationMs}",
                )
            },
        )
        val concat = ConcatenatingMediaSource(false, true, ShuffleOrder.DefaultShuffleOrder(0), first)
        concatRef.set(concat)
        return concat
    }

    private fun isoProgressiveSource(
        mediaItem: MediaItem,
        factory: IsoDataSource.Factory,
    ): MediaSource = ProgressiveMediaSource.Factory(
        M2tsDataSource.Factory(factory),
        IsoExtractorsFactory(),
    ).createMediaSource(mediaItem)

    fun bdavMediaSource(uri: String, headers: Map<String, String>?): MediaSource {
        IsoThroughput.beginSession()
        IsoPlaylistState.reset()
        val dataSourceFactory = BdavDataSource.Factory(createDataSourceFactory(headers))
        return ProgressiveMediaSource.Factory(dataSourceFactory, IsoExtractorsFactory())
            .setLoadErrorHandlingPolicy(BdavLoadErrorPolicy())
            .createMediaSource(buildMediaItem(uri, headers))
    }

    fun createDataSourceFactory(headers: Map<String, String>?): DataSource.Factory {
        val normalized = toRequestHeaders(headers)
        var userAgent: String? = null
        val requestHeaders = HashMap<String, String>()
        for (entry in normalized.entries) {
            if ("User-Agent".equals(entry.key, ignoreCase = true)) {
                userAgent = entry.value
            } else {
                requestHeaders[entry.key] = entry.value
            }
        }
        val httpFactory = OkHttpDataSource.Factory(client ?: HttpClients.itvClient() ?: FallbackClient.INSTANCE)
        httpFactory.setUserAgent(userAgent)
        httpFactory.setDefaultRequestProperties(requestHeaders)
        return DefaultDataSource.Factory(appContext, httpFactory)
    }

    fun createDataSourceFactory(mediaItem: MediaItem): DataSource.Factory =
        createDataSourceFactory(headersFrom(mediaItem))

    private fun cacheDataSourceFactory(
        upstream: DataSource.Factory,
        headers: Map<String, String>?,
        useDefaultCacheKey: Boolean,
    ): DataSource.Factory {
        val cache = this.cache ?: PlayerCache.getSharedCache(appContext)
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (!useDefaultCacheKey) {
            val keySuffix = headerKeySuffix(headers)
            if (keySuffix.isNotEmpty()) {
                factory.setCacheKeyFactory { dataSpec -> dataSpec.uri.toString() + keySuffix }
            }
        }
        return factory
    }

    companion object {

        const val HEADER_FORMAT = HttpHeaders.HEADER_FORMAT

        const val HEADER_ISO = HttpHeaders.HEADER_ISO

        const val TYPE_ISO = 100

        const val EXTRA_HEADERS = "avbox.extras.httpHeaders"

        private object FallbackClient {
            val INSTANCE: OkHttpClient = OkHttpClient.Builder().build()
        }

        @Volatile
        private var instance: MediaSources? = null

        @JvmStatic
        fun getInstance(context: Context): MediaSources {
            val existing = instance
            if (existing != null) return existing
            synchronized(MediaSources::class.java) {
                instance?.let { return it }
                val created = MediaSources(context.applicationContext)
                instance = created
                return created
            }
        }

        @JvmStatic
        fun buildMediaItem(uri: String, headers: Map<String, String>?): MediaItem {
            val extras = Bundle()
            extras.putSerializable(EXTRA_HEADERS, toRequestHeaders(headers))
            val requestMetadata = MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .setExtras(extras)
                .build()
            return MediaItem.Builder()
                .setUri(uri)
                .setRequestMetadata(requestMetadata)
                .build()
        }

        @JvmStatic
        fun buildPreloadMediaItem(uri: String, headers: Map<String, String>?): MediaItem {
            val item = buildMediaItem(uri, headers)
            val mimeType = mimeTypeOf(inferContentType(uri, headers)) ?: return item
            return item.buildUpon().setMimeType(mimeType).build()
        }

        @JvmStatic
        fun headersFrom(mediaItem: MediaItem): Map<String, String>? {
            val extras = mediaItem.requestMetadata.extras ?: return null
            val stored = extras.getSerializable(EXTRA_HEADERS)
            @Suppress("UNCHECKED_CAST")
            return stored as? Map<String, String>
        }

        @JvmStatic
        fun headerKeySuffix(headers: Map<String, String>?): String {
            if (headers == null || headers.isEmpty()) {
                return ""
            }
            val sorted = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            for (entry in headers.entries) {
                val key = entry.key
                val value = entry.value
                if (key != null && value != null) {
                    if (HEADER_ISO.equals(key, ignoreCase = true)) continue
                    sorted[key.trim { it <= ' ' }] = value.trim { it <= ' ' }
                }
            }
            if (sorted.isEmpty()) {
                return ""
            }
            val sb = StringBuilder()
            for ((key, value) in sorted) {
                sb.append('\n').append(key).append(':').append(value).append(';')
            }
            return sb.toString()
        }

        @JvmStatic
        fun toRequestHeaders(headers: Map<String, String>?): HashMap<String, String> =
            HttpHeaders.toRequestHeaders(headers)

        @JvmStatic
        fun inferContentType(fileName: String, headers: Map<String, String>?): Int {
            val formatType = inferFormatContentType(headers)
            if (formatType != C.TYPE_OTHER) {
                return formatType
            }
            val name = fileName.lowercase(Locale.getDefault())
            return when {
                name.contains(".mpd") || name.contains("type=mpd") || name.contains("type=dash")
                    || name.contains("format=mpd") || name.contains("format=dash") -> C.TYPE_DASH
                isHlsUri(name) -> C.TYPE_HLS
                else -> C.TYPE_OTHER
            }
        }

        @JvmStatic
        fun inferFormatContentType(headers: Map<String, String>?): Int {
            if (headers == null || !headers.containsKey(HEADER_FORMAT)) {
                return if (isoHeaders(headers)) TYPE_ISO else C.TYPE_OTHER
            }
            val format = (headers[HEADER_FORMAT] ?: return C.TYPE_OTHER).trim { it <= ' ' }.lowercase(Locale.getDefault())
            if (isIsoFormat(format)) {
                return TYPE_ISO
            }
            if (format == "hls" || format.contains("mpegurl") || format.contains("m3u8")) {
                return C.TYPE_HLS
            }
            if (format == "dash" || format == "mpd" || format.contains("dash+xml")) {
                return C.TYPE_DASH
            }
            return C.TYPE_OTHER
        }

        @JvmStatic
        fun mimeTypeOf(contentType: Int): String? = when (contentType) {
            C.TYPE_HLS -> MimeTypes.APPLICATION_M3U8
            C.TYPE_DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        @JvmStatic
        fun isIsoFormat(format: String?): Boolean {
            val value = format?.trim()?.lowercase(Locale.ROOT) ?: return false
            if (value.isEmpty()) return false
            val subtype = value.substringAfterLast('/')
            return subtype == "iso" || subtype == "x-iso" || subtype.contains("iso9660")
        }

        @JvmStatic
        fun isoHeaders(headers: Map<String, String>?): Boolean =
            headers != null && headers.keys.any { HEADER_ISO.equals(it, ignoreCase = true) }

        @JvmStatic
        fun withIsoHeader(headers: Map<String, String>?): HashMap<String, String> {
            val copy = HashMap<String, String>()
            if (headers != null) {
                for ((key, value) in headers) {
                    copy[key] = value
                }
            }
            copy[HEADER_ISO] = "1"
            return copy
        }

        @JvmStatic
        fun isIsoUri(uri: String?): Boolean {
            if (uri.isNullOrEmpty() || uri.startsWith("data:", ignoreCase = true)) return false
            if (pathEndsWithIso(uri)) return true
            val target = extractQueryParam(uri, "url") ?: return false
            return pathEndsWithIso(target)
        }

        private fun pathEndsWithIso(url: String): Boolean =
            url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT).endsWith(".iso")

        private fun extractQueryParam(url: String, name: String): String? {
            val queryStart = url.indexOf('?')
            if (queryStart < 0 || queryStart == url.length - 1) return null
            val query = url.substring(queryStart + 1).substringBefore('#')
            for (pair in query.split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                if (!pair.substring(0, separator).equals(name, ignoreCase = true)) continue
                return decodeComponent(pair.substring(separator + 1))
            }
            return null
        }

        private fun decodeComponent(value: String): String =
            try {
                java.net.URLDecoder.decode(value, "UTF-8")
            } catch (e: IllegalArgumentException) {
                value
            }

        @JvmStatic
        fun isHlsUri(uri: String): Boolean {
            if (isAudioUri(uri)) {
                return false
            }
            if (uri.contains("m3u8") || uri.contains("type=hls") || uri.contains("format=hls")) {
                return true
            }
            val parsedUri: Uri? = Uri.parse(uri)
            val path = parsedUri?.path ?: return false
            val lower = path.lowercase(Locale.getDefault())
            return lower.endsWith("/live.php") || lower.contains("/live/")
        }

        @JvmStatic
        fun isAudioUri(uri: String): Boolean {
            val parsedUri: Uri? = Uri.parse(uri)
            var path = parsedUri?.path ?: uri
            path = path.lowercase(Locale.getDefault())
            return path.endsWith(".mp3")
                || path.endsWith(".m4a")
                || path.endsWith(".aac")
                || path.endsWith(".flac")
                || path.endsWith(".wav")
                || path.endsWith(".ogg")
                || path.endsWith(".opus")
                || path.endsWith(".amr")
        }
    }
}
