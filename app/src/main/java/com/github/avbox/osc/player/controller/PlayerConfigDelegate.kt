package com.github.avbox.osc.player.controller

import android.os.SystemClock
import com.github.avbox.osc.R
import com.github.avbox.osc.player.core.DecodeDecision
import com.github.avbox.osc.player.core.VideoCodecInfo
import com.github.avbox.osc.player.effect.PictureEffects
import com.github.avbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.avbox.osc.player.effect.anime4k.Anime4kTier
import com.github.avbox.osc.player.state.ParamsChoice
import com.github.avbox.osc.player.state.ParamsSheetState
import com.github.avbox.osc.player.state.PictureParamsState
import com.github.avbox.osc.player.state.PlaybackSettingsState
import com.github.avbox.osc.player.state.SelectDialogState
import com.github.avbox.osc.player.thirdparty.PlayerCatalog
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.PlayerUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONException
import org.json.JSONObject

private const val SPEED_RETRY_MAX = 30
private const val DECODE_PENDING_TIMEOUT_MS = 10000L

internal class PlayerConfigDelegate(private val host: VideoPlayerController) {

    private data class PendingDecode(val value: String, val at: Long, val generation: Int)

    private var speedRetryCount = 0

    private val pendingDecode = MutableStateFlow<PendingDecode?>(null)

    private val speedRetryRunnable by lazy { Runnable { applySpeedWhenReady() } }

    fun cancelSpeedRetry() {
        host.uiHandler.removeCallbacks(speedRetryRunnable)
    }

    fun updatePlayerCfgState() {
        val cfg = host.playerConfig ?: return
        try {
            val playerType = cfg.getInt("pl")
            host.state.updatePlayerType(playerType)
            val start = cfg.getInt("st")
            val end = cfg.getInt("et")
            host.state.updateTimeStartText(if (start == 0) "" else PlayerUtils.stringForTime(start * 1000))
            host.state.updateTimeEndText(if (end == 0) "" else PlayerUtils.stringForTime(end * 1000))
            refreshParamsSheet()
        } catch (e: JSONException) {
            AppLog.e("VideoPlayerController", e)
        }
    }

    private val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

    private fun speedIndex(value: Float): Int {
        val idx = speedOptions.indexOfFirst { it == value }
        return if (idx >= 0) idx else speedOptions.indexOfFirst { it == 1.0f }
    }

    private fun sheetPlayerOrder(types: List<Int>): List<Int> {
        val head = listOf(2)
        return head.filter { types.contains(it) } + types.filter { it !in head }
    }

    fun buildParamsSheet(): ParamsSheetState? {
        val cfg = host.playerConfig ?: return null
        val speed = cfg.optDouble("sp", 1.0).toFloat()
        val playerType = cfg.optInt("pl", 2)
        val players = sheetPlayerOrder(PlayerCatalog.all())
        val scaleType = cfg.optInt("sc", 0)
        return ParamsSheetState(
            speed = ParamsChoice(
                options = speedOptions.map { "${it}x" },
                selected = speedIndex(speed),
                onSelect = { applySpeed(speedOptions[it]) },
            ),
            player = ParamsChoice(
                options = players.map { PlayerCatalog.name(it) },
                selected = players.indexOf(playerType).coerceAtLeast(0),
                onSelect = { applyPlayer(players[it]) },
            ),
            scale = ParamsChoice(
                options = (0..5).map { PlayerTextFormat.scaleName(it) },
                selected = scaleType.coerceIn(0, 5),
                onSelect = { applyScale(it) },
            ),
            picture = PictureParamsState(
                preset = PictureEffects.preset(),
                tuning = PictureEffects.custom,
                unavailableReason = PictureEffects.unavailableReason(),
                anime4kTierText = host.context.getString(Anime4kTier.byName(PlaybackSettingsState.config.anime4kTier).labelRes),
                anime4kEnabled = PlaybackSettingsState.config.anime4kEnabled,
                anime4kUnavailable = PictureEffects.anime4kUnavailable(),
                anime4kSharpen = Anime4kSettings.sharpen(),
                anime4kDeblur = PlaybackSettingsState.config.anime4kDeblur,
                onAnime4kToggled = {
                    PlaybackSettingsState.anime4kEnabled = it
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onAnime4kSharpenChanged = { PictureEffects.setAnime4kSharpen(it) },
                onAnime4kDeblurToggled = {
                    PlaybackSettingsState.anime4kDeblur = it
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onPresetSelected = {
                    PictureEffects.selectPreset(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onTuningChanged = {
                    PictureEffects.custom = it
                    restartForPictureIfNeeded()
                },
                onReset = {
                    PictureEffects.reset()
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onCompareChanged = {
                    PictureEffects.compare(it)
                    restartForPictureIfNeeded()
                },
            ),
            timeStartText = host.state.timeStartText,
            timeEndText = host.state.timeEndText,
            onSetTimeStart = { markTimeStart() },
            onSetTimeEnd = { markTimeEnd() },
            onResetTime = { host.actions.onTimeResetClicked() },
            onSearchDanmu = if (host.state.danmuSearchAvailable) {
                { host.actions.onDanmuSearchClicked() }
            } else {
                null
            },
        )
    }

    internal fun refreshParamsSheet() {
        if (host.state.paramsSheet == null) return
        host.state.openParamsSheet(buildParamsSheet())
    }

    private fun restartForPictureIfNeeded() {
        if (!PictureEffects.consumeRestartNeeded()) return
        host.videoView?.let {
            it.requireKernelRebuild()
            AppLog.i("echo-picture-effects: rebuild kernel on next start")
        }
        host.listener?.replay(false)
    }

    internal fun decodeChoiceFlow(cfg: JSONObject?): Flow<ParamsChoice> =
        combine(pendingDecode, host.videoCodecFlow) { pending, info ->
            buildDecodeChoice(pending, info, configuredExo(cfg))
        }

    internal fun decodeChoiceNow(cfg: JSONObject?): ParamsChoice =
        buildDecodeChoice(pendingDecode.value, host.videoCodecFlow.value, configuredExo(cfg))

    private fun configuredExo(cfg: JSONObject?): String =
        cfg?.optString("exo", "硬解码") ?: "硬解码" // i18n: keep

    private fun buildDecodeChoice(
        pending: PendingDecode?,
        info: VideoCodecInfo,
        configured: String,
    ): ParamsChoice = ParamsChoice(
        options = listOf(
            host.context.getString(R.string.player_decode_hard),
            host.context.getString(R.string.player_decode_soft),
        ),
        selected = pendingDecodeIndex(pending, info)
            ?: DecodeDecision.decodeDisplayIndex(info.kind, configured),
        onSelect = { applyDecode(if (it == 1) "软解码" else "硬解码") }, // i18n: keep
    )

    private fun pendingDecodeIndex(pending: PendingDecode?, info: VideoCodecInfo): Int? =
        DecodeDecision.pendingDecodeIndex(
            pending?.value,
            pending?.at ?: 0L,
            pending?.generation ?: 0,
            SystemClock.elapsedRealtime(),
            info.generation,
            DECODE_PENDING_TIMEOUT_MS,
        )

    private fun decodeDisplayedIndex(cfg: JSONObject): Int =
        pendingDecodeIndex(pendingDecode.value, host.videoCodecFlow.value)
            ?: DecodeDecision.decodeDisplayIndex(host.videoCodecFlow.value.kind, configuredExo(cfg))

    fun applySpeed(value: Float) {
        host.actions.keepControlsAlive()
        host.listener?.updatePlayerCfg { it.put("sp", value.toDouble()) }
        host.state.updateSpeedOld(value)
        host.videoView?.setSpeed(value)
    }

    fun applyScale(index: Int) {
        host.actions.keepControlsAlive()
        host.listener?.updatePlayerCfg { it.put("sc", index) }
        host.videoView?.setScreenScaleType(index)
    }

    fun applyPlayer(playerType: Int) {
        host.actions.keepControlsAlive()
        val cfg = host.playerConfig ?: return
        if (playerType == cfg.optInt("pl", 2)) return
        host.listener?.updatePlayerCfg(suppressAutoSwitch = true) { it.put("pl", playerType) }
        host.listener?.replay(false)
    }

    fun applyDecode(value: String) {
        host.actions.keepControlsAlive()
        val cfg = host.playerConfig ?: return
        if (decodeDisplayedIndex(cfg) == (if (value == "软解码") 1 else 0)) return // i18n: keep
        pendingDecode.value = PendingDecode(
            value,
            SystemClock.elapsedRealtime(),
            host.videoCodecFlow.value.generation,
        )
        host.listener?.updatePlayerCfg(suppressDecodeFallback = true) {
            it.put("exo", value) // i18n: keep
            it.put("exoSet", 1)
        }
        host.listener?.replay(false)
    }

    fun applySpeedWhenReady() {
        if (host.isInPlaybackState()) {
            speedRetryCount = 0
            try {
                host.playerConfig?.let { host.videoView?.setSpeed(it.getDouble("sp").toFloat()) }
            } catch (e: JSONException) {
                AppLog.e("VideoPlayerController", e)
            }
        } else if (speedRetryCount < SPEED_RETRY_MAX) {
            speedRetryCount++
            host.uiHandler.removeCallbacks(speedRetryRunnable)
            host.uiHandler.postDelayed(speedRetryRunnable, 100)
        }
    }

    fun markTimeStart() {
        val snapshot = host.progressSnapshot() ?: return
        val current = snapshot.positionMs
        if (current > snapshot.durationMs / 2) return
        setTimeMark("st", current / 1000)
    }

    fun markTimeEnd() {
        val snapshot = host.progressSnapshot() ?: return
        val current = snapshot.positionMs
        val duration = snapshot.durationMs
        if (current < duration / 2) return
        setTimeMark("et", (duration - current) / 1000)
    }

    fun setTimeMark(key: String, seconds: Int) {
        host.actions.keepControlsAlive()
        host.listener?.updatePlayerCfg { it.put(key, seconds) }
    }

    fun showScaleDialog() {
        try {
            val cfg = host.playerConfig ?: return
            val scaleType = cfg.getInt("sc")
            val scales = ArrayList<String>()
            for (i in 0..5) {
                scales.add(PlayerTextFormat.scaleName(i))
            }
            host.state.openSelectDialog(SelectDialogState(
                tip = host.context.getString(R.string.player_select_scale),
                items = scales,
                defaultIndex = scaleType.coerceIn(0, 5),
                onSelected = { index -> applyScale(index) },
            ))
        } catch (e: JSONException) {
            AppLog.e("VideoPlayerController", e)
        }
    }

    fun showSpeedDialog() {
        try {
            val cfg = host.playerConfig ?: return
            val speed = cfg.getDouble("sp").toFloat()
            val speeds = speedOptions.map { "${it}x" }
            host.state.openSelectDialog(SelectDialogState(
                tip = host.context.getString(R.string.player_select_speed),
                items = speeds,
                defaultIndex = speedIndex(speed),
                onSelected = { index -> applySpeed(speedOptions[index]) },
            ))
        } catch (e: JSONException) {
            AppLog.e("VideoPlayerController", e)
        }
    }
}
