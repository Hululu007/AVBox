package com.github.tvbox.osc.ui.activity

import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackTimes
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Date
import java.util.regex.Pattern

internal class LiveCatchupController(
    private val vm: LivePlayViewModel,
    private val host: Host,
    private val overlay: LiveOverlayController,
) {

    internal interface Host {
        fun updateLogoUrl(url: String?)

        fun videoView(): MyVideoView?

        fun releasePlayerKernel()

        fun liveChannelHeader(): HashMap<String, String>?

        fun loadEpgAfterChannelStarted()
    }

    private var catchup: JsonObject? = null
    private var playUrl: String? = null
    private var shiyiTimeC = 0

    fun initLiveObj() {
        catchup = null
        vm.updateLogoUrl(null)
        val position = ApiConfig.getLiveGroupIndex()
        val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        if (liveGroups == null || liveGroups.size() == 0 || position < 0 || position >= liveGroups.size()) {
            return
        }
        val livesOBJ = liveGroups.get(position).asJsonObject
        val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else "0"
        if (livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject) {
            catchup = livesOBJ.getAsJsonObject("catchup")
            LOG.i("echo-catchup :$catchup")
        }
        if (livesOBJ.has("logo")) {
            vm.updateLogoUrl(livesOBJ.get("logo").asString)
        }
        if (type == "3") {
            var pyJar = ""
            if (livesOBJ.has("jar")) {
                pyJar = livesOBJ.get("jar").asString
            } else if (livesOBJ.has("api")) {
                pyJar = livesOBJ.get("api").asString
                val ext = if (livesOBJ.has("ext") &&
                    (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)
                ) {
                    livesOBJ.get("ext").toString()
                } else {
                    DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                }
                LOG.i("echo-ext:$ext")
                if (ext.isNotEmpty()) pyJar = "$pyJar?extend=$ext"
            }
            ApiConfig.get().setLiveJar(pyJar)
        }
    }

    fun onEpgRowClicked(position: Int): Boolean {
        if (position == vm.state.value.epg.lookBackIndex) return false
        val selectedData = vm.state.value.epg.epgList.getOrNull(position) ?: return false
        if (selectedData.startdateTime == null || selectedData.enddateTime == null) return false
        val now = Date()
        if (now.before(selectedData.startdateTime)) return false
        if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return false
        vm.updateEpg { it.copy(lookBackIndex = position) }
        var switched = false
        if (!now.before(selectedData.startdateTime) && !now.after(selectedData.enddateTime)) {
            backToLiveFromEpg()
            switched = true
        } else if (canCurrentChannelCatchup()) {
            startCatchupReplay(selectedData)
            switched = true
        }
        return switched
    }

    private fun startCatchupReplay(epg: Epginfo) {
        val item = vm.state.value.channelList.playingChannel ?: return
        val videoView = host.videoView() ?: return
        host.releasePlayerKernel()
        vm.updateTimeshift { it.copy(isShiyi = true) }
        val shiyiUrl = buildCatchupUrl(item.urlAt(vm.currentSourceIndex()), epg)
        if (TextUtils.isEmpty(shiyiUrl)) return
        LOG.i("echo-回看地址playUrl :$shiyiUrl")
        playUrl = shiyiUrl
        videoView.setUrl(shiyiUrl, host.liveChannelHeader())
        videoView.start()
        shiyiTimeC = LiveEpgParser.getCatchupDurationSeconds(epg)
        val duration = PlaybackTimes.safeTimeMs(shiyiTimeC.toLong() * 1000)
        val position = PlaybackTimes.safeTimeMs(videoView.currentPosition)
        vm.updateTimeshift { it.copy(duration = duration, position = position, isBackState = true) }
        vm.updateOverlay { it.copy(visible = true) }
        overlay.startTimeshiftTicker()
        overlay.scheduleOverlayHide()
    }

    fun backToLiveFromEpg() {
        val item = vm.state.value.channelList.playingChannel ?: return
        val videoView = host.videoView() ?: return
        overlay.stopTimeshiftTicker()
        host.releasePlayerKernel()
        vm.updateTimeshift { it.copy(isShiyi = false, isBackState = false) }
        vm.updateOverlay { it.copy(visible = false) }
        videoView.setUrl(item.urlAt(vm.currentSourceIndex()), host.liveChannelHeader())
        videoView.start()
    }

    private fun currentChannelHasCatchup(): Boolean {
        val item = vm.state.value.channelList.playingChannel ?: return false
        return LiveEpgParser.hasCatchupSource(item.channelCatchup)
    }

    private fun currentCatchup(): JsonObject? {
        val channel = vm.state.value.channelList.playingChannel
        if (channel != null && currentChannelHasCatchup()) return channel.channelCatchup
        return catchup
    }

    fun canCurrentChannelCatchup(): Boolean {
        val item = vm.state.value.channelList.playingChannel ?: return false
        val url = item.urlAt(vm.currentSourceIndex())
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            val regex = LiveEpgParser.getCatchupValue(catchupObj, "regex")
            if (TextUtils.isEmpty(regex)) return true
            return try {
                url.contains(regex) || Pattern.compile(regex).matcher(url).find()
            } catch (ignored: Throwable) {
                false
            }
        }
        return url.contains("/PLTV/")
    }

    private fun buildCatchupUrl(url: String, epg: Epginfo?): String {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return ""
        val catchupObj = currentCatchup()
        if (catchupObj != null && LiveEpgParser.hasCatchupSource(catchupObj)) {
            return LiveEpgParser.formatCatchupUrl(url, catchupObj, epg)
        }
        if (!url.contains("/PLTV/")) return ""
        val startTime = epg.startdateTime ?: return ""
        val endTime = epg.enddateTime ?: return ""
        val source = "?playseek=" + LiveEpgParser.formatCatchupTime(startTime, "yyyyMMddHHmmss") +
                "-" + LiveEpgParser.formatCatchupTime(endTime, "yyyyMMddHHmmss")
        return LiveEpgParser.appendCatchupUrl(url, "/PLTV/,/TVOD/", source)
    }

    fun loadEpgAfterChannelStarted() {
        host.loadEpgAfterChannelStarted()
    }
}
