package com.github.avbox.osc.server

import android.text.TextUtils

import com.github.avbox.osc.util.KV

import fi.iki.elonen.NanoHTTPD

class CacheRequestProcess : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String?): Boolean {
        return fileName != null && fileName.startsWith("/cache")
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String?,
        params: MutableMap<String, String>?,
        files: MutableMap<String, String>?,
    ): NanoHTTPD.Response {
        var params2 = params ?: session.parms
        if (files != null && files.isNotEmpty()) params2.putAll(files)
        val action = params2["do"]
        val key = params2["key"]
        if (TextUtils.isEmpty(key)) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "")
        val cacheKey = getKey(params2["rule"], key)
        if ("get" == action) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, KV.get(cacheKey, ""))
        if ("set" == action) {
            val value = params2["value"]
            KV.put(cacheKey, value ?: "")
        }
        if ("del" == action) KV.delete(cacheKey)
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "OK")
    }

    private fun getKey(rule: String?, key: String?): String {
        return "cache_" + (if (TextUtils.isEmpty(rule)) "" else rule + "_") + key
    }
}
