package com.github.avbox.osc.bean

import android.util.Base64

import com.github.avbox.osc.util.DefaultConfig

class ParseBean {

    var name: String? = null

    private var urlValue: String? = null

    var url: String?
        get() = urlValue?.let { DefaultConfig.checkReplaceProxy(it) }
        set(value) {
            urlValue = value
        }

    var ext: String? = null

    var type: Int = 0

    var isDefault: Boolean = false

    fun mixUrl(): String? {
        val url = urlValue
        val extValue = ext
        if (!extValue.isNullOrEmpty() && url != null) {
            val idx = url.indexOf("?")
            if (idx > 0) {
                return url.substring(0, idx + 1) + "cat_ext=" +
                    Base64.encodeToString(extValue.toByteArray(), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP) +
                    "&" + url.substring(idx + 1)
            }
        }
        return url
    }
}
