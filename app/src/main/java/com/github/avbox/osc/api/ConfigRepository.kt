package com.github.avbox.osc.api

import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.KV

object ConfigRepository {

    fun vodUrl(): String = KV.get(KVKeys.API_URL, "")

    fun liveUrl(): String = KV.get(KVKeys.LIVE_API_URL, "")

    fun switchVodSource(url: String): Boolean {
        val followLive = ApiConfig.isLiveFollowVod()
        val oldApi = vodUrl()
        val oldFollowTarget = liveUrl().ifEmpty { oldApi }
        HistoryHelper.setApiHistory(url)
        KV.put(KVKeys.API_URL, url)
        if (followLive) {
            KV.put(KVKeys.LIVE_API_URL, "")
            if (url != oldFollowTarget) HistoryHelper.clearLiveApiLineList()
        }
        if (!HistoryHelper.isApiLineHistory(url)) HistoryHelper.clearApiLineList()
        return oldApi == url
    }

    fun switchLiveSource(url: String) {
        KV.put(KVKeys.LIVE_API_URL, url)
        if (url.isEmpty()) {
            HistoryHelper.clearLiveApiLineList()
        } else {
            HistoryHelper.setLiveApiHistory(url)
            if (!HistoryHelper.isLiveApiLineHistory(url)) HistoryHelper.clearLiveApiLineList()
        }
        ApiConfig.get().clearLiveHosts()
        ApiConfig.get().invalidateLiveConfig()
    }
}
