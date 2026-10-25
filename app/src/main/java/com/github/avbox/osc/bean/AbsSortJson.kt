package com.github.avbox.osc.bean

import com.google.gson.annotations.SerializedName
import java.io.Serializable
import java.util.ArrayList

class AbsSortJson : Serializable {

    @JvmField
    @SerializedName(value = "class")
    var classes: ArrayList<AbsJsonClass?>? = null

    @JvmField
    @SerializedName(value = "list")
    var list: ArrayList<AbsJson.AbsJsonVod>? = null

    fun toAbsSortXml(): AbsSortXml {
        val absSortXml = AbsSortXml()
        val movieSort = MovieSort()
        movieSort.sortList = ArrayList()
        val classList = classes ?: ArrayList()
        classes = classList
        for (cls in classList) {
            val item: AbsJsonClass = cls ?: continue
            if (item.type_id == null || item.type_name == null) {
                continue
            }
            val sortData = MovieSort.SortData()
            sortData.id = item.type_id
            sortData.name = item.type_name
            sortData.flag = item.type_flag
            movieSort.sortList?.add(sortData)
        }
        val vods = list
        if (!vods.isNullOrEmpty()) {
            val movie = Movie()
            val videos = ArrayList<Movie.Video>()
            for (vod in vods) {
                videos.add(vod.toXmlVideo())
            }
            movie.videoList = videos
            absSortXml.list = movie
        } else {
            absSortXml.list = null
        }
        absSortXml.classes = movieSort
        return absSortXml
    }

    inner class AbsJsonClass : Serializable {
        @JvmField
        @SerializedName(value = "type_id", alternate = ["id"])
        var type_id: String? = null
        @JvmField
        @SerializedName(value = "type_name", alternate = ["name"])
        var type_name: String? = null
        @JvmField
        var type_flag: String? = null
    }
}
