package com.github.avbox.osc.bean

class SourceSubtitle {

    var name: String? = null

    var url: String? = null

    @get:JvmName("getIsZip")
    @set:JvmName("setIsZip")
    var isZip: Boolean = false

    override fun toString(): String {
        return "SourceSubtitle{name='$name', url='$url', isZip=$isZip}"
    }
}
