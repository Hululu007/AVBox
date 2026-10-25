package com.github.avbox.osc.ui

import android.graphics.Bitmap
import android.os.Handler
import androidx.compose.runtime.snapshotFlow
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.EpgInfo
import com.github.avbox.osc.player.PlayerKernelView
import com.github.avbox.osc.player.PlaybackTimes
import com.github.avbox.osc.player.controller.PlayerTextFormat
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.ui.settings.LiveSettingsState
import com.github.avbox.osc.util.AppLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal class LiveOverlayController(
    private val vm: LivePlayViewModel,
    private val host: Host,
    private val handler: Handler,
    private val scope: CoroutineScope,
) {

    private var timeJob: Job? = null
    private var netSpeedJob: Job? = null
    private var timeshiftJob: Job? = null

    init {
        scope.launch {
            snapshotFlow { LiveSettingsState.config.showTime }
                .drop(1)
                .distinctUntilChanged()
                .collect { showTime() }
        }
        scope.launch {
            snapshotFlow { LiveSettingsState.config.showNetSpeed }
                .drop(1)
                .distinctUntilChanged()
                .collect { showNetSpeed() }
        }
    }

    internal interface Host {
        fun text(resId: Int, vararg args: Any): String

        fun videoView(): PlayerKernelView?

        fun cachedEpg(channelName: String): List<EpgInfo>?
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
            AppLog.d("LiveOverlayController", "doScreenShot failed, switch-channel snapshot skipped")
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
        val showTimeOn = LiveSettingsState.config.showTime
        vm.updateOverlay { it.copy(showTimeOn = showTimeOn) }
        timeJob?.cancel()
        timeJob = if (showTimeOn) scope.launch {
            while (true) {
                vm.updateOverlay { it.copy(timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
                delay(1000)
            }
        } else null
    }

    fun showNetSpeed() {
        val showNetSpeedOn = LiveSettingsState.config.showNetSpeed
        vm.updateOverlay { it.copy(showNetSpeedOn = showNetSpeedOn) }
        netSpeedJob?.cancel()
        netSpeedJob = if (showNetSpeedOn) scope.launch {
            while (true) {
                val videoView = host.videoView() ?: return@launch
                val speedText = PlayerTextFormat.displaySpeed(videoView.tcpSpeed, true)
                vm.updateOverlay { it.copy(netSpeedText = speedText) }
                delay(1000)
            }
        } else null
    }

    fun startTimeshiftTicker() {
        timeshiftJob?.cancel()
        timeshiftJob = scope.launch {
            while (true) {
                delay(1000)
                val videoView = host.videoView() ?: return@launch
                if (!vm.state.value.timeshift.isShiyi) return@launch
                val position = PlaybackTimes.safeTimeMs(videoView.currentPosition)
                vm.updateTimeshift { it.copy(position = position) }
            }
        }
    }

    fun stopTimeshiftTicker() {
        timeshiftJob?.cancel()
        timeshiftJob = null
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
