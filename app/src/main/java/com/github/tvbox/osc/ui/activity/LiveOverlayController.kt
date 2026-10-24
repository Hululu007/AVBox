package com.github.tvbox.osc.ui.activity

import android.graphics.Bitmap
import android.os.Handler
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackTimes
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class LiveOverlayController(
    private val vm: LivePlayViewModel,
    private val host: Host,
    private val handler: Handler,
) {

    internal interface Host {
        fun text(resId: Int, vararg args: Any): String

        fun videoView(): MyVideoView?

        fun cachedEpg(channelName: String): List<Epginfo>?
    }

    companion object {
        private const val RESOLUTION_INFO_MAX_RETRY = 10
        private const val RESOLUTION_INFO_RETRY_DELAY = 300L
        private const val RESOLUTION_INFO_HIDE_DELAY = 3000L
        private const val OVERLAY_HIDE_DELAY = 6000L
        private const val GESTURE_HINT_HIDE_DELAY = 1000L
    }

    private var resolutionInfoRetryCount = 0
    private var resolutionInfoPending = false

    private val hideOverlayRun = Runnable { vm.updateOverlay { it.copy(visible = false) } }

    private val hideGestureHintRun = Runnable { vm.updatePlayer { it.copy(gestureHintText = null) } }

    private val hideResolutionInfoRun = Runnable {
        vm.updatePlayer { it.copy(resolutionVisible = false) }
    }

    fun scheduleOverlayHide() {
        handler.removeCallbacks(hideOverlayRun)
        handler.postDelayed(hideOverlayRun, OVERLAY_HIDE_DELAY)
    }

    fun showGestureHint(isBrightness: Boolean, percent: Int) {
        val label = host.text(if (isBrightness) R.string.live_brightness else R.string.live_volume)
        vm.updatePlayer { it.copy(gestureHintText = host.text(R.string.live_gesture_hint, label, percent)) }
        handler.removeCallbacks(hideGestureHintRun)
        handler.postDelayed(hideGestureHintRun, GESTURE_HINT_HIDE_DELAY)
    }

    fun showSwitchChannelSnapshot() {
        var bitmap: Bitmap? = null
        try {
            bitmap = host.videoView()?.doScreenShot()
        } catch (ignored: Throwable) {
            LOG.d("LiveOverlayController", "doScreenShot failed, switch-channel snapshot skipped")
        }
        vm.updatePlayer { it.copy(snapshotBitmap = bitmap, snapshotVisible = true) }
    }

    fun hideSwitchChannelSnapshot() {
        vm.updatePlayer { it.copy(snapshotVisible = false, snapshotBitmap = null) }
    }

    fun onPlaybackStarted() {
        hideSwitchChannelSnapshot()
        if (resolutionInfoPending) {
            resolutionInfoRetryCount = 0
            handler.removeCallbacks(updateResolutionInfoRun)
            handler.post(updateResolutionInfoRun)
        }
    }

    fun showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true
        resolutionInfoRetryCount = 0
        vm.updatePlayer { it.copy(resolutionText = "", resolutionVisible = false) }
        handler.removeCallbacks(hideResolutionInfoRun)
        handler.removeCallbacks(updateResolutionInfoRun)
        handler.postDelayed(updateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
    }

    private val updateResolutionInfoRun = Runnable {
        val videoView = host.videoView() ?: return@Runnable
        if (videoView.playState != PlayState.PREPARED &&
            videoView.playState != PlayState.BUFFERED &&
            videoView.playState != PlayState.PLAYING
        ) {
            retryOrHideResolutionInfo()
            return@Runnable
        }
        val videoSize = videoView.videoSize
        if (videoSize != null && videoSize.size >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
            resolutionInfoPending = false
            vm.updatePlayer {
                it.copy(
                    resolutionText = videoSize[0].toString() + " x " + videoSize[1],
                    resolutionVisible = true,
                )
            }
            handler.removeCallbacks(hideResolutionInfoRun)
            handler.postDelayed(hideResolutionInfoRun, RESOLUTION_INFO_HIDE_DELAY)
            return@Runnable
        }
        retryOrHideResolutionInfo()
    }

    private fun retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            handler.postDelayed(updateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
        } else {
            vm.updatePlayer { it.copy(resolutionVisible = false) }
        }
    }

    fun showTime() {
        val showTimeOn = KV.get(HawkConfig.LIVE_SHOW_TIME, false)
        vm.updateOverlay { it.copy(showTimeOn = showTimeOn) }
        handler.removeCallbacks(updateTimeRun)
        if (showTimeOn) handler.post(updateTimeRun)
    }

    private val updateTimeRun = object : Runnable {
        override fun run() {
            vm.updateOverlay { it.copy(timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
            handler.postDelayed(this, 1000)
        }
    }

    fun showNetSpeed() {
        val showNetSpeedOn = KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
        vm.updateOverlay { it.copy(showNetSpeedOn = showNetSpeedOn) }
        handler.removeCallbacks(updateNetSpeedRun)
        if (showNetSpeedOn) handler.post(updateNetSpeedRun)
    }

    private val updateNetSpeedRun = object : Runnable {
        override fun run() {
            val videoView = host.videoView() ?: return
            val speedText = PlayerHelper.getDisplaySpeed(videoView.tcpSpeed, true)
            vm.updateOverlay { it.copy(netSpeedText = speedText) }
            handler.postDelayed(this, 1000)
        }
    }

    private val updateTimeshiftRun = object : Runnable {
        override fun run() {
            val videoView = host.videoView() ?: return
            if (!vm.state.value.timeshift.isShiyi) return
            val position = PlaybackTimes.safeTimeMs(videoView.currentPosition)
            vm.updateTimeshift { it.copy(position = position) }
            handler.postDelayed(this, 1000)
        }
    }

    fun startTimeshiftTicker() {
        handler.removeCallbacks(updateTimeshiftRun)
        handler.postDelayed(updateTimeshiftRun, 1000)
    }

    fun stopTimeshiftTicker() {
        handler.removeCallbacks(updateTimeshiftRun)
    }

    fun onTimeshiftSeek(progress: Float) {
        val videoView = host.videoView() ?: return
        val target = progress.toInt().coerceIn(0, vm.state.value.timeshift.duration.coerceAtLeast(1))
        videoView.seekTo(target.toLong())
        vm.updateTimeshift { it.copy(position = target) }
        scheduleOverlayHide()
    }

    fun onTimeshiftTogglePlay() {
        val videoView = host.videoView() ?: return
        if (videoView.isPlaying) videoView.pause() else videoView.start()
        scheduleOverlayHide()
    }

    fun updateChannelInfoUi() {
        if (vm.state.value.timeshift.isShiyi) return
        val channel = vm.state.value.channelList.playingChannel ?: return
        val name = channel.channelName ?: return
        var ui = ChannelInfoUi(name = name, num = channel.channelNum)
        ui = if (channel.sourceNum <= 0) {
            ui.copy(sourceText = "1/1")
        } else {
            ui.copy(sourceText = host.text(R.string.live_line_index, vm.currentSourceIndex() + 1, channel.sourceNum))
        }
        var current = ""
        var currentTitle = ""
        var next = ""
        var nextTitle = ""
        val cached = host.cachedEpg(name)
        vm.updateEpg { it.copy(epgList = if (cached.isNullOrEmpty()) emptyList() else cached) }
        val timeZone = TimeZone.getTimeZone("GMT+8:00")
        val currentStart = Calendar.getInstance(timeZone)
        currentStart.set(Calendar.MINUTE, 0)
        currentStart.set(Calendar.SECOND, 0)
        currentStart.set(Calendar.MILLISECOND, 0)
        val currentEnd = (currentStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val nextStart = (currentEnd.clone() as Calendar).apply { add(Calendar.MINUTE, 1) }
        val nextEnd = (nextStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeFormat.timeZone = timeZone
        var hasInfo = false
        val list = vm.state.value.epg.epgList
        if (list.isNotEmpty()) {
            val date = Date()
            var size = list.size - 1
            while (size >= 0) {
                val info = list[size]
                if (info.startdateTime != null && info.enddateTime != null &&
                    date.after(info.startdateTime) && date.before(info.enddateTime)
                ) {
                    current = info.start + "-" + info.end
                    currentTitle = info.title.orEmpty()
                    if (size != list.size - 1) {
                        next = list[size + 1].start + "-" + list[size + 1].end
                        nextTitle = list[size + 1].title.orEmpty()
                    } else {
                        next = info.end + "-23:59"
                        nextTitle = host.text(R.string.live_epg_hot_no_info)
                    }
                    hasInfo = true
                    break
                } else {
                    size--
                }
            }
        }
        if (!hasInfo) {
            current = timeFormat.format(currentStart.time) + "-" + timeFormat.format(currentEnd.time)
            currentTitle = host.text(R.string.live_epg_hot)
            next = timeFormat.format(nextStart.time) + "-" + timeFormat.format(nextEnd.time)
            nextTitle = host.text(R.string.live_epg_no_info)
        }
        vm.updateChannelInfo(
            ui.copy(
                currentEpgTime = current,
                currentEpgTitle = currentTitle,
                nextEpgTime = next,
                nextEpgTitle = nextTitle,
            ),
        )
    }
}
