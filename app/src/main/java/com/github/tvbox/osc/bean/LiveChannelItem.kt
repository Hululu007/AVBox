package com.github.tvbox.osc.bean

import com.google.gson.JsonObject
import java.util.ArrayList
import java.util.HashMap
import java.util.Objects

class LiveChannelItem {
    var channelIndex: Int = 0
    var channelNum: Int = 0
    var channelName: String? = null

    var channelLogo: String? = null
        get() = field ?: ""

    var channelEpg: String? = null
        get() = field ?: ""

    var channelUa: String? = null
        get() = field ?: ""

    var channelClick: String? = null
        get() = field ?: ""

    var channelFormat: String? = null
        get() = field ?: ""

    var channelOrigin: String? = null
        get() = field ?: ""

    var channelReferer: String? = null
        get() = field ?: ""

    var channelTvgId: String? = null
        get() = field ?: ""

    var channelTvgName: String? = null
        get() = field ?: ""

    var channelCatchup: JsonObject? = null
        get() = field ?: JsonObject()

    var channelHeader: MutableMap<String, String>? = null
        get() = field ?: HashMap<String, String>()

    private var channelParse: Int? = null

    var channelSourceNames: ArrayList<String>? = null

    var channelUrls: ArrayList<String>? = null
        set(value) {
            field = value
            sourceNum = value?.size ?: 0
        }

    @JvmField
    var sourceNum: Int = 0

    @JvmField
    var include_back: Boolean = false

    fun urlAt(sourceIndex: Int): String = channelUrls?.getOrNull(sourceIndex) ?: ""

    val headers: MutableMap<String, String>
        get() {
            val result = HashMap<String, String>(channelHeader.orEmpty())
            val ua = channelUa.orEmpty()
            if (ua.isNotEmpty()) result["User-Agent"] = ua
            val origin = channelOrigin.orEmpty()
            if (origin.isNotEmpty()) result["Origin"] = origin
            val referer = channelReferer.orEmpty()
            if (referer.isNotEmpty()) result["Referer"] = referer
            return result
        }

    fun setinclude_back(include_back: Boolean) {
        this.include_back = include_back
    }

    fun getinclude_back(): Boolean = include_back

    fun getSourceNum(): Int = sourceNum

    fun getChannelParse(): Int = channelParse ?: 0

    fun setChannelParse(channelParse: Int?) {
        this.channelParse = channelParse
    }

    fun hasCatchup(): Boolean {
        val catchup = channelCatchup ?: return false
        return catchup.entrySet().size > 0
    }

    fun isEmptyCatchup(): Boolean {
        val catchup = channelCatchup ?: return true
        return catchup.entrySet().isEmpty()
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as LiveChannelItem
        return Objects.equals(channelName, that.channelName) &&
            Objects.equals(channelUrls, that.channelUrls)
    }

    override fun hashCode(): Int {
        return Objects.hash(channelName, channelUrls)
    }
}
