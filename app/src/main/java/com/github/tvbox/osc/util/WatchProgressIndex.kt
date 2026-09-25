package com.github.tvbox.osc.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 片级进度索引纯逻辑:键名、载荷、容量淘汰、集键裁剪。
 *
 * <p>载荷用 JSON 文本而非分隔符拼接 —— 集名可含分号/竖线/引号,拼接会被撕裂。
 */
object WatchProgressIndex {

    const val KEY_PREFIX = "progress_index_"

    /** 全库最多保留多少部片的进度痕迹:超限按最后活跃时间淘汰最旧 */
    const val MAX_TITLES = 100

    /** 单部片最多记多少集键:安全阀,防"整季铺成上千项"撑爆载荷 */
    const val MAX_EPS_PER_TITLE = 300

    /** 一条索引:最后活跃时间 + 该片写过进度的集键(存原始进度键,删除时现算 MD5) */
    data class Entry(val at: Long, val eps: List<String>)

    fun keyOf(owner: String): String = KEY_PREFIX + owner

    fun ownerOf(indexKey: String): String = indexKey.removePrefix(KEY_PREFIX)

    /** 载荷损坏按"无索引"处理:宁可这一次删不干净,也不能让删除路径崩 */
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

    /** 追加集键并刷新活跃时间(同一集重复落盘不产生重复项) */
    fun withEp(raw: String?, ep: String, at: Long): String {
        val old = decode(raw)?.eps.orEmpty()
        return encode(at, trimEps(old.filter { it != ep } + ep, MAX_EPS_PER_TITLE))
    }

    /** 移除集键;没有集键了返回 null(整条删掉,索引里不堆没有进度的片) */
    fun withoutEp(raw: String?, ep: String): String? {
        val entry = decode(raw) ?: return null
        val eps = entry.eps.filter { it != ep }
        return if (eps.isEmpty()) null else encode(entry.at, eps)
    }

    /** 挑出超限要淘汰的 owner:按活跃时间升序取溢出数量(时间相同时保持入参顺序) */
    fun pickEvictions(entries: List<Pair<String, Long>>, cap: Int): List<String> {
        if (entries.size <= cap) return emptyList()
        return entries.sortedBy { it.second }.take(entries.size - cap).map { it.first }
    }

    /** 保留最近的 [max] 个集键(追加顺序即写入顺序) */
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
