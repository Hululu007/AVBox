package com.github.tvbox.osc.util

/**
 * 观看历史合并(移植自上游"历史合并"功能,口径按当前源标题特点强化)。
 *
 * 作用:同一部剧从多个源看过时,历史列表只保留最新一条,不再出现多条重复。
 *
 * 与上游的差异:
 * ① 上游在 `RoomDataManger.insertVodRecord` 写入时全表遍历删同名,而当前项目写入发生在播放/切集路径上,
 *    全量反序列化会有可感知卡顿 ⇒ 这里改在读取侧(历史页 IO 线程)完成,去重后把被合并掉的旧记录永久删除,
 *    效果与上游一致(旧记录同样不可恢复);
 * ② 上游按标题 trim 严格相等判定,当前源标题普遍带年份/集数括注(如「庆余年(2019)」)会合并不上
 *    ⇒ 复用精准搜索同一套归一化(删括注/空白/标点、忽略大小写);
 * ③ 括注里的年份归一化后会被删掉,为避免「鹿鼎记(1998)」与「鹿鼎记(2020)」误合并,
 *    两边都能解析出年份且不相等时不合并。
 *
 * 不改任何 Java:开关走独立 KV 键(未登记 KVKeySpec,读取必须带默认值)。
 */
object HistoryMerge {
    private const val KEY = "history_merge"

    private val YEAR_PATTERN = Regex("(?:19|20)\\d{2}")

    fun isEnabled(): Boolean = KV.get(KEY, false)

    fun setEnabled(enabled: Boolean) {
        KV.put(KEY, enabled)
    }

    fun normalize(title: String?): String = SearchSettings.normalize(title)

    /** 标题里的第一个 4 位年份(如「鹿鼎记(1998)」→ "1998");没有则空串 */
    fun yearOf(title: String?): String = YEAR_PATTERN.find(title.orEmpty())?.value ?: ""

    /** 归一化相等,且年份不冲突(一方没有年份即视为同剧) */
    fun isSameTitle(title: String?, otherTitle: String?): Boolean {
        val norm = normalize(title)
        if (norm.isEmpty() || norm != normalize(otherTitle)) return false
        val a = yearOf(title)
        val b = yearOf(otherTitle)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    /**
     * 去重:输入按时间倒序(最新在前),每组保留最新一条。
     *
     * @return first = 保留列表,second = 被合并掉的记录(调用方负责永久删除)
     */
    fun <T> dedupe(list: List<T>, titleOf: (T) -> String?): Pair<List<T>, List<T>> {
        val kept = ArrayList<T>(list.size)
        val dropped = ArrayList<T>()
        val representatives = HashMap<String, MutableList<String>>()
        for (item in list) {
            val title = titleOf(item)
            val norm = normalize(title)
            // 归一化后为空(缺失或纯符号)无法判断重复,原样保留
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
