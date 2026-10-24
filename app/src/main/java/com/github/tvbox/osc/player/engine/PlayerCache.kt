package com.github.tvbox.osc.player.engine

import android.content.Context
import android.os.SystemClock
import android.os.Trace
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.github.tvbox.osc.BuildConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.io.File

object PlayerCache {

    private const val DEFAULT_SIZE_BYTES = 512L * 1024 * 1024

    private const val CACHE_DIR_NAME = "exo-video-cache"

    @Volatile
    private var sharedCache: Cache? = null

    @Volatile
    private var sharedCacheSizeBytes: Long = DEFAULT_SIZE_BYTES

    @Volatile
    private var sizeExplicit = false

    @JvmStatic
    fun getSharedCache(context: Context): Cache {
        val existing = sharedCache
        if (existing != null) return existing
        synchronized(PlayerCache::class.java) {
            sharedCache?.let { return it }
            ensureSizeFromConfig()
            val appContext = context.applicationContext
            Trace.beginSection("playerCache:buildSimpleCache")
            val created = try {
                SimpleCache(
                    File(externalCacheDir(appContext), CACHE_DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(sharedCacheSizeBytes),
                    StandaloneDatabaseProvider(appContext),
                )
            } finally {
                Trace.endSection()
            }
            sharedCache = created
            return created
        }
    }

    @JvmStatic
    fun warmup(context: Context) {
        val start = SystemClock.elapsedRealtime()
        try {
            getSharedCache(context)
            if (BuildConfig.DEBUG) {
                LOG.i("echo-playercache-warmup dt=" + (SystemClock.elapsedRealtime() - start) + "ms")
            }
        } catch (th: Throwable) {
            LOG.e("PlayerCache", th)
        }
    }

    @JvmStatic
    fun setSharedCacheSizeBytes(bytes: Long) {
        if (bytes > 0) {
            sharedCacheSizeBytes = bytes
            sizeExplicit = true
        }
    }

    private fun ensureSizeFromConfig() {
        if (sizeExplicit) return
        val mb = Math.max(128, KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT))
        sharedCacheSizeBytes = mb * 1024L * 1024L
    }

    private fun externalCacheDir(context: Context): File =
        context.externalCacheDir ?: context.cacheDir
}
