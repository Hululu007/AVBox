package com.undcover.freedom.pyramid

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log

import androidx.media3.common.util.UriUtil

import com.chaquo.python.PyObject
import com.github.catvod.crawler.Spider
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.RegexUtils

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.HashMap
import java.util.Objects

class PythonSpider : Spider {
    private var app: PyObject? = null
    private var pySpider: PyObject? = null
    private var loadSuccess = false
    private var cachePath = ""
    private var name = ""

    constructor() : this(PythonLoader.getInstance().getCachePath())

    constructor(cache: String) : this("", cache)

    constructor(name: String, cache: String) : super() {
        this.cachePath = cache
        this.name = name
    }

    override fun init(context: Context?) {
        requireNotNull(app).callAttr("init", pySpider)
    }

    override fun init(context: Context?, url: String?) {
        init(context, url, "")
    }

    fun init(context: Context?, url: String?, extend: String?) {
        val pyApp = requireNotNull(PythonLoader.getInstance().pyApp)
        app = pyApp
        val retValue = pyApp.callAttr("downloadPlugin", cachePath, url)
        val uri = Uri.parse(requireNotNull(url))
        var extInfo = extend
        if (extInfo == null || extInfo.length == 0) extInfo = uri.getQueryParameter("extend")
        if (null == extInfo) extInfo = ""
        val path = retValue.toString()
        Log.i("PyLoader", "echo-init path: " + path)
        val file = File(path)
        if (file.exists()) {
            val spider = pyApp.callAttr("loadFromDisk", path)
            pySpider = spider
            try {
                spider.put("siteKey", name)
            } catch (ignored: Exception) {
                AppLog.d("PyLoader", "set siteKey failed")
            }

            val poList = pyApp.callAttr("getDependence", spider).asList()
            for (po in poList) {
                val api = po.toString()
                Log.i("PyLoader", "echo-init api: " + api)
                var depUrl = PythonLoader.getInstance().getUrlByApi(api)
                if (depUrl.isEmpty()) depUrl = resolveDependenceUrl(url, api)
                if (!depUrl.isEmpty()) {
                    Log.i("PyLoader", "echo-init depUrl: " + depUrl)
                    val tmpPath = pyApp.callAttr("downloadPlugin", cachePath, depUrl).toString()
                    if (!File(tmpPath).exists()) {
                        PyToast.showCancelableToast(api + "加载失败!")
                        return
                    } else {
                        pyApp.callAttr("registerPluginAlias", api, tmpPath)
                        PyLog.d(api + ": 加载插件依赖成功！")
                    }
                }
            }
            pyApp.callAttr("init", spider, extInfo)
            loadSuccess = true
            PyLog.d(name + ": 下載插件成功！")
        } else {
            PyToast.showCancelableToast(name + "下载插件失败")
        }
    }

    private fun resolveDependenceUrl(baseUrl: String?, api: String?): String {
        if (api == null || api.isEmpty()) return ""
        val dep = if (api.endsWith(".py")) api else api + ".py"
        return UriUtil.resolve(baseUrl, dep)
    }

    fun getName(): String {
        return if (name.isEmpty()) {
            val po = requireNotNull(app).callAttr("getName", pySpider)
            po.toString()
        } else {
            name
        }
    }

    fun isLoadSuccess(): Boolean {
        return loadSuccess && pySpider != null
    }

    fun map2json(extend: HashMap<String, String>?): JSONObject {
        val jo = JSONObject()
        try {
            if (extend != null) {
                for (key in extend.keys) {
                    jo.put(key, extend[key])
                }
            }
        } catch (e: JSONException) {
            AppLog.e("PythonSpider", e)
        }
        return jo
    }

    fun map2json(extend: Map<*, *>?): JSONObject {
        val jo = JSONObject()
        try {
            if (extend != null) {
                for (key in extend.keys) {
                    jo.put(key.toString(), extend[key])
                }
            }
        } catch (e: JSONException) {
            AppLog.e("PythonSpider", e)
        }
        return jo
    }

    fun list2json(array: List<String>?): JSONArray {
        val ja = JSONArray()
        if (array != null) {
            for (str in array) {
                ja.put(str)
            }
        }
        return ja
    }

    fun paramLog(vararg obj: Any?): String {
        val sb = StringBuilder()
        sb.append("request params:[")
        for (o in obj) {
            sb.append(o).append("-")
        }
        sb.append("]")
        return sb.toString()
    }

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? {
        val proxyResult = requireNotNull(app).callAttr("localProxy", pySpider, map2json(params).toString())
        if (proxyResult == null) return null
        val list = proxyResult.asList()
        if (list == null || list.size < 3) return null
        val base64 = list.size > 4 && list[4].toInt() == 1
        val headerAvailable = list.size > 3 && list[3] != null
        val result = arrayOfNulls<Any?>(4)
        result[0] = list[0].toInt()
        result[1] = list[1].toString()
        result[2] = getStream(list[2], base64)
        result[3] = if (headerAvailable) getHeader(list[3]) else null
        return result
    }

    private fun getHeader(headerObj: PyObject?): Map<String, String>? {
        if (headerObj == null) {
            return null
        }
        val headerMap = HashMap<String, String>()
        for (key in headerObj.asMap().keys) {
            headerMap[key.toString()] = Objects.requireNonNull(headerObj.asMap()[key]).toString()
        }
        return headerMap
    }

    private fun getStream(o: PyObject?, base64: Boolean): ByteArrayInputStream {
        if (o == null) return ByteArrayInputStream(ByteArray(0))
        val typeStr = o.type().toString()
        if (typeStr.contains("bytes")) return ByteArrayInputStream(o.toJava(ByteArray::class.java))
        var content = o.toString()
        if (base64 && content.contains("base64,")) {
            content = RegexUtils.getPattern("base64,").split(content)[1]
        }
        return ByteArrayInputStream(if (base64) decode(content) else content.toByteArray(Charset.defaultCharset()))
    }

    fun replaceLocalUrl(content: String): String {
        return content.replace("http://127.0.0.1:UndCover/proxy", PythonLoader.getInstance().localProxyUrl())
    }

    override fun homeContent(filter: Boolean): String {
        if (pySpider == null) return "{}"
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("homeContent-$name", paramLog(filter))
        val po = requireNotNull(app).callAttr("homeContent", pySpider, filter)
        val rsp = po.toString()
        PyLog.nw("homeContent-$name", rsp)
        return rsp
    }

    override fun homeVideoContent(): String {
        PyLog.nw("homeVideoContent-$name", "")
        val po = requireNotNull(app).callAttr("homeVideoContent", pySpider)
        val rsp = po.toString()
        PyLog.nw("homeVideoContent-$name", rsp)
        return rsp
    }

    override fun categoryContent(tid: String?, pg: String, filter: Boolean, extend: HashMap<String, String>?): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("categoryContent-$name", paramLog(tid, pg, filter, map2json(extend).toString()))
        val po = requireNotNull(app).callAttr("categoryContent", pySpider, tid, pg, filter, map2json(extend).toString())
        val rsp = po.toString()
        PyLog.nw("categoryContent-$name", rsp)
        return rsp
    }

    override fun detailContent(ids: List<String>?): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("detailContent-$name", paramLog(list2json(ids).toString()))
        val po = requireNotNull(app).callAttr("detailContent", pySpider, list2json(ids).toString())
        val rsp = po.toString()
        PyLog.nw("detailContent-$name", rsp)
        return rsp
    }

    override fun searchContent(key: String?, quick: Boolean): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("searchContent-$name", paramLog(key, quick))
        val po = requireNotNull(app).callAttr("searchContent", pySpider, key, quick)
        val rsp = po.toString()
        PyLog.nw("searchContent-$name", rsp)
        return rsp
    }

    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String {
        if (PyLog.isNetworkLogEnabled()) PyLog.nw("playerContent-$name", paramLog(flag, id, list2json(vipFlags).toString()))
        val po = requireNotNull(app).callAttr("playerContent", pySpider, flag, id, list2json(vipFlags).toString())
        val rsp = replaceLocalUrl(po.toString())
        PyLog.nw("playerContent-$name", rsp)
        return rsp
    }

    override fun liveContent(url: String?): String {
        PyLog.nw("liveContent-$name", "")
        val po = requireNotNull(app).callAttr("liveContent", pySpider, url)
        val rsp = po.toString()
        PyLog.nw("liveContent-$name", rsp)
        return rsp
    }

    override fun isVideoFormat(url: String?): Boolean {
        return false
    }

    override fun manualVideoCheck(): Boolean {
        return false
    }

    override fun destroy() {
        try {
            val pyApp = app
            if (pyApp != null && pySpider != null) pyApp.callAttr("destroy", pySpider)
        } catch (ignored: Exception) {
            AppLog.d("PyLoader", "python spider destroy failed")
        }
    }

    companion object {
        @JvmStatic
        fun decode(s: String): ByteArray {
            return decode(s, Base64.DEFAULT or Base64.NO_WRAP)
        }

        @JvmStatic
        fun decode(s: String, flags: Int): ByteArray {
            return Base64.decode(s, flags)
        }
    }
}
