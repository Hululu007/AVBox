package com.github.avbox.osc.bean

import java.util.ArrayList
import java.util.Collections

class SourceBean {

    var key: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var name: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var api: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var type: Int = 0

    var searchable: Int = 0

    var quickSearch: Int = 0

    var changeable: Int = 1

    var filterable: Int = 0

    var playerUrl: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var ext: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var jar: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var categories: ArrayList<String>? = null

    var playerType: Int = 0

    var timeout: Int = 0

    var clickSelector: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var style: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var icon: String? = null
        get() = safeString(field)
        set(value) {
            field = safeString(value)
        }

    var hide: Int = 0

    var indexs: Int = 0

    var danmaku: Int = 1

    var header: MutableMap<String, String>? = null
        get() = field ?: Collections.emptyMap<String, String>()

    private fun safeString(value: String?): String = value ?: ""

    fun isSearchable(): Boolean = searchable != 0


    fun isQuickSearch(): Boolean = quickSearch != 0


    fun isChangeable(): Boolean = changeable != 0


    val isIndexSource: Boolean
        get() = indexs == 1


    fun isHidden(): Boolean = hide == 1


    fun isDanmakuEnabled(): Boolean = danmaku != 0


    fun playTimeoutSeconds(): Int = if (timeout > 0) Math.max(5, Math.min(60, timeout)) else 15
}
