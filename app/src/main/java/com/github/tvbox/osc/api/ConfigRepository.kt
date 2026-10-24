package com.github.tvbox.osc.api

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV

object ConfigRepository {

    fun vodUrl(): String = KV.get(HawkConfig.API_URL, "")

    fun liveUrl(): String = KV.get(HawkConfig.LIVE_API_URL, "")

    fun switchVodSource(url: String): Boolean {
        val followLive = ApiConfig.isLiveFollowVod()
        val oldApi = vodUrl()
        val oldFollowTarget = liveUrl().ifEmpty { oldApi }
        HistoryHelper.setApiHistory(url)
        KV.put(HawkConfig.API_URL, url)
        if (followLive) {
            KV.put(HawkConfig.LIVE_API_URL, "")
            if (url != oldFollowTarget) HistoryHelper.clearLiveApiLineList()
        }
        if (!HistoryHelper.isApiLineHistory(url)) HistoryHelper.clearApiLineList()
        return oldApi == url
    }

    fun switchLiveSource(url: String) {
        KV.put(HawkConfig.LIVE_API_URL, url)
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
