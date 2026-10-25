package com.github.avbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

@XStreamAlias("rss")
class AbsSortXml : Serializable {
    @JvmField
    var sourceKey: String? = null

    @JvmField
    @Transient
    var loadFailed: Boolean = false

    @JvmField
    @XStreamAlias("class")
    var classes: MovieSort? = null

    @JvmField
    @XStreamAlias("list")
    var list: Movie? = null

    @JvmField
    var videoList: MutableList<Movie.Video>? = null
}
