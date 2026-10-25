package com.github.avbox.osc.api

import com.github.avbox.osc.bean.MovieSort

import java.util.ArrayList
import java.util.Collections

object SortAdjuster {

    @JvmStatic
    fun adjustSort(sourceKey: String?, list: List<MovieSort.SortData>?, withMy: Boolean): List<MovieSort.SortData> {
        var data: MutableList<MovieSort.SortData> = ArrayList()
        if (sourceKey != null && list != null) {
            val sb = ApiConfig.get().getSource(sourceKey)
            if (sb == null || sb.categories == null) {
                for (sortData in list) {
                    if (sortData.filters == null)
                        sortData.filters = ArrayList()
                    data.add(sortData)
                }
                if (withMy)
                    data.add(0, MovieSort.SortData("my0", "主页")) // i18n: keep(默认配置数据)
                Collections.sort(data)
                return data
            }
            val categories = requireNotNull(sb.categories) { "source categories missing" }
            if (!categories.isEmpty()) {
                data = pickByCategories(list, categories)
            } else {
                for (sortData in list) {
                    if (sortData.filters == null)
                        sortData.filters = ArrayList()
                    data.add(sortData)
                }
            }
        }
        if (withMy)
            data.add(0, MovieSort.SortData("my0", "主页")) // i18n: keep(默认配置数据)
        Collections.sort(data)
        return data
    }

    @JvmStatic
    fun pickByCategories(list: List<MovieSort.SortData>, categories: List<String>): MutableList<MovieSort.SortData> {
        val data = ArrayList<MovieSort.SortData>()
        for (cate in categories) {
            for (sortData in list) {
                if (sortData.name == cate) {
                    if (sortData.filters == null)
                        sortData.filters = ArrayList()
                    data.add(sortData)
                }
            }
        }
        if (data.isEmpty()) {
            for (sortData in list) {
                if (sortData.filters == null)
                    sortData.filters = ArrayList()
                data.add(sortData)
            }
        }
        return data
    }
}
