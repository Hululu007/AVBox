package com.github.tvbox.osc.ui.player

import android.text.TextUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.player.controller.VodControlListener
import com.github.tvbox.osc.player.state.DanmuSettingSheetState
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject
import java.util.HashMap

class PlayContainerControlListener(private val container: PlayContainer) : VodControlListener {

    override fun showDanmuSetting() {
        if (!container.isAttached()) return
        container.mController.getUiState().openDanmuSettingSheet(
            DanmuSettingSheetState(
                onOpenSearch = { container.openDanmuSearchSheet() },
                onReset = {
                    DanmuHelper.reset()
                    container.applyDanmuSettings(false)
                },
                onSettingsApplied = { forColor -> container.applyDanmuSettings(forColor) },
            ),
        )
    }

    override fun toggleDanmu(): Boolean {
        val danmu = container.danmuLoadController ?: return false
        return danmu.toggle()
    }

    override fun showEpisodes() {
        container.mPageHost?.showEpisodeSheet()
    }

    override fun searchDanmuUi(longClick: Boolean) {
        val vod = container.scheduler.vod()
        val series = if (vod == null) {
            null
        } else {
            container.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        }
        ApiConfig.get().searchDanmuUi(
            vod?.name ?: "",
            series?.name ?: "",
            longClick,
        )
    }

    override fun playNext(rmProgress: Boolean) {
        val preProgressKey = container.scheduler.progressKey()
        val preOwner = container.scheduler.progressOwner()
        container.playNext(rmProgress)
        if (rmProgress && preProgressKey != null) {
            WatchProgressStore.clear(preOwner ?: "", preProgressKey, discard = true)
        }
    }

    override fun playPre() {
        container.playPrevious()
    }

    override fun changeParse(pb: ParseBean) {
        container.scheduler.requestParse(pb)
    }

    override fun updatePlayerCfg(
        suppressAutoSwitch: Boolean,
        suppressDecodeFallback: Boolean,
        mutate: (JSONObject) -> Unit,
    ) {
        container.scheduler.updatePlayerCfg(mutate, suppressAutoSwitch, suppressDecodeFallback)
    }

    override fun replay(replay: Boolean) {
        container.reviveEngineIfReleased()
        container.scheduler.prepareUserReplay()
        if (replay) {
            container.playViaScheduler(true)
        } else {
            container.replayCurrentAddress()
        }
    }

    override fun onNearEndProgress(positionMs: Long, durationMs: Long) {
        container.scheduler.onNearEndProgress(positionMs, durationMs)
    }

    override fun closeSubtitles() {
        container.closeSubtitles()
    }

    override fun selectSubtitle() {
        try {
            container.selectMySubtitle()
        } catch (e: Exception) {
            LOG.e("PlayContainer", e)
        }
    }

    override fun selectAudioTrack() {
        container.selectMyAudioTrack()
    }

    override fun selectVideoTrack() {
        container.selectMyVideoTrack()
    }

    override fun prepared() {
        container.initSubtitleView()
        container.mVideoView?.prepared()
        container.startDanmuIfReady()
    }

    override fun startPlayUrl(url: String, headers: HashMap<String, String>?) {
        if (!TextUtils.isEmpty(container.scheduler.m3u8SourceUrl()) &&
            !container.scheduler.isM3u8ProxyUrl(url)
        ) {
            container.scheduler.clearM3u8ProxyUrl()
        }
        container.scheduler.goPlayUrl(url, headers)
    }

    override fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String) {
        container.scheduler.onM3u8ProxyResolved(proxyUrl, sourceUrl)
    }

    override fun clickCast() {
        container.showCastDialog()
    }
}
