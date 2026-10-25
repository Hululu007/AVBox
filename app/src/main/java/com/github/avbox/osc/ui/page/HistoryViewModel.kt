package com.github.avbox.osc.ui.page

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.api.AppBootstrap
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.api.TmdbPoster
import com.github.avbox.osc.bean.TmdbPosterCache
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.event.UiChange
import com.github.avbox.osc.event.UiEvents
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.HistoryRepository
import com.github.avbox.osc.data.PlaybackProgress
import com.github.avbox.osc.data.WatchProgressStore
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.SearchSettingsState
import com.github.avbox.osc.util.TrackMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val history: HistoryRepository = AppGraph.historyRepository,
) : ViewModel(), TmdbPosterCache {

    override fun tmdbCachedPoster(name: String?, year: Int, altName: String?): String? =
        TmdbPoster.cachedPoster(name, year, altName)

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _items = MutableStateFlow<List<VodInfo>>(emptyList())
    val items = _items.asStateFlow()
    private val _episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val episodeTotals = _episodeTotals.asStateFlow()
    private val _playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = _playedPercents.asStateFlow()

    private val _incognito = MutableStateFlow(HistoryHelper.isIncognito())
    val incognito = _incognito.asStateFlow()

    init {
        observeUiEvents()
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                AppBootstrap.subscriptionCid.flatMapLatest { cid ->
                    snapshotFlow { HistoryHelper.getHisNum(SearchSettingsState.config.historyNumIndex) }
                        .distinctUntilChanged()
                        .flatMapLatest { limit -> history.observeVodRecords(cid, limit) }
                },
                snapshotFlow { UiSettingsState.config.incognito }.distinctUntilChanged(),
            ) { list, hidden -> list to hidden }
                .collect { (list, hidden) ->
                    _incognito.value = hidden
                    _items.value = if (hidden) emptyList() else list
                    _episodeTotals.value = if (hidden) emptyMap() else EpisodeTotals.snapshot()
                    resolveSourceNames()
                    _loading.value = false
                }
        }
        viewModelScope.launch {
            PlaybackProgress.percents.collect { map ->
                _playedPercents.value = if (HistoryHelper.isIncognito()) emptyMap() else map
            }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) {
                    _episodeTotals.value = EpisodeTotals.snapshot()
                    resolveSourceNames()
                }
            }
        }
    }

    private val _scrollSignal = MutableStateFlow(0)
    val scrollSignal = _scrollSignal.asStateFlow()

    private val _placementAnim = MutableStateFlow(false)
    val placementAnim = _placementAnim.asStateFlow()

    private fun observeUiEvents() {
        viewModelScope.launch {
            UiEvents.changes.collect { change ->
                if (change !is UiChange.HistoryChanged) return@collect
                _placementAnim.value = false
                _scrollSignal.value++
                viewModelScope.launch(Dispatchers.IO) {
                    history.sweepDuplicates()
                }
            }
        }
    }

    private var resolveJob: Job? = null

    fun resolveSourceNames() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch(Dispatchers.IO) {
            val list = _items.value
            if (list.isEmpty()) return@launch
            if (AppBootstrap.state.value !is AppBootstrap.Boot.Ready) return@launch
            val cache = KV.get(KVKeys.SOURCE_NAME_CACHE, HashMap<String, String>())
            var cacheChanged = false
            var listChanged = false
            val resolvedItems = list.mapIndexed { position, info ->
                val key = info.sourceKey
                val bean = if (key.isNullOrEmpty()) null else ApiConfig.get().getSource(key)
                val resolved = if (key.isNullOrEmpty()) {
                    ""
                } else {
                    val current = bean?.name
                    if (!current.isNullOrEmpty()) {
                        if (cache[key] != current) {
                            cache[key] = current
                            cacheChanged = true
                        }
                        current
                    } else {
                        cache[key] ?: key
                    }
                }
                val unavailable = !key.isNullOrEmpty() && bean == null
                if (info.sourceName == resolved && info.sourceUnavailable == unavailable) {
                    info
                } else {
                    listChanged = true
                    info.copy(sourceName = resolved, sourceUnavailable = unavailable)
                }
            }
            if (cacheChanged) KV.put(KVKeys.SOURCE_NAME_CACHE, cache)
            if (listChanged) _items.value = resolvedItems.toList()
        }
    }

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun enterEdit() {
        _editMode.value = true
    }

    fun exitEdit() {
        _editMode.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(key: String) {
        _selected.value = if (key in _selected.value) _selected.value - key else _selected.value + key
    }

    fun pruneSelection(keys: Set<String>) {
        val pruned = _selected.value.intersect(keys)
        if (pruned.size != _selected.value.size) _selected.value = pruned
        if (keys.isEmpty()) _editMode.value = false
    }

    fun deleteSelected(list: List<VodInfo>) {
        if (list.isEmpty()) return
        _placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { item ->
                history.deleteVodRecord(item.sourceKey, item)
                WatchProgressStore.clearOwner(WatchProgressStore.ownerOf(item))
                TrackMemory.delete(TrackMemory.contentKey(item.sourceKey, item.id))
            }
        }
    }

    fun deleteAll() {
        _placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            history.deleteVodRecordAll()
            WatchProgressStore.clearAll()
            TrackMemory.deleteAll()
        }
    }

    companion object {
        fun key(item: VodInfo): String = item.sourceKey + "|" + item.id
    }
}
