package com.github.catvod.crawler.js

import com.github.avbox.osc.util.RegexUtils
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

import java.util.Arrays

class Req {

    @field:SerializedName("buffer")
    private var buffer: Int? = null

    @field:SerializedName("redirect")
    private var redirect: Int? = null

    @field:SerializedName("timeout")
    private var timeout: Int? = null

    @field:SerializedName("callTimeout")
    private var callTimeout: Int? = null

    @field:SerializedName("cookie")
    private var cookie: Boolean? = null

    @field:SerializedName("__session")
    private var session: Int? = null

    @field:SerializedName("postType")
    private var postType: String? = null

    @field:SerializedName("method")
    private var method: String? = null

    @field:SerializedName("body")
    private var body: String? = null

    @field:SerializedName("data")
    private var data: JsonElement? = null

    @field:SerializedName("headers")
    private var headers: JsonElement? = null

    @field:SerializedName("params")
    private var params: JsonElement? = null

    fun getBuffer(): Int {
        return buffer ?: 0
    }

    fun getRedirect(): Int? {
        return redirect ?: 1
    }

    fun getTimeout(): Int? {
        return timeout ?: 10000
    }

    fun getCallTimeout(): Int {
        return callTimeout ?: 0
    }

    fun isCookie(): Boolean {
        return cookie == true
    }

    fun getSessionId(): Int? {
        return session
    }

    fun getPostType(): String {
        val value = postType
        return if (value.isNullOrEmpty()) "json" else value
    }

    fun getMethod(): String {
        val value = method
        return if (value.isNullOrEmpty()) "get" else value
    }

    fun getBody(): String? {
        return body
    }

    fun getData(): JsonElement? {
        return data
    }

    fun getParams(): JsonElement? {
        return params
    }

    private fun getHeaders(): JsonElement? {
        return headers
    }

    fun isRedirect(): Boolean {
        return getRedirect() == 1
    }

    fun getHeader(): Map<String, String> {
        return Json.toMap(getHeaders())
    }

    fun getCharset(): String {
        val header = getHeader()
        val keys = Arrays.asList("Content-Type", "content-type")
        for (key in keys) header[key]?.let { return getCharset(it) }
        return "UTF-8"
    }

    private fun getCharset(value: String): String {
        for (text in RegexUtils.getPattern(";").split(value)) if (text.contains("charset=")) return RegexUtils.getPattern("=").split(text)[1]
        return "UTF-8"
    }

    companion object {

        @JvmStatic
        fun objectFrom(json: String): Req {
            return Gson().fromJson(json, Req::class.java)
        }
    }
}
