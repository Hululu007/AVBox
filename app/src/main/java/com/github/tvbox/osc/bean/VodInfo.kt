package com.github.tvbox.osc.bean

import com.github.tvbox.osc.util.RegexUtils
import java.io.Serializable
import java.util.Collections
import java.util.LinkedHashMap

data class VodInfo(
    val last: String? = null,
    val id: String? = null,
    val tid: Int = 0,
    val name: String? = null,
    val type: String? = null,
    val en: String? = null,
    val dt: String? = null,
    val pic: String? = null,
    val lang: String? = null,
    val area: String? = null,
    val year: Int = 0,
    val state: String? = null,
    val note: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val seriesFlags: ArrayList<VodSeriesFlag>? = null,
    val seriesMap: LinkedHashMap<String?, MutableList<VodSeries>>? = null,
    val des: String? = null,
    val playFlag: String? = null,
    val playIndex: Int = 0,
    val playNote: String = "",
    val sourceKey: String? = null,
    val sourceName: String = "",
    val sourceUnavailable: Boolean = false,
    val updateTime: Long = 0,
    val playerCfg: String = "",
    val reverseSort: Boolean = false,
) : Serializable {

    fun reverse() {
        val map = seriesMap ?: return
        for (flag in map.keys) {
            val series = map[flag] ?: continue
            Collections.reverse(series)
        }
    }

    class VodSeriesFlag(val name: String? = null) : Serializable

    class VodSeries(val name: String? = null, val url: String? = null) : Serializable

    companion object {

        fun fromVideo(video: Movie.Video): VodInfo {
            var flags: ArrayList<VodSeriesFlag>? = null
            var map: LinkedHashMap<String?, MutableList<VodSeries>>? = null
            val infoList = video.urlBean?.infoList
            if (infoList != null && infoList.isNotEmpty()) {
                val tempSeriesMap = LinkedHashMap<String?, MutableList<VodSeries>>()
                val builtFlags = ArrayList<VodSeriesFlag>()
                for (urlInfo in infoList) {
                    val beanList = urlInfo.beanList
                    if (beanList != null && beanList.isNotEmpty()) {
                        val seriesList = ArrayList<VodSeries>()
                        for (infoBean in beanList) {
                            seriesList.add(VodSeries(infoBean.name, infoBean.url))
                        }
                        tempSeriesMap[urlInfo.flag] = seriesList
                        builtFlags.add(VodSeriesFlag(urlInfo.flag))
                    }
                }
                val ordered = LinkedHashMap<String?, MutableList<VodSeries>>()
                for (flag in builtFlags) {
                    val series = tempSeriesMap[flag.name] ?: continue
                    if (builtFlags.size <= 5) {
                        if (isReverse(series)) Collections.reverse(series)
                    }
                    ordered[flag.name] = series
                }
                flags = builtFlags
                map = ordered
            }
            return VodInfo(
                last = video.last,
                id = video.id,
                tid = video.tid,
                name = video.name,
                en = video.en,
                type = video.type,
                pic = video.pic,
                lang = video.lang,
                area = video.area,
                year = video.year,
                state = video.state,
                note = video.note,
                actor = video.actor,
                director = video.director,
                des = video.des,
                seriesFlags = flags,
                seriesMap = map,
            )
        }

        private fun extractNumber(name: String): Int {
            val matcher = RegexUtils.getPattern("\\d+").matcher(name)
            if (matcher.find()) {
                return Integer.parseInt(matcher.group())
            }
            return 0
        }

        private fun isReverse(list: List<VodSeries>): Boolean {
            var ascCount = 0
            var descCount = 0
            val limit = Math.min(list.size - 1, 6)
            for (i in 0 until limit) {
                val current = extractNumber(list[i].name ?: "")
                val next = extractNumber(list[i + 1].name ?: "")
                if (current < next) {
                    ascCount++
                    if (ascCount == 2) return false
                } else if (current > next) {
                    descCount++
                    if (descCount == 2) return true
                }
            }
            return false
        }
    }
}
