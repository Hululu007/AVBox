package com.github.avbox.osc.player.danmu

import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.view.View
import com.github.avbox.osc.api.DanmakuApi
import com.github.avbox.osc.player.PlayerKernelView
import com.github.avbox.osc.player.controller.PlayerControlApi
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.util.DanmuSettingsState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.PlayerUtils
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.ui.widget.DanmakuView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val DANMU_FONT_WEIGHT = 500
private const val DANMU_MARGIN_DP = 12
private const val DANMU_PLACEHOLDER_MAX_COUNT = 2
private const val DANMU_PLACEHOLDER_MAX_RETRY = 2
private const val DANMU_PLACEHOLDER_RETRY_BASE_MS = 3000L

class DanmuLoadController(
    private var videoView: PlayerKernelView?,
    private val controller: PlayerControlApi?,
    private val danmuView: DanmakuView?,
) {

    fun interface LoadCallback {
        fun onFailed()
    }

    private val danmakuContext: DanmakuContext = DanmakuContext.create()

    private val danmuTypeface: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, DANMU_FONT_WEIGHT, false)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private val danmuMarginPx: Int by lazy {
        val context = danmuView?.context ?: return@lazy DANMU_MARGIN_DP
        PlayerUtils.dp2px(context, DANMU_MARGIN_DP.toFloat())
    }
    private val loadSeq = AtomicInteger()
    private var executor: ExecutorService? = null
    private var danmuText: String = ""
    private var danmuTitle: String = ""
    private var danmuEpisode: String = ""
    private var startedSeq: Int = -1
    private var pendingPrepare: Boolean = false
    private var temporarilyClosed: Boolean = false
    private var placeholderRetryCount: Int = 0

    private var overlayHidden: Boolean = false
    private var loadCallback: LoadCallback? = null

    init {
        danmakuContext.updateMethod = 0
        videoView?.setDanmuView(danmuView)
        applySettings(false)
    }

    fun setVideoView(videoView: PlayerKernelView?) {
        this.videoView = videoView
        if (videoView != null && danmuView != null) {
            videoView.setDanmuView(danmuView)
        }
    }

    fun applySettings(reload: Boolean) {
        if (danmuView == null) return
        val config = DanmuSettingsState.config
        if (!config.open) {
            releaseView()
            controller?.setHasDanmu(!TextUtils.isEmpty(danmuText))
            return
        }
        val maxLines = HashMap<Int, Int>()
        val maxLine = config.maxLine
        maxLines[BaseDanmaku.TYPE_FIX_TOP] = maxLine
        maxLines[BaseDanmaku.TYPE_SCROLL_RL] = maxLine
        maxLines[BaseDanmaku.TYPE_SCROLL_LR] = maxLine
        maxLines[BaseDanmaku.TYPE_FIX_BOTTOM] = 0
        danmakuContext.setMaximumLines(maxLines)
            .setScrollSpeedFactor(config.speed)
            .setDanmakuTransparency(config.alpha)
            .setScaleTextSize(config.sizeScale)
            .setTypeface(danmuTypeface)
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 4f)
            .setDanmakuMargin(danmuMarginPx)
        if (reload && !TextUtils.isEmpty(danmuText) && config.open) {
            prepare(danmuText)
        }
    }

    fun check(danmu: String?) {
        check(danmu, "", "")
    }

    fun check(danmu: String?, title: String?, episode: String?) {
        check(danmu, title, episode, null)
    }

    fun check(danmu: String?, title: String?, episode: String?, callback: LoadCallback?) {
        loadCallback = callback
        temporarilyClosed = false
        placeholderRetryCount = 0
        danmuText = if (danmu.isNullOrEmpty()) "" else danmu.trim { it <= ' ' }
        danmuTitle = if (title.isNullOrEmpty()) "" else title
        danmuEpisode = if (episode.isNullOrEmpty()) "" else episode
        releaseView()
        val hasDanmu = !TextUtils.isEmpty(danmuText)
        controller?.setHasDanmu(hasDanmu)
        if (!hasDanmu || !DanmuSettingsState.config.open) {
            setViewVisible(false)
            return
        }
        setViewVisible(true)
        if (!isVideoReady()) {
            pendingPrepare = true
            return
        }
        prepare(danmuText)
    }

    fun startIfReady() {
        if (pendingPrepare && !TextUtils.isEmpty(danmuText) && DanmuSettingsState.config.open && isVideoReady()) {
            pendingPrepare = false
            prepare(danmuText)
            return
        }
        startIfReady(loadSeq.get())
    }

    fun reset() {
        DanmakuApi.cancel()
        temporarilyClosed = false
        placeholderRetryCount = 0
        danmuText = ""
        danmuTitle = ""
        danmuEpisode = ""
        pendingPrepare = false
        loadCallback = null
        loadSeq.incrementAndGet()
        startedSeq = -1
        controller?.setHasDanmu(false)
        releaseView()
    }

    fun close() {
        DanmakuApi.cancel()
        loadSeq.incrementAndGet()
        startedSeq = -1
        pendingPrepare = false
        placeholderRetryCount = 0
        releaseView()
    }

    fun toggle(): Boolean {
        if (temporarilyClosed) {
            temporarilyClosed = false
            reloadForPlayback()
            startIfReady()
            return true
        }
        temporarilyClosed = true
        close()
        return false
    }

    fun reloadForPlayback() {
        temporarilyClosed = false
        loadSeq.incrementAndGet()
        startedSeq = -1
        placeholderRetryCount = 0
        releaseView()
        pendingPrepare = !TextUtils.isEmpty(danmuText) && DanmuSettingsState.config.open
    }

    fun setOverlayHidden(hidden: Boolean) {
        if (overlayHidden == hidden) return
        overlayHidden = hidden
        if (hidden) {
            setViewVisible(false)
        } else {
            applyVisibility()
        }
    }

    private fun applyVisibility() {
        if (danmuView == null) return
        setViewVisible(
            DanmuSettingsState.config.open
                && !temporarilyClosed
                && (!TextUtils.isEmpty(danmuText) || pendingPrepare || danmuView.isPrepared)
        )
    }

    private fun setViewVisible(visible: Boolean) {
        val view = danmuView ?: return
        if (!visible) {
            view.animate().cancel()
            view.alpha = 1f
        }
        view.visibility = if (visible && !overlayHidden) View.VISIBLE else View.GONE
    }

    fun destroy() {
        reset()
        executor?.shutdownNow()
        executor = null
    }

    private fun prepare(danmu: String) {
        if (TextUtils.isEmpty(danmu)) return
        pendingPrepare = false
        val seq = loadSeq.incrementAndGet()
        startedSeq = -1
        AppLog.i("echo-danmu load title: " + safeLog(danmuTitle) + ", episode: " + safeLog(danmuEpisode) + ", source: " + sourceSummary(danmu))
        var worker = executor
        if (worker == null || worker.isShutdown) {
            worker = Executors.newSingleThreadExecutor()
            executor = worker
        }
        worker.execute {
            val parser = DanmakuXmlParser(danmu) { seq != loadSeq.get() }
            if (seq != loadSeq.get()) return@execute
            val danmuCount = parser.getDanmuCount()
            AppLog.i("echo-danmu parsed count: $danmuCount")
            val view = danmuView ?: return@execute
            view.post {
                if (seq != loadSeq.get()) return@post
                try {
                    view.release()
                    videoView?.setDanmuView(view)
                    if (danmuCount <= 0) {
                        AppLog.e("echo-danmu empty after parse")
                        setViewVisible(false)
                        notifyLoadFailed(seq)
                        return@post
                    }
                    if (retryPlaceholderLoad(seq, danmu, danmuCount)) return@post
                    placeholderRetryCount = 0
                    view.prepare(parser, danmakuContext)
                    clearLoadCallback(seq)
                    setViewVisible(DanmuSettingsState.config.open)
                    videoView?.startDanmuClock()
                    startIfReady(seq)
                    view.postDelayed({ startIfReady(seq) }, 300)
                    view.postDelayed({ startIfReady(seq) }, 1000)
                } catch (th: Throwable) {
                    AppLog.e("echo-danmu prepare error: " + th.message)
                    setViewVisible(false)
                    notifyLoadFailed(seq)
                }
            }
        }
    }

    private fun retryPlaceholderLoad(seq: Int, danmu: String, count: Int): Boolean {
        if (!danmu.startsWith("http")) return false
        if (count > DANMU_PLACEHOLDER_MAX_COUNT) return false
        if (placeholderRetryCount >= DANMU_PLACEHOLDER_MAX_RETRY) return false
        val view = danmuView ?: return false
        placeholderRetryCount++
        val delayMs = DANMU_PLACEHOLDER_RETRY_BASE_MS * placeholderRetryCount
        AppLog.i("echo-danmu placeholder retry=$placeholderRetryCount delay=$delayMs count=$count")
        view.postDelayed({
            if (seq != loadSeq.get()) return@postDelayed
            prepare(danmu)
        }, delayMs)
        return true
    }

    private fun clearLoadCallback(seq: Int) {
        if (seq == loadSeq.get()) loadCallback = null
    }

    private fun notifyLoadFailed(seq: Int) {
        if (seq != loadSeq.get() || loadCallback == null) return
        val callback = loadCallback
        loadCallback = null
        callback?.onFailed()
    }

    private fun startIfReady(seq: Int) {
        val view = videoView
        val danmu = danmuView
        if (seq != loadSeq.get()
            || seq == startedSeq
            || view == null
            || !view.isPlaying
            || danmu == null
            || !danmu.isPrepared
            || !DanmuSettingsState.config.open
        ) {
            return
        }
        val position = view.currentPosition
        setViewVisible(true)
        danmu.seekTo(position)
        danmu.start(position)
        view.startDanmuClock()
        startedSeq = seq
        AppLog.i("echo-danmu start at: $position")
    }

    private fun isVideoReady(): Boolean {
        val view = videoView ?: return false
        val state = view.playState
        return state == PlayState.PREPARED
            || state == PlayState.BUFFERED
            || state == PlayState.PLAYING
    }

    private fun releaseView() {
        videoView?.stopDanmuClock()
        val view = danmuView ?: return
        try {
            view.release()
        } catch (th: Throwable) {
            AppLog.e("DanmuLoadController", "danmu view release failed", th)
        }
        setViewVisible(false)
    }

    private fun sourceSummary(danmu: String?): String {
        if (danmu.isNullOrEmpty()) return ""
        if (danmu.startsWith("http") || danmu.startsWith("file")) return danmu
        return "inline xml length=" + danmu.length
    }

    private fun safeLog(text: String?): String = if (text.isNullOrEmpty()) "" else text
}
