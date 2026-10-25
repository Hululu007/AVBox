package com.github.avbox.osc.util

import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

object EpgUtil {

    private val loader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "epg-loader") }

    @Volatile
    private var started = false

    @Volatile
    private var epgHashMap: Map<String, JsonObject> = emptyMap()

    @JvmStatic
    fun init() {
        if (started) return
        started = true
        loader.execute(::load)
    }

    private fun load() {
        Probe.step("epgUtilLoad") {
            try {
                val assetManager: AssetManager = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.assets
                val inputStreamReader = InputStreamReader(assetManager.open("epg_data.json"), "UTF-8")
                val br = BufferedReader(inputStreamReader)
                var line: String? = br.readLine()
                val builder = StringBuilder()
                while (line != null) {
                    builder.append(line)
                    line = br.readLine()
                }
                br.close()
                inputStreamReader.close()
                if (builder.toString().isEmpty()) return@step
                epgHashMap = parse(builder.toString())
            } catch (e: Throwable) {
                AppLog.e("EpgUtil", e)
            }
        }
    }

    internal fun parse(text: String): Map<String, JsonObject> {
        val root = Gson().fromJson(text, JsonObject::class.java)
        val map = HashMap<String, JsonObject>()
        for (opt: JsonElement in root.get("epgs").asJsonArray) {
            val obj = opt as JsonObject
            val name = obj.get("name").asString.trim { it <= ' ' }
            val names = RegexUtils.getPattern(",").split(name)
            for (string in names) {
                map[string] = obj
            }
        }
        return map
    }

    @JvmStatic
    fun getEpgInfo(channelName: String): Array<String>? {
        val obj = epgHashMap[channelName] ?: return null
        return try {
            arrayOf(obj.get("logo").asString, obj.get("epgid").asString)
        } catch (ex: Exception) {
            AppLog.e("EpgUtil", ex)
            null
        }
    }
}
