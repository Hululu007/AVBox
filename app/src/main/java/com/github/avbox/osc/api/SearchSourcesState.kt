package com.github.avbox.osc.api

import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.SearchSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SearchSourcesState {

    data class Ui(
        val sources: List<SourceBean>,
        val pickedKeys: Set<String>,
    )

    private val _state = MutableStateFlow(build())

    val state: StateFlow<Ui> = _state.asStateFlow()

    fun refresh() {
        _state.value = build()
    }

    fun applySelection(next: Set<String>) {
        val searchable = ApiConfig.get().getSourceBeanList()
            .filter(SourceBean::isSearchable)
            .map { it.key.orEmpty() }
            .toSet()
        SearchSettingsState.putSourcesForSearch(next, searchable)
        refresh()
    }

    private fun build(): Ui {
        val sources = ApiConfig.get().getSourceBeanList().filter(SourceBean::isSearchable)
        val keys = sources.map { it.key.orEmpty() }.toSet()
        val stored = SearchSettingsState.currentSelection()
        val picked = when {
            stored == null -> keys
            stored.isEmpty() -> emptySet()
            else -> stored.filter { it in keys }.toSet().ifEmpty { keys }
        }
        return Ui(sources, picked)
    }
}
