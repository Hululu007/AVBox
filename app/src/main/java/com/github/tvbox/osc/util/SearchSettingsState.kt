package com.github.tvbox.osc.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class SearchSettingsConfig(
    val searchThreads: Int,
    val historyNumIndex: Int,
    val historyMerge: Boolean,
    val exactMatch: Boolean,
    val resultLayout: SearchSettingsState.SearchLayout,
    val sourcesForSearch: HashMap<String, HashMap<String, String>>,
    val emptySources: String,
)

object SearchSettingsState {

    enum class SearchLayout { Horizontal, Vertical }

    private const val KEY_EXACT_MATCH = "search_exact_match"
    private const val KEY_RESULT_LAYOUT = "search_result_layout"
    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"
    private const val VALUE_LAYOUT_VERTICAL = "vertical"
    private const val KEY_EMPTY_SOURCES = "search_sources_empty"

    private var current by mutableStateOf(load())

    val config: SearchSettingsConfig get() = current

    private fun load(): SearchSettingsConfig = SearchSettingsConfig(
        searchThreads = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT),
        historyNumIndex = KV.get(HawkConfig.HISTORY_NUM, 0),
        historyMerge = KV.get(HistoryMerge.KEY, false),
        exactMatch = KV.get(KEY_EXACT_MATCH, false),
        resultLayout = if (KV.get(KEY_RESULT_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            SearchLayout.Horizontal
        } else {
            SearchLayout.Vertical
        },
        sourcesForSearch = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: HashMap(),
        emptySources = KV.get(KEY_EMPTY_SOURCES, ""),
    )

    fun setSearchThreads(threads: Int) {
        KV.put(HawkConfig.SEARCH_THREADS, threads)
        current = current.copy(searchThreads = threads)
    }

    fun setHistoryNumIndex(index: Int) {
        KV.put(HawkConfig.HISTORY_NUM, index)
        current = current.copy(historyNumIndex = index)
    }

    fun setHistoryMerge(enabled: Boolean) {
        KV.put(HistoryMerge.KEY, enabled)
        current = current.copy(historyMerge = enabled)
    }

    fun setExactMatchEnabled(enabled: Boolean) {
        KV.put(KEY_EXACT_MATCH, enabled)
        current = current.copy(exactMatch = enabled)
    }

    fun setResultLayout(layout: SearchLayout) {
        KV.put(KEY_RESULT_LAYOUT, if (layout == SearchLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL)
        current = current.copy(resultLayout = layout)
    }

    fun isExactMatchEnabled(): Boolean = config.exactMatch

    fun resultLayout(): SearchLayout = config.resultLayout

    fun isSourcesEmpty(): Boolean {
        val api = KV.get(HawkConfig.API_URL, "")
        return api.isNotEmpty() && emptyApis().contains(api)
    }

    fun currentSelection(): Set<String>? {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return null
        if (isSourcesEmpty()) return emptySet()
        val picked = config.sourcesForSearch[api] ?: return null
        return if (picked.isEmpty()) null else picked.keys.toSet()
    }

    fun putSourcesForSearch(checked: Set<String>, searchable: Set<String>) {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return
        val picked = checked.filter { it in searchable }
        val all = HashMap(config.sourcesForSearch)
        if (picked.isEmpty() || picked.containsAll(searchable)) {
            all.remove(api)
        } else {
            all[api] = HashMap<String, String>().apply { picked.forEach { this[it] = "1" } }
        }
        if (all.isEmpty()) KV.delete(HawkConfig.SOURCES_FOR_SEARCH) else KV.put(HawkConfig.SOURCES_FOR_SEARCH, all)
        val nextEmpty = setEmptySources(api, picked.isEmpty() && searchable.isNotEmpty())
        current = current.copy(sourcesForSearch = all, emptySources = nextEmpty)
    }

    private fun emptyApis(): Set<String> =
        config.emptySources.split('\n').filter { it.isNotEmpty() }.toSet()

    private fun setEmptySources(api: String, empty: Boolean): String {
        val next = emptyApis().toMutableSet()
        if (empty) next.add(api) else next.remove(api)
        val joined = next.joinToString("\n")
        if (next.isEmpty()) KV.delete(KEY_EMPTY_SOURCES) else KV.put(KEY_EMPTY_SOURCES, joined)
        return joined
    }
}
