package com.github.avbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable

@XStreamAlias("list")
class Movie : Serializable {
    @JvmField
    @XStreamAsAttribute
    var page: Int = 0
    @JvmField
    @XStreamAsAttribute
    var pagecount: Int = 0
    @JvmField
    @XStreamAsAttribute
    var pagesize: Int = 0
    @JvmField
    @XStreamAsAttribute
    var recordcount: Int = 0
    @JvmField
    @XStreamImplicit(itemFieldName = "video")
    var videoList: MutableList<Video>? = null

    @XStreamAlias("video")
    class Video : Serializable {
        @JvmField
        @XStreamAlias("last")
        var last: String? = null

        @JvmField
        @XStreamAlias("id")
        var id: String? = null

        @JvmField
        @XStreamAlias("tid")
        var tid: Int = 0

        @JvmField
        @XStreamAlias("name")
        var name: String? = null

        @JvmField
        @XStreamAlias("type")
        var type: String? = null

        @JvmField
        @XStreamAlias("en")
        var en: String? = null

        @JvmField
        @XStreamAlias("pic")
        var pic: String? = null

        @JvmField
        @XStreamAlias("lang")
        var lang: String? = null

        @JvmField
        @XStreamAlias("area")
        var area: String? = null

        @JvmField
        @XStreamAlias("year")
        var year: Int = 0

        @JvmField
        @XStreamAlias("state")
        var state: String? = null

        @JvmField
        @XStreamAlias("note")
        var note: String? = null

        @JvmField
        @XStreamAlias("actor")
        var actor: String? = null

        @JvmField
        @XStreamAlias("director")
        var director: String? = null

        @JvmField
        @XStreamAlias("dl")
        var urlBean: UrlBean? = null

        @JvmField
        @XStreamAlias("des")
        var des: String? = null

        @JvmField
        var sourceKey: String? = null

        @JvmField
        @XStreamAlias("tag")
        var tag: String? = null

        @JvmField
        @XStreamAlias("action")
        var action: String? = null

        @XStreamAlias("dl")
        class UrlBean : Serializable {
            @JvmField
            @XStreamImplicit(itemFieldName = "dd")
            var infoList: MutableList<UrlInfo>? = null

            @XStreamAlias("dd")
            @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["urls"])
            class UrlInfo : Serializable {
                @JvmField
                @XStreamAsAttribute
                var flag: String? = null

                @JvmField
                var urls: String? = null

                @JvmField
                var beanList: MutableList<InfoBean>? = null

                class InfoBean(nameValue: String?, urlValue: String?) : Serializable {
                    @JvmField
                    var name: String? = nameValue

                    @JvmField
                    var url: String? = urlValue
                }
            }
        }
    }
}
