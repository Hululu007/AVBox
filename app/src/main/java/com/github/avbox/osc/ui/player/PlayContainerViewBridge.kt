package com.github.avbox.osc.ui.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.player.KernelConfigApplier
import com.github.avbox.osc.player.core.KernelPlayer
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.PreloadCoordinator
import com.github.avbox.osc.player.PlaybackHostApi
import com.github.avbox.osc.player.PlaybackViewBridge
import com.github.avbox.osc.player.core.VideoCodecInfo
import com.github.avbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.thirdparty.ExternalPlayerLauncher
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.PermissionHelper
import org.json.JSONObject
import java.util.HashMap

class PlayContainerViewBridge(private val container: PlayContainer) : PlaybackViewBridge {

    override fun isPageAlive(): Boolean = container.isAttached()

    override fun runOnUi(action: Runnable) {
        val activity = container.attachedActivity
        if (container.isAttached() && activity != null) activity.runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(container.context, text, Toast.LENGTH_SHORT).show()
    }

    override fun showTip(msg: String, loading: Boolean, error: Boolean) {
        container.setTip(msg, loading, error)
    }

    override fun hideTipOnUiThread() {
        container.hideTipOnUiThread()
    }

    override fun playState(): PlayState = container.kernelView?.playState ?: PlayState.IDLE

    override fun currentPosition(): Long = container.kernelView?.currentPosition ?: 0L

    override fun isPlaying(): Boolean = container.kernelView?.isPlaying == true

    override fun duration(): Long = container.kernelView?.duration ?: 0L

    override fun mediaPlayer(): KernelPlayer? = container.kernelView?.mediaPlayer

    override fun isKernelErrored(): Boolean = container.kernelView?.isKernelErrored() == true

    override fun currentUrl(): String? = container.kernelView?.currentUrl

    override fun onSessionVodUpdated(vod: VodInfo) {
        container.syncSessionVod()
    }

    override fun context(): Context = container.context

    override fun playbackHost(): PlaybackHostApi = container

    override fun requestNotificationPermission() {
        val host = container.pageHostRef
        val activity = container.attachedActivity
        if (host != null) {
            host.requestNotificationPermission()
        } else if (activity != null) {
            PermissionHelper.requestNotificationIfNeeded(activity)
        }
    }

    override fun switchRenderToTexture() {
        val view = container.kernelView
        if (view != null && view.renderIsSurface) view.switchRenderToTexture()
    }

    override fun ensureRenderViewMatchesConfig() {
        container.kernelView?.ensureRenderViewMatchesConfig()
    }

    override fun releasePlayer() {
        container.releasePlayerKernel()
    }

    override fun setTitle(title: String) {
        container.playerController?.setTitle(title)
    }

    override fun stopOtherPlayers() {
        container.playerController?.stopOther()
    }

    override fun resetDanmu() {
        container.resetDanmuState()
    }

    override fun startDanmuIfReady() {
        container.startDanmuIfReady()
    }

    override fun clearLyric() {
        container.clearLyricView()
    }

    override fun clearArtwork() {
        container.kernelView?.clearArtwork()
    }

    override fun clearVideoFrame() {
        container.kernelView?.clearVideoFrame()
    }

    override fun setSubtitleViewVisible(visible: Boolean) {
        val controller = container.playerController ?: return
        controller.getSubtitleView().visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onNewPlayStarted(sameContent: Boolean) {
        container.previewExiting = false
        container.playerController?.onNewPlayStarted(sameContent)
    }

    override fun applyPlayerConfigToView() {
        val view = container.kernelView ?: return
        KernelConfigApplier.applyConfig(view, requireNotNull(container.scheduler.playerCfg()) { "player cfg missing in bridge" })
    }

    override fun useTextureRenderForAudio() {
        container.kernelView?.setRenderViewFactory(EngineTextureRenderViewFactory.create())
    }

    override fun playExternalPlayer(
        playerType: Int,
        url: String,
        title: String,
        subtitle: String?,
        headers: HashMap<String, String>?,
        progress: Long,
    ): Boolean {
        val activity = container.attachedActivity ?: return false
        return ExternalPlayerLauncher.launch(
            playerType,
            activity,
            url,
            title,
            subtitle ?: "",
            headers,
            progress,
        )
    }

    override fun playM3u8(url: String, headers: HashMap<String, String>) {
        container.playerController?.playM3u8(url, headers)
    }

    override fun playM3u8(url: String, headers: HashMap<String, String>?, gen: Int) {
        if (!container.scheduler.isParseResultCurrent(gen)) {
            AppLog.i("echo-ignore stale m3u8 result")
            return
        }
        container.playerController?.playM3u8(url, headers)
    }

    override fun startVideoPlayback(
        url: String,
        headers: HashMap<String, String>?,
        forceExoPlayer: Boolean,
    ) {
        val view = container.kernelView ?: return
        container.playerController?.hidePauseRoot()
        val reusePlayer = container.scheduler.startVideoPlayback(
            view,
            url,
            headers,
            forceExoPlayer,
            releaseKernel = { container.releasePlayerKernel() },
            trackMemoryKey = container.trackMemoryKey(),
            renderRebuildGuard = { true },
            startLineTimeoutIso = MediaSources.isoHeaders(headers),
        )
        if (!reusePlayer) container.hideTip()
        container.playerController?.adoptCurrentContent()
        container.playerController?.resetSpeed()
    }

    override fun buildPreloadSnapshot(): PreloadCoordinator.Snapshot? = container.buildPreloadSnapshot()

    override fun showPreloadReadyTip() {
        container.showPreloadReady()
    }

    override fun hidePreloadReadyTip() {
        container.hidePreloadReady()
    }

    override fun firstUrlByArray(url: String): String =
        container.playerController?.firstUrlByArray(url) ?: url

    override fun setArtwork(url: String) {
        container.kernelView?.setArtwork(url)
    }

    override fun showParse(show: Boolean) {
        container.playerController?.showParse(show)
    }

    override fun checkDanmu(danmaku: String, onFailed: Runnable?) {
        container.checkDanmu(danmaku) { onFailed?.run() }
    }

    override fun encodeUrl(url: String): String =
        container.playerController?.encodeUrl(url) ?: url

    override fun evaluateScript(url: String, webView: WebView?) {
        container.playerController?.evaluateScript(container.scheduler.sourceBean(), url, webView)
    }

    override fun newSniffWebView(): WebView = container.MyWebView(container.context)

    override fun attachSniffWebView(webView: WebView) {
        val activity = container.attachedActivity
        if (container.isAttached() && activity != null) {
            activity.addContentView(webView, ViewGroup.LayoutParams(1, 1))
        }
    }

    override fun showErrorWithRetry(err: String, finish: Boolean) {
        container.errorWithRetry(err, finish)
    }

    override fun switchPlayerKernel(): Boolean =
        container.playerController != null && container.playerController.switchPlayer()

    override fun applyPlayerConfig(cfg: JSONObject) {
        container.playerController?.setPlayerConfig(cfg)
    }

    override fun videoCodecInfo(): VideoCodecInfo =
        container.engine?.videoCodec?.value ?: VideoCodecInfo()

    override fun onLinesExhausted(): Boolean {
        val host = container.pageHostRef ?: return false
        return host.onPlaybackLinesExhausted()
    }
}
