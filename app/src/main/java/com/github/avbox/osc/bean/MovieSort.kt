package com.github.avbox.osc.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap

@XStreamAlias("class")
class MovieSort : Serializable {
    @JvmField
    @XStreamImplicit(itemFieldName = "ty")
    var sortList: MutableList<SortData>? = null

    @XStreamAlias("ty")
    @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["name"])
    class SortData() : Serializable, Comparable<SortData> {
        @JvmField
        @XStreamAsAttribute
        var id: String? = null
        @JvmField
        var name: String? = null
        @JvmField
        var sort: Int = -1
        @JvmField
        var select: Boolean = false
        @JvmField
        var filters: ArrayList<SortFilter> = ArrayList()
        @JvmField
        var filterSelect: HashMap<String, String> = HashMap()

        @JvmField
        var flag: String? = null

        constructor(id: String?, name: String?) : this() {
            this.id = id
            this.name = name
        }

        fun filterSelectCount(): Int {
            val map: HashMap<String, String>? = filterSelect
            if (map == null) {
                return 0
            }
            var count = 0
            for (filter in map.values) {
                if (!filter.isNullOrEmpty()) {
                    count++
                }
            }
            return count
        }

        override fun compareTo(other: SortData): Int {
            return this.sort - other.sort
        }

        override fun toString(): String {
            return "SortData{id='$id', name='$name', sort=$sort, select=$select, filters=$filters, filterSelect=$filterSelect, flag='$flag'}"
        }
    }

    class SortFilter {
        @JvmField
        var key: String? = null
        @JvmField
        var name: String? = null
        @JvmField
        var values: LinkedHashMap<String, String>? = null

        override fun toString(): String {
            return "SortFilter{key='$key', name='$name', values=$values}"
        }
    }
}
