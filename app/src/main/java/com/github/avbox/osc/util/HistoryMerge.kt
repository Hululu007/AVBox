package com.github.avbox.osc.util

object HistoryMerge {
    internal const val KEY = "history_merge"

    private val YEAR_PATTERN = Regex("(?:19|20)\\d{2}")

    fun isEnabled(): Boolean = KV.get(KEY, false)

    fun normalize(title: String?): String = StringUtils.normalizeTitle(title)

    fun yearOf(title: String?): String = YEAR_PATTERN.find(title.orEmpty())?.value ?: ""

    fun isSameTitle(title: String?, otherTitle: String?): Boolean {
        val norm = normalize(title)
        if (norm.isEmpty() || norm != normalize(otherTitle)) return false
        val a = yearOf(title)
        val b = yearOf(otherTitle)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    fun <T> dedupe(list: List<T>, titleOf: (T) -> String?): Pair<List<T>, List<T>> {
        val kept = ArrayList<T>(list.size)
        val dropped = ArrayList<T>()
        val representatives = HashMap<String, MutableList<String>>()
        for (item in list) {
            val title = titleOf(item)
            val norm = normalize(title)
            if (title == null || norm.isEmpty()) {
                kept += item
                continue
            }
            val reps = representatives.getOrPut(norm) { ArrayList() }
            if (reps.none { isSameTitle(title, it) }) {
                reps += title
                kept += item
            } else {
                dropped += item
            }
        }
        return kept to dropped
    }
}
