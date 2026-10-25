package com.github.catvod.crawler.js

import androidx.annotation.Keep

import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.whl.quickjs.wrapper.Function

class local {

    @Keep
    @Function
    fun delete(str: String, str2: String) {
        try {
            KV.delete("jsRuntime_" + str + "_" + str2)
        } catch (e: Exception) {
            AppLog.e("local", e)
        }
    }

    @Keep
    @Function
    fun get(str: String, str2: String): String {
        try {
            return KV.get("jsRuntime_" + str + "_" + str2, "")
        } catch (e: Exception) {
            KV.delete("jsRuntime_" + str + "_" + str2)
            return str2
        }
    }

    @Keep
    @Function
    fun set(str: String, str2: String, str3: String) {
        try {
            KV.put("jsRuntime_" + str + "_" + str2, str3)
        } catch (e: Exception) {
            AppLog.e("local", e)
        }
    }
}
