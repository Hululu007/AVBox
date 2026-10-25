package com.github.avbox.osc.ui

import android.os.Handler
import android.os.Looper
import com.github.avbox.osc.bean.EpgInfo
import com.github.avbox.osc.bean.LiveChannelItem
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.EpgUtil
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Hashtable
import java.util.Locale
import java.util.TimeZone
import org.json.JSONException

internal class LiveEpgController(private val host: Host) {

    internal interface Host {
        fun currentChannel(): LiveChannelItem?

        fun currentChannelHasLogo(): Boolean

        fun onEpgListChanged(list: ArrayList<EpgInfo>)

        fun onEpgSettled()
    }

    companion object {
        private const val EPG_LOAD_DELAY = 1200L
        private const val DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hsEpg = Hashtable<String, ArrayList<EpgInfo>>()
    private var epgStringAddress = ""
    var epgDayPresented = ""
    private var firstLiveEpgLoad = true

    private val loadEpgRun = Runnable {
        if (host.currentChannel() != null) getEpg(Date())
    }

    fun reloadAddress() {
        val userEpgAddress: String = KV.get(KVKeys.EPG_URL, "")
        epgStringAddress = if (userEpgAddress.trim { it <= ' ' }.length >= 5) {
            userEpgAddress.trim { it <= ' ' }
        } else {
            DEFAULT_EPG_ADDRESS
        }
    }

    fun cachedEpg(channelName: String): ArrayList<EpgInfo>? = hsEpg[channelName + "_" + epgDayPresented]

    fun cancelPending() {
        handler.removeCallbacks(loadEpgRun)
    }

    fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
    }

    fun loadAfterChannelStarted() {
        handler.removeCallbacks(loadEpgRun)
        if (!hasEpgAddress()) {
            host.onEpgListChanged(ArrayList())
            return
        }
        if (hasCurrentEpgCache()) {
            firstLiveEpgLoad = false
            return
        }
        if (firstLiveEpgLoad) {
            firstLiveEpgLoad = false
            handler.postDelayed(loadEpgRun, EPG_LOAD_DELAY)
        } else {
            getEpg(Date())
        }
    }

    fun getEpg(date: Date) {
        val channel = host.currentChannel() ?: return
        val channelNameStr = channel.channelName ?: return
        val channelNameReal = LiveEpgParser.normalizeEpgChannelName(LiveEpgParser.getFirstPartBeforeSpace(channelNameStr) ?: "")
        val timeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+8:00")
        }
        var epgTagName = channelNameReal
        if (!host.currentChannelHasLogo()) {
            val epgInfo = EpgUtil.getEpgInfo(channelNameReal)
            if (epgInfo != null && epgInfo[1].isNotEmpty()) {
                epgTagName = epgInfo[1]
            }
        }
        if (!hasEpgAddress()) {
            host.onEpgListChanged(ArrayList())
            return
        }
        val epgQueryNames = LiveEpgParser.buildEpgQueryNames(channelNameStr, channelNameReal, epgTagName)
        val url = LiveEpgParser.buildEpgUrl(epgStringAddress, epgQueryNames[0], date, timeFormat)
        val savedEpgKey = channelNameStr + "_" + epgDayPresented
        if (hsEpg.containsKey(savedEpgKey)) {
            showEpg(hsEpg[savedEpgKey])
            host.onEpgSettled()
            return
        }
        host.onEpgListChanged(ArrayList())
        requestEpg(url, date, channelNameReal, epgTagName, savedEpgKey, epgQueryNames, timeFormat, 0)
    }

    private fun requestEpg(
        url: String,
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        var client = HttpClients.defaultClient
        if (client == null) client = com.github.catvod.net.OkHttp.client()
        client.newCall(okhttp3.Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                handler.post {
                    onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.code != 200) {
                    response.close()
                    handler.post {
                        onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                    }
                    return
                }
                val body = try {
                    response.body.string()
                } finally {
                    response.close()
                }
                val parsed = parseEpgBody(body, date, finalEpgTagName)
                handler.post {
                    onEpgRequestResponse(parsed, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }
        })
    }

    private fun parseEpgBody(body: String?, date: Date, epgTagName: String): ArrayList<EpgInfo>? {
        if (body.isNullOrEmpty() || body.trim { it <= ' ' }.isEmpty()) return null
        AppLog.i("echo-epgTagName:$epgTagName")
        return try {
            if (LiveEpgParser.isXmlEpgResponse(body)) {
                LiveEpgParser.parseXmlEpg(body, epgTagName, date)
            } else if (body.contains("epg_data") || body.trim { it <= ' ' }.startsWith("{")) {
                LiveEpgParser.parseJsonEpg(body, date)
            } else {
                ArrayList()
            }
        } catch (jsonException: JSONException) {
            AppLog.e("LiveEpgController", jsonException)
            ArrayList()
        }
    }

    private fun onEpgRequestFailure(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        if (requestDefaultEpgOnFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        host.onEpgListChanged(ArrayList())
    }

    private fun onEpgRequestResponse(
        parsed: ArrayList<EpgInfo>?,
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (parsed == null) {
            host.onEpgListChanged(ArrayList())
            return
        }
        val arrayList = parsed
        if (arrayList.isEmpty() && requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        hsEpg[savedEpgKey] = arrayList
        if (!isCurrentEpgRequest(savedEpgKey)) return
        showEpg(arrayList)
        host.onEpgSettled()
    }

    private fun requestDefaultEpgOnFailure(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ): Boolean {
        if (DEFAULT_EPG_ADDRESS == epgStringAddress || queryIndex >= epgQueryNames.size) {
            return false
        }
        val fallbackUrl = LiveEpgParser.buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames[0], date, timeFormat)
        AppLog.i("echo-epg fallback default address")
        requestEpg(fallbackUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, epgQueryNames.size)
        return true
    }

    private fun requestNextEpgQueryName(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ): Boolean {
        if (!LiveEpgParser.isTemplateEpgAddress(epgStringAddress) || queryIndex + 1 >= epgQueryNames.size) {
            return false
        }
        val nextIndex = queryIndex + 1
        val nextUrl = LiveEpgParser.buildEpgUrl(epgStringAddress, epgQueryNames[nextIndex], date, timeFormat)
        AppLog.i("echo-epg retry query name:" + epgQueryNames[nextIndex])
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex)
        return true
    }

    private fun showEpg(arrayList: ArrayList<EpgInfo>?) {
        host.onEpgListChanged(if (arrayList != null && arrayList.isNotEmpty()) arrayList else ArrayList())
    }

    private fun hasEpgAddress(): Boolean {
        return epgStringAddress.isNotEmpty() && epgStringAddress.trim { it <= ' ' }.isNotEmpty()
    }

    private fun hasCurrentEpgCache(): Boolean {
        val channel = host.currentChannel() ?: return false
        return hsEpg.containsKey(channel.channelName + "_" + epgDayPresented)
    }

    private fun isCurrentEpgRequest(savedEpgKey: String): Boolean {
        val channel = host.currentChannel() ?: return false
        return savedEpgKey == channel.channelName + "_" + epgDayPresented
    }
}
