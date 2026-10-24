package com.github.tvbox.osc.api

import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.net.SearchSettings
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
        SearchSettings.putSourcesForSearch(next)
        refresh()
    }

    private fun build(): Ui {
        val sources = ApiConfig.get().getSourceBeanList().filter(SourceBean::isSearchable)
        val keys = sources.map { it.key.orEmpty() }.toSet()
        val stored = SearchSettings.currentSelection()
        val picked = when {
            stored == null -> keys
            stored.isEmpty() -> emptySet()
            else -> stored.filter { it in keys }.toSet().ifEmpty { keys }
        }
        return Ui(sources, picked)
    }
}
