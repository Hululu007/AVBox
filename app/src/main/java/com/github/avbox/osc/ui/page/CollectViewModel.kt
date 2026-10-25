package com.github.avbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.api.AppBootstrap
import com.github.avbox.osc.api.TmdbPoster
import com.github.avbox.osc.bean.TmdbPosterCache
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.CollectRepository
import com.github.avbox.osc.data.VodCollect
import com.github.avbox.osc.util.SubscribeList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class CollectViewModel(
    private val collect: CollectRepository = AppGraph.collectRepository,
) : ViewModel(), TmdbPosterCache {

    override fun tmdbCachedPoster(name: String?, year: Int, altName: String?): String? =
        TmdbPoster.cachedPoster(name, year, altName)

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _items = MutableStateFlow<List<VodCollect>>(emptyList())
    val items: StateFlow<List<VodCollect>> = _items.asStateFlow()

    private val _unavailableKeys = MutableStateFlow<Set<String>>(emptySet())
    val unavailableKeys: StateFlow<Set<String>> = _unavailableKeys.asStateFlow()

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()
    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected: StateFlow<Set<Int>> = _selected.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            AppBootstrap.subscriptionCid.drop(1).collect { recomputeUnavailableNow() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            collect.observeAll().collect { list ->
                _items.value = list
                recomputeUnavailableNow()
                _loading.value = false
            }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) {
                    viewModelScope.launch(Dispatchers.IO) { recomputeUnavailableNow() }
                }
            }
        }
    }

    private val _placementAnim = MutableStateFlow(false)
    val placementAnim: StateFlow<Boolean> = _placementAnim.asStateFlow()

    private fun recomputeUnavailableNow() {
        if (AppBootstrap.state.value !is AppBootstrap.Boot.Ready) return
        _unavailableKeys.value = computeUnavailable(_items.value)
    }

    private fun computeUnavailable(list: List<VodCollect>): Set<String> {
        val current = collect.currentCid()
        val known = SubscribeList.vodUrls()
        return list.filter { item ->
            val cid = item.cid.orEmpty()
            if (cid.isNotEmpty() && !known.contains(cid)) {
                true
            } else {
                (cid.isEmpty() || cid == current) && ApiConfig.get().getSource(item.sourceKey) == null
            }
        }.mapNotNull { it.sourceKey }.toSet()
    }

    fun enterEdit() {
        _editMode.value = true
    }

    fun exitEdit() {
        _editMode.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(id: Int) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun pruneSelection(ids: Set<Int>) {
        val pruned = _selected.value.intersect(ids)
        if (pruned.size != _selected.value.size) _selected.value = pruned
        if (ids.isEmpty()) _editMode.value = false
    }

    fun deleteSelected(list: List<VodCollect>) {
        if (list.isEmpty()) return
        _placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { item -> collect.deleteVodCollect(item.id) }
        }
    }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            collect.deleteVodCollectAll()
        }
    }
}
