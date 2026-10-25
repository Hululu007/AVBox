package com.github.avbox.osc.bean

class DanmuSearchResult(name: String?, url: String?, isBuiltIn: Boolean) {
    private val rawName: String? = name
    private val rawUrl: String? = url
    private val builtIn: Boolean = isBuiltIn

    val name: String
        get() = if (rawName.isNullOrEmpty()) url else rawName

    val url: String
        get() = rawUrl ?: ""

    val isBuiltIn: Boolean
        get() = builtIn
}
