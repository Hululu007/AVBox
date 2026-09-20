package com.github.tvbox.osc.ui.activity

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.google.gson.JsonArray
import xyz.doikki.videoplayer.player.VideoView
import java.util.ArrayList

/** 直播页三态:加载中 / 空 / 就绪 */
internal enum class PageState { LOADING, EMPTY, READY }

/** 频道信息条的数据 */
internal data class ChannelInfoUi(
    val name: String = "",
    val num: Int = 0,
    val sourceText: String = "",
    val currentEpgTime: String = "",
    val currentEpgTitle: String = "",
    val nextEpgTime: String = "",
    val nextEpgTitle: String = "",
)

/**
 * 直播页的界面状态与设置项分发。需要动播放器/频道列表的动作一律经 [Host] 回调宿主,
 * 本类不持有 Activity/Context,只持有可观察状态与 KV 配置。
 */
internal class LivePlayViewModel : ViewModel() {

    /** 设置项分发需要宿主配合的动作(播放器、频道列表都在宿主手里) */
    internal interface Host {
        /** 当前频道;无频道返回 null */
        fun currentChannelItem(): LiveChannelItem?

        fun currentPlayerScale(): Int

        fun currentPlayerType(): Int

        /** 切线路后按新线路重播当前频道 */
        fun replayCurrentChannel()

        fun applyPlayerScale(position: Int)

        /** 切换播放内核并重播 */
        fun applyPlayerType(position: Int)

        fun releasePlayerKernel()

        fun refreshTimeOverlay()

        fun refreshNetSpeedOverlay()

        /** 切组/切配置后重建频道列表并起播 */
        fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int)

        fun setEmptyChannelList(releasePlayer: Boolean)

        fun toast(msg: String)

        fun isFinishing(): Boolean

        fun postToMain(action: Runnable)
    }

    // ---------- 界面状态 ----------

    var pageState by mutableStateOf(PageState.LOADING)
    var playState by mutableStateOf(VideoView.STATE_IDLE)
    var snapshotVisible by mutableStateOf(false)
    var snapshotBitmap by mutableStateOf<Bitmap?>(null)
    var fullScreen by mutableStateOf(false)
    var rotating by mutableStateOf(false)
    var overlayVisible by mutableStateOf(false)
    var isBackState by mutableStateOf(false)
    var epgSheetVisible by mutableStateOf(false)
    var settingsSheetVisible by mutableStateOf(false)
    var passwordDialogTarget by mutableStateOf<Pair<Int, Int>?>(null)
    var settingsVersion by mutableIntStateOf(0)
    var channelVersion by mutableIntStateOf(0)
    var epgVersion by mutableIntStateOf(0)
    var scrollTick by mutableIntStateOf(0)
    var resolutionText by mutableStateOf("")
    var resolutionVisible by mutableStateOf(false)
    var showTimeOn by mutableStateOf(false)
    var showNetSpeedOn by mutableStateOf(false)
    var timeText by mutableStateOf("")
    var netSpeedText by mutableStateOf("")
    var gestureHintText by mutableStateOf<String?>(null)
    var tsPosition by mutableIntStateOf(0)
    var tsDuration by mutableIntStateOf(0)
    var channelInfoUi by mutableStateOf(ChannelInfoUi())
    val expandedGroups = mutableStateListOf<Int>()
    var currentChannelGroupIndex by mutableIntStateOf(0)
    var currentLiveChannelIndex by mutableIntStateOf(-1)

    /** 切组/切配置的请求代号:响应回来时对不上就作废(用户可能连点了好几次) */
    private var liveConfigRequestId = 0

    // ---------- 设置项分发 ----------

    fun onSettingClicked(groupIndex: Int, position: Int, host: Host) {
        if (groupIndex in 0..2 && host.currentChannelItem() == null) {
            host.toast("请先选择频道")
            return
        }
        when (groupIndex) {
            0 -> {
                val item = host.currentChannelItem() ?: return
                if (position < 0 || position >= item.sourceNum || position == item.sourceIndex) return
                item.sourceIndex = position
                host.replayCurrentChannel()
            }
            1 -> {
                if (position == host.currentPlayerScale()) return
                host.applyPlayerScale(position)
            }
            2 -> {
                if (position == host.currentPlayerType()) return
                host.applyPlayerType(position)
            }
            3 -> {
                if (position == KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)) return
                KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position)
            }
            4 -> {
                when (position) {
                    0 -> KV.put(HawkConfig.LIVE_SHOW_TIME, !KV.get(HawkConfig.LIVE_SHOW_TIME, false)).also { host.refreshTimeOverlay() }
                    1 -> KV.put(HawkConfig.LIVE_SHOW_NET_SPEED, !KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)).also { host.refreshNetSpeedOverlay() }
                    2 -> KV.put(HawkConfig.LIVE_CHANNEL_REVERSE, !KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                    3 -> KV.put(HawkConfig.LIVE_CROSS_GROUP, !KV.get(HawkConfig.LIVE_CROSS_GROUP, false))
                }
            }
            5 -> {
                if (position == ApiConfig.getLiveGroupIndex()) return
                val currentChannelName = preferredRefreshChannelName(host)
                val currentSourceIndex = preferredRefreshSourceIndex(host)
                val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
                if (liveGroups == null || position >= liveGroups.size()) return
                liveConfigRequestId++
                val livesOBJ = liveGroups.get(position).asJsonObject
                ApiConfig.setLiveGroupIndex(position)
                ApiConfig.get().loadLiveApi(livesOBJ)
                if (ApiConfig.get().channelGroupList.isEmpty()) {
                    host.releasePlayerKernel()
                    host.setEmptyChannelList(false)
                    return
                }
                host.refreshChannelListAndPlay(currentChannelName, currentSourceIndex)
            }
            6 -> {
                val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
                val target: String
                if (position == 0) {
                    if (ApiConfig.isLiveFollowVod()) return
                    target = ""
                } else {
                    if (position - 1 >= history.size) return
                    target = history[position - 1]
                    if (target == KV.get(HawkConfig.LIVE_API_URL, "")) return
                }
                val configChannelName = preferredRefreshChannelName(host)
                val configSourceIndex = preferredRefreshSourceIndex(host)
                val requestId = ++liveConfigRequestId
                KV.put(HawkConfig.LIVE_API_URL, target)
                if (target.isNotEmpty()) HistoryHelper.setLiveApiHistory(target)
                ApiConfig.get().invalidateLiveConfig()
                ApiConfig.get().refreshLiveApiHistoryItems()
                ApiConfig.get().loadLiveConfig(false, object : ApiConfig.LoadConfigCallback {
                    override fun success() {
                        host.postToMain(Runnable {
                            if (requestId != liveConfigRequestId || host.isFinishing()) return@Runnable
                            host.refreshChannelListAndPlay(configChannelName, configSourceIndex)
                        })
                    }

                    override fun error(msg: String) {
                        host.postToMain(Runnable {
                            if (requestId != liveConfigRequestId || host.isFinishing()) return@Runnable
                            host.releasePlayerKernel()
                            ApiConfig.get().refreshLiveApiHistoryItems()
                            host.setEmptyChannelList(false)
                            host.toast(msg)
                        })
                    }

                    override fun notice(msg: String) {
                        host.postToMain(Runnable {
                            if (requestId != liveConfigRequestId || host.isFinishing()) return@Runnable
                            host.toast(msg)
                        })
                    }
                })
            }
        }
        settingsVersion++
    }

    /** 切换前记录"用户当前在看哪个频道/哪条线路",切完要回到同一个 */
    private fun preferredRefreshChannelName(host: Host): String? {
        host.currentChannelItem()?.let { return it.channelName }
        return KV.get(HawkConfig.LIVE_CHANNEL, "")
    }

    private fun preferredRefreshSourceIndex(host: Host): Int {
        host.currentChannelItem()?.let { return it.sourceIndex }
        return -1
    }
}
