package com.github.tvbox.osc.util

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.PlaybackService
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** "本集播放进度"快照(历史页进度条)+ 观看历史的落库信号源 */
object PlaybackProgress {

    private const val KEY = "playback_progress"

    private const val LIMIT = 300

    private const val MIN_INTERVAL_MS = 5_000L

    private const val MIN_ADVANCE_MS = 1_000

    private const val MAX_STEP_MS = 10_000

    private var lastKey = ""

    private var lastSavedAt = 0L

    private var lastSavedPercent = -1

    private var sampleToken = ""

    private var lastPosition = -1

    private var advancedMs = 0

    private var watchedToken = ""

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
        // 节流会吞采样,判据必须拿到全部采样
        markWatched(positionMs)
        val now = System.currentTimeMillis()
        if (id == lastKey && (percent == lastSavedPercent || now - lastSavedAt < MIN_INTERVAL_MS)) return
        lastKey = id
        lastSavedAt = now
        lastSavedPercent = percent
        writer.execute { write(id, percent) }
    }

    fun flush(positionMs: Int, durationMs: Int): Boolean {
        val percent = calcPercent(positionMs, durationMs) ?: return false
        val id = currentKey() ?: return false
        markWatched(positionMs)
        watchedToken = ""
        sampleToken = ""
        advancedMs = 0
        if (id == lastKey && percent == lastSavedPercent) return false
        if (!write(id, percent)) return false
        lastKey = id
        lastSavedAt = System.currentTimeMillis()
        lastSavedPercent = percent
        return true
    }

    /** 只认平滑推进:回拖与 seek 跳变(起播起始位置来自上次进度)都不算"在播" */
    fun stepAdvanceMs(positionMs: Int, lastPositionMs: Int): Int =
        (positionMs - lastPositionMs).takeIf { it in 1..MAX_STEP_MS } ?: 0

    fun shouldMarkWatched(advancedMs: Int, token: String, lastToken: String): Boolean =
        token != lastToken && advancedMs >= MIN_ADVANCE_MS

    private fun markWatched(positionMs: Int) {
        val vod = App.getInstance().vodInfo ?: return
        if (PlaybackService.peek()?.isLiveMode() == true) return
        val token = key(vod.sourceKey, vod.id) + "#" + vod.playFlag + "#" + vod.playIndex
        if (token != sampleToken) {
            sampleToken = token
            lastPosition = positionMs
            advancedMs = 0
            return
        }
        advancedMs += stepAdvanceMs(positionMs, lastPosition)
        lastPosition = positionMs
        if (!shouldMarkWatched(advancedMs, token, watchedToken)) return
        watchedToken = token
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAYBACK_STARTED))
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
