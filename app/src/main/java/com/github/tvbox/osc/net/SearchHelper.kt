package com.github.tvbox.osc.net

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.SearchSettingsState

object SearchHelper {

    @JvmStatic
    var liveSourcesProvider: (() -> List<SourceBean>?)? = null

    @JvmStatic
    fun getSourcesForSearch(): HashMap<String, String>? {
        var mCheckSources: HashMap<String, String>? = null
        try {
            val api = KV.get(HawkConfig.API_URL, "")
            if (api.isEmpty()) return null
            mCheckSources = SearchSettingsState.config.sourcesForSearch[api]
        } catch (e: Exception) {
            return null
        }
        if (mCheckSources == null || mCheckSources.isEmpty()) {
            mCheckSources = getSources()
        }
        return mCheckSources
    }

    @JvmStatic
    fun isSelectionStale(checked: HashMap<String, String>?): Boolean {
        val liveKeys = HashSet<String>()
        for (bean in liveSourcesProvider?.invoke().orEmpty()) {
            val key = bean.key ?: continue
            liveKeys.add(key)
        }
        return isSelectionStale(checked, liveKeys)
    }

    @JvmStatic
    fun isSelectionStale(checked: HashMap<String, String>?, liveSourceKeys: Set<String>): Boolean {
        if (checked == null || checked.isEmpty()) return false
        for (checkedKey in checked.keys) {
            if (!liveSourceKeys.contains(checkedKey)) return true
        }
        return false
    }

    @JvmStatic
    fun getSources(): HashMap<String, String> {
        val mCheckSources = HashMap<String, String>()
        for (bean in liveSourcesProvider?.invoke().orEmpty()) {
            if (!bean.isSearchable()) {
                continue
            }
            val key = bean.key ?: continue
            mCheckSources[key] = "1"
        }
        return mCheckSources
    }

}
