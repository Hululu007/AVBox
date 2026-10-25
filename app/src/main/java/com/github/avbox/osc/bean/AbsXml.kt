package com.github.avbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

@XStreamAlias("rss")
class AbsXml : Serializable {
    @JvmField
    var sourceKey: String? = null
    @JvmField
    var searchToken: String? = null

    @JvmField
    var detailToken: Int? = null

    @JvmField
    @XStreamAlias("list")
    var movie: Movie? = null

    @JvmField
    @XStreamAlias("msg")
    var msg: String? = null
}
