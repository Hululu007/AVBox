package com.github.tvbox.osc.ui.player

import android.os.Looper
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.danmu.DanmuLoadController
import com.github.tvbox.osc.player.state.DanmuSearchSheetState
import com.github.tvbox.osc.util.LOG
import master.flame.danmaku.ui.widget.DanmakuView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val PRELOAD_TOAST_REFRESH_DELAY_MS = 1000L

data class PlayerTipState(
    val msg: String = "",
    val loading: Boolean = false,
    val err: Boolean = false,
)

internal class PlayContainerOverlays(private val host: PlayContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var preloadReadyToast: Toast? = null

    private var mDanmuView: DanmakuView? = null

    private val refreshPreloadToastRunnable: Runnable = Runnable {
        val toast = preloadReadyToast ?: return@Runnable
        toast.show()
    }

    fun onTipStateChanged(tip: PlayerTipState) {
        if (host.mHandler == null) return
        val showing = tip.loading || tip.err
        host.mHandler?.post {
            if (host.mController != null) {
                host.mController.getUiState().applyTip(tip.msg, tip.loading, tip.err)
            }
            if (host.danmuLoadController != null) host.danmuLoadController?.setOverlayHidden(showing)
        }
    }

    fun initDanmuView() {
        mDanmuView = host.findViewById(R.id.danmaku)
        host.danmuLoadController = DanmuLoadController(host.mVideoView, host.mController, mDanmuView)
    }

    fun setDanmuViewSettings(reload: Boolean) {
        val controller = host.danmuLoadController ?: return
        controller.applySettings(reload)
    }

    fun applyDanmuSettings(reload: Boolean) {
        setDanmuViewSettings(reload)
    }

    fun checkDanmu(danmu: String?) {
        checkDanmu(danmu, null)
    }

    fun checkDanmu(danmu: String?, callback: DanmuLoadController.LoadCallback?) {
        host.scheduler.onDanmuChecked(danmu)
        val controller = host.danmuLoadController ?: return
        val vod = host.scheduler.vod()
        val series = if (vod == null) null else host.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        controller.check(danmu, vod?.name ?: "", series?.name ?: "", callback)
    }

    fun startDanmuIfReady() {
        host.danmuLoadController?.startIfReady()
    }

    fun resetDanmuState() {
        host.danmuLoadController?.reset()
    }

    fun reloadDanmuForPlayback() {
        host.danmuLoadController?.reloadForPlayback()
    }

    fun openDanmuSearchSheet() {
        if (!host.isAttached()) return
        val vod = host.scheduler.vod()
        val series = if (vod == null) null else host.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        val uiState = host.mController.getUiState()
        uiState.openDanmuSearchSheet(DanmuSearchSheetState(
            series?.name ?: "",
            host.scheduler.vod()?.name ?: "",
        ) { danmu ->
            if (host.isAttached()) {
                checkDanmu(danmu)
            }
        })
    }

    fun setTip(msg: String, loading: Boolean, err: Boolean) {
        if (!host.isAttached()) return
        onTipStateChanged(PlayerTipState(msg, loading, err))
    }

    fun hideTip() {
        onTipStateChanged(PlayerTipState())
    }

    fun hideTipOnUiThread() {
        if (!host.isAttached()) return
        onTipStateChanged(PlayerTipState())
    }

    fun showPreloadReady() {
        val activity = host.mActivity
        val handler = host.mHandler
        if (activity == null || !host.isAttached() || handler == null) return
        preloadReadyToast?.cancel()
        val toast = Toast.makeText(activity, activity.getString(R.string.player_next_episode_ready), Toast.LENGTH_SHORT)
        preloadReadyToast = toast
        toast.show()
        handler.removeCallbacks(refreshPreloadToastRunnable)
        handler.postDelayed(refreshPreloadToastRunnable, PRELOAD_TOAST_REFRESH_DELAY_MS)
    }

    fun hidePreloadReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPreloadToast()
        } else {
            host.mActivity?.runOnUiThread {
                cancelPreloadToast()
            }
        }
    }

    fun cancelPreloadToast() {
        host.mHandler?.removeCallbacks(refreshPreloadToastRunnable)
        val toast = preloadReadyToast
        if (toast != null) {
            toast.cancel()
            preloadReadyToast = null
        }
    }

    fun errorWithRetry(err: String, finish: Boolean, playbackError: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            host.mHandler?.post { errorWithRetry(err, finish, playbackError) }
            return
        }
        if (host.scheduler.isPlaybackStarted()) {
            host.scheduler.cancelPlayTimeout()
            hideTipOnUiThread()
            if (host.scheduler.retryAfterStartedError()) return
            scope.launch {
                if (host.scheduler.autoRetry()) return@launch
                host.scheduler.stopMusicSessionForFailedPlayback()
                if (!host.isAttached()) return@launch
                val text = terminalErrorText(err, playbackError)
                setTip(text, false, true)
                if (finish) {
                    Toast.makeText(host.context, text, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        scope.launch {
            if (!host.scheduler.autoRetry()) {
                host.scheduler.cancelPlayTimeout()
                host.scheduler.stopMusicSessionForFailedPlayback()
                if (!host.isAttached()) return@launch
                val text = terminalErrorText(err, playbackError)
                setTip(text, false, true)
                if (finish) {
                    Toast.makeText(host.context, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun terminalErrorText(err: String, playbackError: Boolean): String {
        val httpStatus = if (playbackError) host.scheduler.errorHttpStatusForTip() else 0
        val text = when {
            host.scheduler.isSourceStub() -> host.context.getString(R.string.player_source_invalid)
            host.scheduler.isIsoBandwidthStopped() -> host.context.getString(R.string.player_iso_bandwidth_low)
            host.scheduler.isLastErrorExceedsDecodeCapability() -> host.context.getString(
                if (host.scheduler.isLastErrorTenBitFormat()) R.string.player_decode_10bit_tip
                else R.string.player_decode_exceeds_capability,
            )
            httpStatus > 0 -> host.context.getString(R.string.player_error_play_http, httpStatus)
            else -> err
        }
        LOG.i(
            "echo-player tip: $text | stub=" + host.scheduler.isSourceStub() +
                " bandwidth=" + host.scheduler.isIsoBandwidthStopped() +
                " exceeds=" + host.scheduler.isLastErrorExceedsDecodeCapability() +
                " tenBit=" + host.scheduler.isLastErrorTenBitFormat() +
                " http=" + httpStatus,
        )
        return text
    }
}
