package com.github.avbox.osc.data

import com.github.avbox.osc.util.KV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

object FollowWatched {

    private const val KEY = "follow_watched"

    private val stateFlow: MutableStateFlow<Map<String, Set<Int>>> by lazy {
        MutableStateFlow(watchedDays(read(), weekStart()))
    }

    val state: StateFlow<Map<String, Set<Int>>> get() = stateFlow

    private fun refreshState() {
        stateFlow.value = watchedDays(read(), weekStart())
    }

    fun ownerOf(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun weekStart(): Long {
        val today = LocalDate.now()
        return today.minusDays((today.dayOfWeek.value - 1).toLong()).toEpochDay()
    }

    fun mark(owner: String, days: Set<Int>, week: Long) {
        if (days.isEmpty()) return
        val value = week.toString()
        val map = read()
        days.forEach { map[dayKey(owner, it)] = value }
        prune(map, week)
        KV.put(KEY, map)
        refreshState()
    }

    fun clear(owner: String, days: Set<Int>, week: Long) {
        if (days.isEmpty()) return
        val map = read()
        var removed = false
        days.forEach { if (map.remove(dayKey(owner, it)) != null) removed = true }
        val pruned = prune(map, week)
        if (removed || pruned) KV.put(KEY, map)
        refreshState()
    }

    internal fun watchedDays(map: Map<String, String>, week: Long): Map<String, Set<Int>> {
        val value = week.toString()
        val result = HashMap<String, MutableSet<Int>>()
        map.forEach { (key, storedWeek) ->
            if (storedWeek != value) return@forEach
            val index = key.substringAfterLast('|').toIntOrNull() ?: return@forEach
            result.getOrPut(key.substringBeforeLast('|')) { HashSet() }.add(index)
        }
        return result
    }

    internal fun prune(map: MutableMap<String, String>, week: Long): Boolean {
        val value = week.toString()
        return map.entries.removeAll { it.value != value }
    }

    private fun dayKey(owner: String, day: Int): String = owner + "|" + day

    private fun read(): HashMap<String, String> = KV.get(KEY, HashMap<String, String>())
}
