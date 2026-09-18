package com.github.tvbox.osc.util

/**
 * 影片总集数快照(历史页进度条用)。
 *
 * 历史记录落库时 `seriesMap` 被 Gson 排除策略剔掉了(见 `RoomDataManger.vodInfoStrategy`),历史里读不到集数,
 * 只能由详情页拿到详情数据时另记一份。
 */
object EpisodeTotals {

    private const val KEY = "episode_totals"

    private const val LIMIT = 300

    private val INDEX_PATTERNS = listOf(
        Regex("^第?\\s*(\\d{1,4})\\s*[集期话話]?\\s*(?:\\.[a-z0-9]{1,5})?$"),
        Regex("^(?:ep|e|episode)\\.?\\s*(\\d{1,4})\\s*(?:\\.[a-z0-9]{1,5})?$", RegexOption.IGNORE_CASE),
    )

    fun key(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun snapshot(): Map<String, Int> = read()
        .mapNotNull { (key, value) -> value.toIntOrNull()?.takeIf { it > 1 }?.let { key to it } }
        .toMap()

    /**
     * 可数的集数:集名必须带"集序号"(01 / 第1集 / EP01);网盘电影常把同一集的多个语言/码率版本铺成
     * 多项(甚至整串文件名),那种一律返回 null,不显示集数进度;只有一个不同序号也返回 null。
     */
    fun episodeCount(episodes: List<String?>): Int? {
        val indexes = episodes.mapNotNull { episodeIndexOf(it) }.toSet()
        return if (indexes.size >= 2) indexes.size else null
    }

    /** 集名是否"集序号"形态(历史页据此判断集数快照对这个片是否可信) */
    fun isNumberedEpisode(name: String?): Boolean = episodeIndexOf(name) != null

    private fun episodeIndexOf(name: String?): Int? {
        val text = name?.trim().orEmpty()
        if (text.isEmpty()) return null
        INDEX_PATTERNS.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            if (value != null) return value
        }
        return null
    }

    fun put(sourceKey: String?, vodId: String?, total: Int?) {
        if (sourceKey.isNullOrEmpty() || vodId.isNullOrEmpty()) return
        val id = key(sourceKey, vodId)
        val map = read()
        if (total == null || total <= 1) {
            // 不可数(多版本/文件名混排)必须清掉旧快照,否则上一版写入的集数会一直残留
            if (map.remove(id) == null) return
        } else {
            val value = total.toString()
            if (map[id] == value) return
            map[id] = value
        }
        if (map.size > LIMIT) map.keys.take(map.size - LIMIT).forEach { map.remove(it) }
        KV.put(KEY, map)
    }

    private fun read(): HashMap<String, String> = KV.get(KEY, HashMap<String, String>())
}
