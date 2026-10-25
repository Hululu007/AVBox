package com.github.avbox.osc.net

import com.github.avbox.osc.util.StringUtils

object SearchSettings {

    fun isExactMatch(title: String?, keyword: String?): Boolean {
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(keyword)
    }

    internal fun normalize(text: String?): String = StringUtils.normalizeTitle(text)
}
