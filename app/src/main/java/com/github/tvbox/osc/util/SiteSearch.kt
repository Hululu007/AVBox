package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.SourceBean

/** 站点查找:命中站点名或接口地址(key 常就是地址),忽略大小写,保持原顺序(重排会让选中项乱跳) */
object SiteSearch {

    fun filter(sources: List<SourceBean>, query: String): List<SourceBean> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return sources
        return sources.filter { it.name.contains(keyword, true) || it.key.contains(keyword, true) }
    }
}
