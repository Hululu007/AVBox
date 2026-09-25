package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.VodInfo

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
        Regex("^第?\\s*(\\d{1,4})\\s*[集期话話]?\\s*(?:\\.[a-z0-9]{1,5})?$"), // i18n: keep(R13:集数正则)
        Regex("^(?:ep|e|episode)\\.?\\s*(\\d{1,4})\\s*(?:\\.[a-z0-9]{1,5})?$", RegexOption.IGNORE_CASE),
    )

    fun key(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun snapshot(): Map<String, Int> {
        // 无痕:不展示观看痕迹(与 PlaybackProgress.snapshot 同口径;不靠调用方早退兜住)
        if (HistoryHelper.isIncognito()) return emptyMap()
        return read()
            .mapNotNull { (key, value) -> value.toIntOrNull()?.takeIf { it > 1 }?.let { key to it } }
            .toMap()
    }

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
        // 无痕:不新增观看痕迹(集数快照只服务历史卡片)
        if (HistoryHelper.isIncognito()) return
        putInternal(sourceKey, vodId, total)
    }

    /** 用户删历史时的移除入口(owner = 源|片id):主动操作不受无痕拦截 */
    @Synchronized
    fun remove(owner: String) {
        val map = read()
        if (map.remove(owner) == null) return
        KV.put(KEY, map)
    }

    /**
     * 与索引同口径:只保留这些 owner 的快照,理由同 `PlaybackProgress.retain`
     * ([LIMIT] 那道按 HashMap 迭代序淘汰,挑的不是最旧的)。
     */
    @Synchronized
    fun retain(owners: Set<String>) {
        val map = read()
        val kept = HashMap<String, String>(map.size)
        for ((id, value) in map) {
            if (owners.contains(id)) kept[id] = value
        }
        if (kept.size == map.size) return
        KV.put(KEY, kept)
    }

    /** 清空历史:整张快照清掉 */
    @Synchronized
    fun removeAll() {
        KV.delete(KEY)
    }

    /** 按当前线路的集名快照集数(历史卡片 "X/Y 集");集名不可数时清掉旧快照 */
    fun putFromVod(vod: VodInfo) {
        val list = vod.playFlag?.let { vod.seriesMap?.get(it) }
        put(vod.sourceKey, vod.id, list?.let { episodeCount(it.map { series -> series.name }) })
    }

    /** 加锁:详情页主线程写入与历史页 IO 协程的级联移除会并发读改写 */
    @Synchronized
    private fun putInternal(sourceKey: String, vodId: String, total: Int?) {
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
