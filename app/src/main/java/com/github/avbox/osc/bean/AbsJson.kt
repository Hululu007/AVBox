package com.github.avbox.osc.bean

import com.google.gson.JsonElement
import java.io.Serializable
import java.util.ArrayList

class AbsJson : Serializable {

    @JvmField
    var code: Int = 0
    @JvmField
    var limit: String? = null
    @JvmField
    var list: ArrayList<AbsJsonVod>? = null
    @JvmField
    var msg: String? = null
    @JvmField
    var page: Int = 0
    @JvmField
    var pagecount: Int = 0
    @JvmField
    var total: Int = 0

    inner class AbsJsonVod : Serializable {
        @JvmField
        var group_id: Int = 0
        @JvmField
        var type_id: String? = null
        @JvmField
        var type_id_1: Int = 0
        @JvmField
        var type_name: String? = null
        @JvmField
        var vod_actor: String? = null
        @JvmField
        var vod_area: String? = null
        @JvmField
        var vod_author: String? = null
        @JvmField
        var vod_behind: String? = null
        @JvmField
        var vod_blurb: String? = null
        @JvmField
        var vod_class: String? = null
        @JvmField
        var vod_color: String? = null
        @JvmField
        var vod_content: String? = null
        @JvmField
        var vod_copyright: String? = null
        @JvmField
        var vod_director: String? = null
        @JvmField
        var vod_douban_id: String? = null
        @JvmField
        var vod_douban_score: String? = null
        @JvmField
        var vod_down: String? = null
        @JvmField
        var vod_down_from: String? = null
        @JvmField
        var vod_down_note: String? = null
        @JvmField
        var vod_down_server: String? = null
        @JvmField
        var vod_down_url: String? = null
        @JvmField
        var vod_duration: String? = null
        @JvmField
        var vod_en: String? = null
        @JvmField
        var vod_hits: String? = null
        @JvmField
        var vod_hits_day: String? = null
        @JvmField
        var vod_hits_month: String? = null
        @JvmField
        var vod_hits_week: String? = null
        @JvmField
        var vod_id: String? = null
        @JvmField
        var vod_isend: String? = null
        @JvmField
        var vod_jumpurl: String? = null
        @JvmField
        var vod_lang: String? = null
        @JvmField
        var vod_letter: String? = null
        @JvmField
        var vod_level: String? = null
        @JvmField
        var vod_lock: String? = null
        @JvmField
        var vod_name: String? = null
        @JvmField
        var vod_pic: String? = null
        @JvmField
        var vod_pic_screenshot: String? = null
        @JvmField
        var vod_pic_slide: String? = null
        @JvmField
        var vod_pic_thumb: String? = null
        @JvmField
        var vod_play_from: String? = null
        @JvmField
        var vod_play_note: String? = null
        @JvmField
        var vod_play_server: String? = null
        @JvmField
        var vod_play_url: String? = null
        @JvmField
        var vod_plot: String? = null
        @JvmField
        var vod_plot_detail: String? = null
        @JvmField
        var vod_plot_name: String? = null
        @JvmField
        var vod_points: String? = null
        @JvmField
        var vod_points_down: String? = null
        @JvmField
        var vod_points_play: String? = null
        @JvmField
        var vod_pubdate: String? = null
        @JvmField
        var vod_pwd: String? = null
        @JvmField
        var vod_pwd_down: String? = null
        @JvmField
        var vod_pwd_down_url: String? = null
        @JvmField
        var vod_pwd_play: String? = null
        @JvmField
        var vod_pwd_play_url: String? = null
        @JvmField
        var vod_pwd_url: String? = null
        @JvmField
        var vod_rel_art: String? = null
        @JvmField
        var vod_rel_vod: String? = null
        @JvmField
        var vod_remarks: String? = null
        @JvmField
        var vod_reurl: String? = null
        @JvmField
        var vod_score: String? = null
        @JvmField
        var vod_score_all: String? = null
        @JvmField
        var vod_score_num: String? = null
        @JvmField
        var vod_serial: String? = null
        @JvmField
        var vod_state: String? = null
        @JvmField
        var vod_status: String? = null
        @JvmField
        var vod_sub: String? = null
        @JvmField
        var vod_tag: String? = null
        @JvmField
        var vod_time: String? = null
        @JvmField
        var vod_time_add: String? = null
        @JvmField
        var vod_time_hits: String? = null
        @JvmField
        var vod_time_make: String? = null
        @JvmField
        var vod_total: String? = null
        @JvmField
        var vod_tpl: String? = null
        @JvmField
        var vod_tpl_down: String? = null
        @JvmField
        var vod_tpl_play: String? = null
        @JvmField
        var vod_trysee: String? = null
        @JvmField
        var vod_tv: String? = null
        @JvmField
        var vod_up: String? = null
        @JvmField
        var vod_version: String? = null
        @JvmField
        var vod_weekday: String? = null
        @JvmField
        var vod_writer: String? = null
        @JvmField
        var vod_year: String? = null
        @JvmField
        var action: String? = null

        @JvmField
        var cate: JsonElement? = null

        fun toXmlVideo(): Movie.Video {
            val video = Movie.Video()
            video.tag = vod_tag
            if (video.tag.isNullOrEmpty() && cate?.isJsonNull == false) video.tag = "folder"
            video.action = action
            video.last = vod_time
            video.id = vod_id
            video.tid = try {
                Integer.parseInt(type_id)
            } catch (ex: NumberFormatException) {
                0
            }
            video.name = vod_name
            video.en = vod_en
            video.type = type_name
            video.pic = vod_pic
            video.lang = vod_lang
            video.area = vod_area
            video.year = try {
                Integer.parseInt(vod_year)
            } catch (th: Throwable) {
                0
            }
            video.state = vod_state
            video.note = vod_remarks
            video.actor = vod_actor
            video.director = vod_director
            val urlBean = Movie.Video.UrlBean()
            val playFrom = vod_play_from
            val playUrl = vod_play_url
            if (playFrom != null && playUrl != null) {
                val playFlags = playFrom.split(Regex("\\$\\$\\$"))
                val playUrls = playUrl.split(Regex("\\$\\$\\$"))
                val infoList = ArrayList<Movie.Video.UrlBean.UrlInfo>()
                val count = Math.min(playFlags.size, playUrls.size)
                for (i in 0 until count) {
                    if (playFlags[i].trim { it <= ' ' }.isEmpty() || playUrls[i].trim { it <= ' ' }.isEmpty()) continue
                    val urlInfo = Movie.Video.UrlBean.UrlInfo()
                    urlInfo.flag = playFlags[i].trim { it <= ' ' }
                    urlInfo.urls = playUrls[i]
                    infoList.add(urlInfo)
                }
                urlBean.infoList = infoList
            }
            video.urlBean = urlBean
            video.des = vod_content
            return video
        }
    }

    fun toAbsXml(): AbsXml {
        val xml = AbsXml()
        val movie = Movie()
        movie.page = page
        movie.pagecount = pagecount
        movie.pagesize = try {
            Integer.parseInt(limit)
        } catch (th: Throwable) {
            0
        }
        movie.recordcount = total
        val videoList = ArrayList<Movie.Video>()
        for (vod in list.orEmpty()) {
            try {
                videoList.add(vod.toXmlVideo())
            } catch (th: Throwable) {
                movie.pagesize = 0
            }
        }
        movie.videoList = videoList
        xml.movie = movie
        xml.msg = msg
        return xml
    }
}
