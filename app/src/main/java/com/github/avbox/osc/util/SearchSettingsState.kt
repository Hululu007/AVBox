package com.github.avbox.osc.util

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
        searchThreads = KV.get(KVKeys.SEARCH_THREADS, KVKeys.SEARCH_THREADS_DEFAULT),
        historyNumIndex = KV.get(KVKeys.HISTORY_NUM, 0),
        historyMerge = KV.get(HistoryMerge.KEY, false),
        exactMatch = KV.get(KEY_EXACT_MATCH, false),
        resultLayout = if (KV.get(KEY_RESULT_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            SearchLayout.Horizontal
        } else {
            SearchLayout.Vertical
        },
        sourcesForSearch = KV.get<HashMap<String, HashMap<String, String>>>(KVKeys.SOURCES_FOR_SEARCH) ?: HashMap(),
        emptySources = KV.get(KEY_EMPTY_SOURCES, ""),
    )

    var searchThreads: Int
        get() = current.searchThreads
        set(value) {
            KV.put(KVKeys.SEARCH_THREADS, value)
            current = current.copy(searchThreads = value)
        }

    var historyNumIndex: Int
        get() = current.historyNumIndex
        set(value) {
            KV.put(KVKeys.HISTORY_NUM, value)
            current = current.copy(historyNumIndex = value)
        }

    var historyMerge: Boolean
        get() = current.historyMerge
        set(value) {
            KV.put(HistoryMerge.KEY, value)
            current = current.copy(historyMerge = value)
        }

    var exactMatch: Boolean
        get() = current.exactMatch
        set(value) {
            KV.put(KEY_EXACT_MATCH, value)
            current = current.copy(exactMatch = value)
        }

    var resultLayout: SearchLayout
        get() = current.resultLayout
        set(value) {
            KV.put(KEY_RESULT_LAYOUT, if (value == SearchLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL)
            current = current.copy(resultLayout = value)
        }



    fun isSourcesEmpty(): Boolean {
        val api = KV.get(KVKeys.API_URL, "")
        return api.isNotEmpty() && emptyApis().contains(api)
    }

    fun currentSelection(): Set<String>? {
        val api = KV.get(KVKeys.API_URL, "")
        if (api.isEmpty()) return null
        if (isSourcesEmpty()) return emptySet()
        val picked = config.sourcesForSearch[api] ?: return null
        return if (picked.isEmpty()) null else picked.keys.toSet()
    }

    fun putSourcesForSearch(checked: Set<String>, searchable: Set<String>) {
        val api = KV.get(KVKeys.API_URL, "")
        if (api.isEmpty()) return
        val picked = checked.filter { it in searchable }
        val all = HashMap(config.sourcesForSearch)
        if (picked.isEmpty() || picked.containsAll(searchable)) {
            all.remove(api)
        } else {
            all[api] = HashMap<String, String>().apply { picked.forEach { this[it] = "1" } }
        }
        if (all.isEmpty()) KV.delete(KVKeys.SOURCES_FOR_SEARCH) else KV.put(KVKeys.SOURCES_FOR_SEARCH, all)
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
