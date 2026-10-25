package com.github.avbox.osc.net

import android.util.Base64

import com.github.avbox.osc.util.AppLog

import com.github.avbox.osc.util.parser.JsonParallel
import com.github.avbox.osc.util.parser.WebViewJs
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object SuperParse {
    @JvmField
    val flagWebJx: ConcurrentHashMap<String, ArrayList<String>> = ConcurrentHashMap()
    private var configs: HashMap<String, ArrayList<String>>? = null
    private val configsLock = Any()

    class ParseTargets(@JvmField val jsonJx: LinkedHashMap<String, String>, @JvmField val webJx: ArrayList<String>)

    private fun ensureConfigs(jx: LinkedHashMap<String, HashMap<String, String>>) {
        synchronized(configsLock) {
            if (configs != null) return
            val built = HashMap<String, ArrayList<String>>()
            for ((key, parseBean) in jx) {
                if (parseBean == null) {
                    continue
                }
                val type = parseBean["type"]
                if (type == null) {
                    continue
                }
                if ("1" == type || "0" == type) {
                    try {
                        val ext = parseBean["ext"]
                        if (ext == null) {
                            continue
                        }
                        val flagsArray = JSONObject(ext).getJSONArray("flag")
                        for (j in 0 until flagsArray.length()) {
                            val flagKey = flagsArray.getString(j)
                            var flagJx = built[flagKey]
                            if (flagJx == null) {
                                flagJx = ArrayList()
                                built[flagKey] = flagJx
                            }
                            flagJx.add(key)
                        }
                    } catch (e: Exception) {
                        AppLog.d("SuperParse", "" + e.message)
                    }
                }
            }
            configs = built
        }
    }

    @JvmStatic
    fun buildTargets(jx: LinkedHashMap<String, HashMap<String, String>>, flag: String): ParseTargets {
        ensureConfigs(jx)
        val jsonJx = LinkedHashMap<String, String>()
        val webJx = ArrayList<String>()
        val targetKeys = configs.orEmpty()[flag]
        if (targetKeys != null && !targetKeys.isEmpty()) {
            for (key in targetKeys) {
                val parseBean = jx[key]
                if (parseBean == null) {
                    continue
                }
                val type = parseBean["type"]
                if ("1" == type) {
                    val urlValue = parseBean["url"]
                    val ext = parseBean["ext"]
                    if (urlValue != null && ext != null) {
                        jsonJx[key] = mixUrl(urlValue, ext)
                    }
                } else if ("0" == type) {
                    val urlValue = parseBean["url"]
                    if (urlValue != null) {
                        webJx.add(urlValue)
                    }
                }
            }
        } else {
            for ((key, parseBean) in jx) {
                if (parseBean == null) {
                    continue
                }
                val type = parseBean["type"]
                if ("1" == type) {
                    val urlValue = parseBean["url"]
                    val ext = parseBean["ext"]
                    if (urlValue != null && ext != null) {
                        jsonJx[key] = mixUrl(urlValue, ext)
                    }
                } else if ("0" == type) {
                    val urlValue = parseBean["url"]
                    if (urlValue != null) {
                        webJx.add(urlValue)
                    }
                }
            }
        }
        return ParseTargets(jsonJx, webJx)
    }

    @JvmStatic
    fun parse(jx: LinkedHashMap<String, HashMap<String, String>>, flag: String, url: String): JSONObject {
        return parse(jx, flag, url, buildTargets(jx, flag))
    }

    @JvmStatic
    fun parse(jx: LinkedHashMap<String, HashMap<String, String>>, flag: String, url: String, targets: ParseTargets): JSONObject {
        try {
            if (!targets.webJx.isEmpty()) {
                flagWebJx[flag] = targets.webJx
                val webResult = JSONObject()
                webResult.put("url", "proxy://go=SuperParse&flag=" + flag + "&url=" + Base64.encodeToString(url.toByteArray(Charset.defaultCharset()), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP))
                webResult.put("parse", 1)
                webResult.put("ua", WebViewJs.UaWinChrome)
                return webResult
            }
        } catch (e: Exception) {
            AppLog.i("echo-result" + e.message)
        }
        return JSONObject()
    }

    @JvmStatic
    fun doJsonJx(json_jxs: LinkedHashMap<String, String>, url: String): JSONObject {
        AppLog.i("echo-jsonJx1" + json_jxs.toString())
        return JsonParallel.parse(json_jxs, url)
    }

    @JvmStatic
    fun stopJsonJx() {
        JsonParallel.cancelTasks()
    }

    private fun mixUrl(url: String, ext: String): String {
        if (ext.trim { it <= ' ' }.length > 0) {
            val idx = url.indexOf("?")
            if (idx > 0) {
                return url.substring(0, idx + 1) + "cat_ext=" + Base64.encodeToString(ext.toByteArray(Charset.defaultCharset()), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP) + "&" + url.substring(idx + 1)
            }
        }
        return url
    }

    @JvmStatic
    fun loadHtml(flag: String?, url: String?): Array<Any?>? {
        try {
            val decodedUrl = String(Base64.decode(url, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            var html = "\n" +
                    "<!doctype html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "<title>解析</title>\n" + // i18n: keep(注入 HTML 片段)
                    "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
                    "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=EmulateIE10\" />\n" +
                    "<meta name=\"renderer\" content=\"webkit|ie-comp|ie-stand\">\n" +
                    "<meta name=\"viewport\" content=\"width=device-width\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<script>\n" +
                    "var apiArray=[#jxs#];\n" +
                    "var urlPs=\"#url#\";\n" +
                    "var iframeHtml=\"\";\n" +
                    "for(var i=0;i<apiArray.length;i++){\n" +
                    "var URL=apiArray[i]+urlPs;\n" +
                    "iframeHtml=iframeHtml+\"<iframe sandbox='allow-scripts allow-same-origin allow-forms' frameborder='0' allowfullscreen='true' webkitallowfullscreen='true' mozallowfullscreen='true' src=\"+URL+\"></iframe>\";\n" +
                    "}\n" +
                    "document.write(iframeHtml);\n" +
                    "</script>\n" +
                    "</body>\n" +
                    "</html>"

            val jxs = StringBuilder()
            val flagKey = flag ?: ""
            if (flagWebJx.containsKey(flagKey)) {
                val jxUrls = flagWebJx[flagKey].orEmpty()
                for (i in 0 until jxUrls.size) {
                    jxs.append("\"")
                    jxs.append(jxUrls[i])
                    jxs.append("\"")
                    if (i < jxUrls.size - 1) {
                        jxs.append(",")
                    }
                }
            }
            html = html.replace("#url#", decodedUrl).replace("#jxs#", jxs.toString())
            val result = arrayOfNulls<Any>(3)
            result[0] = 200
            result[1] = "text/html; charset=\"UTF-8\""
            val baos = ByteArrayInputStream(html.toString().toByteArray(Charsets.UTF_8))
            result[2] = baos
            return result
        } catch (th: Throwable) {
            AppLog.e("SuperParse", th)
        }
        return null
    }
}
