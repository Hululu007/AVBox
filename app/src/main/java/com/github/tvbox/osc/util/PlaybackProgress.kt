package com.github.tvbox.osc.util

import com.github.tvbox.osc.base.App
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "本集播放进度"快照(历史页进度条用)。
 *
 * 播放位置只按播放地址存在进度缓存里,而历史记录落库时集数表被剔除 ⇒ 历史页反查不到"这一集看了多少";
 * 改由播放层在进度回调里按「源 + 内容 id」记一份百分比,暂停/播完时强制落一次。
 */
object PlaybackProgress {

    private const val KEY = "playback_progress"

    private const val LIMIT = 300

    private const val MIN_INTERVAL_MS = 5_000L

    private var lastKey = ""

    private var lastSavedAt = 0L

    private var lastSavedPercent = -1

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "playback-progress") }
    }

    fun key(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun snapshot(): Map<String, Int> = read()
        .mapNotNull { (key, value) ->
            value.toIntOrNull()?.takeIf { it in 0..100 }?.let { key to it }
        }
        .toMap()

    fun onProgress(positionMs: Int, durationMs: Int) {
        val percent = calcPercent(positionMs, durationMs) ?: return
        val id = currentKey() ?: return
        val now = System.currentTimeMillis()
        if (id == lastKey && (percent == lastSavedPercent || now - lastSavedAt < MIN_INTERVAL_MS)) return
        lastKey = id
        lastSavedAt = now
        lastSavedPercent = percent
        writer.execute { write(id, percent) }
    }

    /** @return true 表示写入了新值,调用方据此决定是否通知历史页刷新 */
    fun flush(positionMs: Int, durationMs: Int): Boolean {
        val percent = calcPercent(positionMs, durationMs) ?: return false
        val id = currentKey() ?: return false
        if (id == lastKey && percent == lastSavedPercent) return false
        if (!write(id, percent)) return false
        lastKey = id
        lastSavedAt = System.currentTimeMillis()
        lastSavedPercent = percent
        return true
    }

    private fun calcPercent(positionMs: Int, durationMs: Int): Int? {
        if (durationMs <= 0) return null
        return (positionMs.toLong() * 100 / durationMs).toInt().coerceIn(0, 100)
    }

    private fun currentKey(): String? {
        val vod = App.getInstance().vodInfo ?: return null
        if (vod.sourceKey.isNullOrEmpty() || vod.id.isNullOrEmpty()) return null
        return key(vod.sourceKey, vod.id)
    }

    @Synchronized
    private fun write(id: String, percent: Int): Boolean {
        val map = read()
        map[id] = percent.toString()
        if (map.size > LIMIT) map.keys.take(map.size - LIMIT).forEach { map.remove(it) }
        return KV.put(KEY, map)
    }

    private fun read(): HashMap<String, String> = KV.get(KEY, HashMap<String, String>())
}
