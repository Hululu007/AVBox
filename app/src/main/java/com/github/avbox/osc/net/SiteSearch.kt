package com.github.avbox.osc.net

import com.github.avbox.osc.bean.SourceBean

object SiteSearch {

    fun filter(sources: List<SourceBean>, query: String): List<SourceBean> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return sources
        return sources.filter { it.name.orEmpty().contains(keyword, true) || it.key.orEmpty().contains(keyword, true) }
    }
}
