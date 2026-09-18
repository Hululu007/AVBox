package com.github.tvbox.osc.util

import com.github.tvbox.osc.base.App
import com.google.gson.JsonParser
import java.io.File

/**
 * 站点级 `indexs` 标记(fongmi 用它区分"推荐/索引型源"):这类源的卡片只是关键词/分类入口,点进去没有可播详情,
 * 只该走搜索;普通站点才直接进详情。
 *
 * ApiConfig 只解析它自己声明的字段,indexs 进不了 SourceBean —— 这里直接读配置落盘缓存
 * (`filesDir/<md5(apiUrl)>`),避免为一个标记去改数据层 Java;配置更新后靠文件修改时间失效。
 */
object SourceIndexFlags {

    private var loadedApi: String? = null
    private var loadedStamp = 0L
    private var indexKeys: Set<String> = emptySet()

    fun isIndexSource(sourceKey: String?): Boolean {
        if (sourceKey.isNullOrEmpty()) return false
        ensureLoaded()
        return indexKeys.contains(sourceKey)
    }

    private fun ensureLoaded() {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return
        val file = File(App.getInstance().filesDir, MD5.encode(api))
        val stamp = if (file.isFile) file.lastModified() else 0L
        if (api == loadedApi && stamp == loadedStamp) return
        loadedApi = api
        loadedStamp = stamp
        indexKeys = if (file.isFile) parseIndexKeys(file.readText()) else emptySet()
    }

    internal fun parseIndexKeys(json: String): Set<String> = try {
        val sites = JsonParser.parseString(json).asJsonObject.getAsJsonArray("sites")
        val keys = HashSet<String>()
        if (sites != null) {
            for (element in sites) {
                val obj = element.asJsonObject
                val key = obj.get("key")?.asString.orEmpty()
                if (key.isNotEmpty() && obj.get("indexs")?.asInt == 1) keys.add(key)
            }
        }
        keys
    } catch (_: Throwable) {
        emptySet()
    }
}
