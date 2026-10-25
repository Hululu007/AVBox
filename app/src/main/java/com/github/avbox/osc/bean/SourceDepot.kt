package com.github.avbox.osc.bean

import com.github.avbox.osc.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonObject

class SourceDepot {

    private var url: String? = null
    private var name: String? = null

    fun getUrl(): String {
        return url?.trim { it <= ' ' } ?: ""
    }

    fun getName(): String {
        val value = name?.trim { it <= ' ' } ?: ""
        return if (value.isEmpty()) getUrl() else value
    }

    companion object {

        private fun isEmpty(text: String?): Boolean = text == null || text.isEmpty()

        @JvmStatic
        fun arrayFrom(urls: JsonArray?): List<SourceDepot> {
            val items = ArrayList<SourceDepot>()
            if (urls == null) return items
            try {
                for (element in urls) {
                    if (element == null || element.isJsonNull) continue
                    val depot = SourceDepot()
                    if (element.isJsonObject) {
                        val item = element.asJsonObject
                        depot.url = string(item, "url")
                        if (isEmpty(depot.url)) depot.url = string(item, "api")
                        depot.name = string(item, "name")
                    } else if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        depot.url = element.asString
                    }
                    if (!isEmpty(depot.getUrl())) items.add(depot)
                }
            } catch (th: Throwable) {
                AppLog.d("Depot", "depot urls parse failed, keep items so far")
            }
            return items
        }

        @JvmStatic
        fun string(json: JsonObject, key: String): String {
            val element = json.get(key)
            if (element == null || !element.isJsonPrimitive) return ""
            return try {
                if (element.asJsonPrimitive.isString) element.asString else ""
            } catch (th: Throwable) {
                ""
            }
        }
    }
}
