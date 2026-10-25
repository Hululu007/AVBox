package com.github.avbox.osc.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object WatchProgressIndex {

    const val KEY_PREFIX = "progress_index_"

    const val MAX_TITLES = 100

    const val MAX_EPS_PER_TITLE = 300

    data class Entry(val at: Long, val eps: List<String>)

    fun keyOf(owner: String): String = KEY_PREFIX + owner

    fun ownerOf(indexKey: String): String = indexKey.removePrefix(KEY_PREFIX)

    fun decode(raw: String?): Entry? {
        if (raw == null || raw.length == 0) return null
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val at = obj.get("at")?.asLong ?: 0L
            val eps = obj.getAsJsonArray("eps")?.map { it.asString }.orEmpty()
            Entry(at, eps)
        } catch (e: Exception) {
            null
        }
    }

    fun withEp(raw: String?, ep: String, at: Long): String {
        val old = decode(raw)?.eps.orEmpty()
        return encode(at, trimEps(old.filter { it != ep } + ep, MAX_EPS_PER_TITLE))
    }

    fun withoutEp(raw: String?, ep: String): String? {
        val entry = decode(raw) ?: return null
        val eps = entry.eps.filter { it != ep }
        return if (eps.isEmpty()) null else encode(entry.at, eps)
    }

    fun pickEvictions(entries: List<Pair<String, Long>>, cap: Int): List<String> {
        if (entries.size <= cap) return emptyList()
        return entries.sortedBy { it.second }.take(entries.size - cap).map { it.first }
    }

    fun trimEps(eps: List<String>, max: Int): List<String> =
        if (eps.size <= max) eps else eps.takeLast(max)

    private fun encode(at: Long, eps: List<String>): String {
        val obj = JsonObject()
        obj.addProperty("at", at)
        val arr = JsonArray()
        eps.forEach { arr.add(it) }
        obj.add("eps", arr)
        return obj.toString()
    }
}
