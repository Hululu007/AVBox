package com.github.tvbox.osc.util

/**
 * 搜索页的全局设置(精准匹配开关)与搜索站点选择(写入侧)。
 *
 * 站点选择的读取与"过期"判定仍在 [SearchHelper];选择永远只保留当前源集合里存在的 key。
 */
object SearchSettings {

    enum class SearchLayout { Horizontal, Vertical }

    // 该键未登记 KVKeySpec:读取必须带默认值(靠默认值携带 Boolean 类型),KV.get(key) 不带默认值会解不出
    private const val KEY_EXACT_MATCH = "search_exact_match"

    // 同 KEY_EXACT_MATCH:未登记 KVKeySpec 的键读取必须带默认值
    private const val KEY_RESULT_LAYOUT = "search_result_layout"

    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"

    private const val VALUE_LAYOUT_VERTICAL = "vertical"

    // 底层"无记录=不限制"表达不了"一个都不搜",只能按源地址另记一份空选择
    private const val KEY_EMPTY_SOURCES = "search_sources_empty"

    // 精准匹配按结果逐条调用:正则必须预编译,写在 normalize 里等于每条结果都重新编译两次
    private val BRACKET_PATTERN = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")

    private val NOISE_PATTERN = Regex("[\\s\\p{Z}\\p{P}\\p{S}]")

    fun isExactMatchEnabled(): Boolean = KV.get(KEY_EXACT_MATCH, false)

    fun setExactMatchEnabled(enabled: Boolean) {
        KV.put(KEY_EXACT_MATCH, enabled)
    }

    /** 搜索结果展示方式:横排(各源分区 + 横向卡片行)/ 竖排(左侧站点栏 + 右侧结果) */
    fun resultLayout(): SearchLayout =
        if (KV.get(KEY_RESULT_LAYOUT, "") == VALUE_LAYOUT_VERTICAL) SearchLayout.Vertical else SearchLayout.Horizontal

    fun setResultLayout(layout: SearchLayout) {
        KV.put(
            KEY_RESULT_LAYOUT,
            if (layout == SearchLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
    }

    /** 当前源地址是否被显式设为"不搜任何站点" */
    fun isSourcesEmpty(): Boolean {
        val api = KV.get(HawkConfig.API_URL, "")
        return api.isNotEmpty() && emptyApis().contains(api)
    }

    /**
     * 当前源地址的站点选择:null=不限制(全部可搜源),空集=不搜任何站点。
     *
     * 必须走不带默认值的 KV 读取(靠 KVKeySpec 登记的泛型还原内层 HashMap);带 `HashMap<>()` 默认值读会让
     * 内层退化成 Gson 的 LinkedTreeMap,Java 侧 `SearchHelper` 强转 HashMap 时抛异常并静默回落"全部"。
     */
    fun currentSelection(): Set<String>? {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return null
        if (isSourcesEmpty()) return emptySet()
        val stored = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: return null
        val picked = stored[api] ?: return null
        return if (picked.isEmpty()) null else picked.keys.toSet()
    }

    /** 写入当前地址的搜索站点选择;空选择=不搜任何站点,覆盖全部可搜源=不限制(删记录) */
    fun putSourcesForSearch(checked: Set<String>) {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return
        val searchable = SearchHelper.getSources().keys
        val picked = checked.filter { it in searchable }
        val all = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: HashMap()
        if (picked.isEmpty() || picked.containsAll(searchable)) {
            all.remove(api)
        } else {
            all[api] = HashMap<String, String>().apply { picked.forEach { this[it] = "1" } }
        }
        if (all.isEmpty()) KV.delete(HawkConfig.SOURCES_FOR_SEARCH) else KV.put(HawkConfig.SOURCES_FOR_SEARCH, all)
        setUpEmpty(api, picked.isEmpty() && searchable.isNotEmpty())
    }

    /** 精准匹配:归一化后相等。站点标题普遍带年份/集数等括注(如「庆余年(2019)」),直接相等会全滤掉 */
    fun isExactMatch(title: String?, keyword: String?): Boolean {
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(keyword)
    }

    /** 归一化 = 删括注及其内容 → 删空白与标点 → 忽略大小写;主体文字之外的差异(如「第二季」)仍然区分 */
    internal fun normalize(text: String?): String {
        if (text == null) return ""
        return text
            .replace(BRACKET_PATTERN, "")
            .replace(NOISE_PATTERN, "")
            .lowercase(java.util.Locale.ROOT)
    }

    private fun emptyApis(): Set<String> =
        KV.get(KEY_EMPTY_SOURCES, "").split('\n').filter { it.isNotEmpty() }.toSet()

    private fun setUpEmpty(api: String, empty: Boolean) {
        val next = emptyApis().toMutableSet()
        if (empty) next.add(api) else next.remove(api)
        if (next.isEmpty()) KV.delete(KEY_EMPTY_SOURCES) else KV.put(KEY_EMPTY_SOURCES, next.joinToString("\n"))
    }
}
