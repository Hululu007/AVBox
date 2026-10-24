package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.api.AppBootstrap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.EpisodeTotals
import com.github.tvbox.osc.data.FollowDays
import com.github.tvbox.osc.data.FollowRepository
import com.github.tvbox.osc.data.FollowWatched
import com.github.tvbox.osc.data.HistoryRepository
import com.github.tvbox.osc.data.PlaybackProgress
import com.github.tvbox.osc.data.VodFollow
import com.github.tvbox.osc.util.HistoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal data class FollowEntry(
    val follow: VodFollow,
    val history: VodInfo?,
    val watchedDays: Set<Int>,
)

internal class FollowingViewModel(
    private val follows: FollowRepository = AppGraph.followRepository,
    private val history: HistoryRepository = AppGraph.historyRepository,
) : ViewModel() {

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _items = MutableStateFlow<List<FollowEntry>>(emptyList())
    val items = _items.asStateFlow()
    private val entries = MutableStateFlow<List<FollowEntry>>(emptyList())
    private val _episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val episodeTotals = _episodeTotals.asStateFlow()
    private val _playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = _playedPercents.asStateFlow()
    private val _today = MutableStateFlow(FollowDays.todayIndex())
    val today = _today.asStateFlow()

    private val _selectedDay = MutableStateFlow<Int?>(null)
    val selectedDay = _selectedDay.asStateFlow()
    private val _editMode = MutableStateFlow(false)
    val editMode = _editMode.asStateFlow()
    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected = _selected.asStateFlow()

    init {
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            combine(entries, FollowWatched.state) { list, watched ->
                list.map { entry ->
                    entry.copy(
                        watchedDays = watched[FollowWatched.ownerOf(entry.follow.sourceKey, entry.follow.vodId)].orEmpty(),
                    )
                }
            }.collect { _items.value = it; _loading.value = false }
        }
        viewModelScope.launch(Dispatchers.IO) {
            PlaybackProgress.percents.collect { map ->
                _playedPercents.value = if (HistoryHelper.isIncognito()) emptyMap() else map
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            AppBootstrap.subscriptionCid.drop(1).collect { refresh() }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) refresh()
            }
        }
    }

    fun refresh() {
        _today.value = FollowDays.todayIndex()
        viewModelScope.launch(Dispatchers.IO) {
            val list = follows.getAll()
            entries.value = list.map { follow ->
                FollowEntry(
                    follow = follow,
                    history = if (HistoryHelper.isIncognito()) {
                        null
                    } else {
                        history.getVodInfo(follow.sourceKey.orEmpty(), follow.vodId.orEmpty())
                    },
                    watchedDays = emptySet(),
                )
            }
            _episodeTotals.value = EpisodeTotals.snapshot()
        }
    }

    fun setWatched(id: Int, watched: Boolean) {
        val entry = _items.value.firstOrNull { it.follow.id == id } ?: return
        val days = targetDays(entry)
        if (days.isEmpty()) return
        val owner = FollowWatched.ownerOf(entry.follow.sourceKey, entry.follow.vodId)
        val updated = if (watched) entry.watchedDays + days else entry.watchedDays - days
        _items.value = _items.value.map { if (it.follow.id == id) it.copy(watchedDays = updated) else it }
        viewModelScope.launch(Dispatchers.IO) {
            val week = FollowWatched.weekStart()
            if (watched) {
                FollowWatched.mark(owner, days, week)
            } else {
                FollowWatched.clear(owner, days, week)
            }
        }
    }

    private fun targetDays(entry: FollowEntry): Set<Int> =
        _selectedDay.value?.let { setOf(it) } ?: FollowDays.decode(entry.follow.updateDays)

    fun selectDay(day: Int?) {
        _selectedDay.value = day
    }

    fun enterEdit(id: Int? = null) {
        _editMode.value = true
        _selected.value = if (id == null) emptySet() else setOf(id)
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

    fun deleteSelected() {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        exitEdit()
        viewModelScope.launch(Dispatchers.IO) {
            follows.deleteSelected(ids)
            refresh()
        }
    }
}
