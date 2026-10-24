package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import org.json.JSONObject

class PlaybackProgressTracker(private val host: Host) {

    interface Host {
        fun playerCfg(): JSONObject?

        fun vod(): VodInfo?

        fun currentSession(): PlaybackSession?

        fun attemptState(): PlaybackAttemptState

        fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState)
    }

    private var progressKey: String? = null

    private var progressOwner: String? = null

    private var inheritProgressKey: String? = null
    private var inheritProgress: Long = 0

    private var startedPlaybackKey: String? = null

    private var startedProgressKey: String? = null

    suspend fun getSavedProgress(url: String?): Long {
        val skip = (host.playerCfg()?.optInt("st", 0) ?: 0) * 1000L
        if (HistoryHelper.isIncognito()) return skip
        val theCache = WatchProgressStore.submitAndAwait {
            AppGraph.cacheRepository.get(MD5.string2MD5(url))
        }
        if (theCache == null) {
            return skip
        }
        var rec = 0L
        if (theCache is Long) {
            rec = theCache
        } else if (theCache is String) {
            try {
                rec = theCache.toLong()
            } catch (e: NumberFormatException) {
                LOG.i("echo-String value is not a valid long.")
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.")
        }
        return Math.max(rec, skip)
    }

    fun inheritProgressFrom(key: String?, position: Long) {
        inheritProgressKey = key
        inheritProgress = position
    }

    fun inheritProgressIfNeeded() {
        try {
            WatchProgressStore.inherit(progressOwner, inheritProgressKey, progressKey, inheritProgress)
        } finally {
            inheritProgressKey = null
            inheritProgress = 0
        }
    }

    fun clearInheritProgress() {
        inheritProgressKey = null
        inheritProgress = 0
    }

    fun progressOwner(): String? = progressOwner

    fun progressKey(): String? = progressKey

    fun setProgressKey(progressKey: String?) {
        this.progressKey = progressKey
        this.progressOwner = WatchProgressStore.ownerOf(host.vod())
    }

    fun markContentStarted() {
        startedPlaybackKey = host.currentSession()?.playbackKey()
        startedProgressKey = progressKey
    }

    fun clearStartedContent() {
        startedPlaybackKey = null
        startedProgressKey = null
    }

    fun clearStartedPlaybackKey() {
        startedPlaybackKey = null
    }

    fun startedPlaybackKey(): String? = startedPlaybackKey

    fun startedProgressKey(): String? = startedProgressKey

    fun isSameStartedContent(): Boolean {
        return startedProgressKey != null && TextUtils.equals(startedProgressKey, progressKey)
    }

    fun setPendingInherit(key: String?, progress: Long) {
        host.updateAttemptState { it.copy(pendingInheritKey = key, pendingInheritProgress = progress) }
    }
}
