package com.github.tvbox.osc.player

import android.app.Activity
import android.content.Context

import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.core.DecodeKindRule
import com.github.tvbox.osc.player.core.PlayerDecodeKind
import com.github.tvbox.osc.player.engine.SourcePolicy
import com.github.tvbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.tvbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.tvbox.osc.player.host.PlayerRenderViewFactory
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager

import org.json.JSONObject

import java.text.DecimalFormat

object PlayerHelper {
    @JvmStatic
    fun updateCfg(videoView: MyVideoView?, playerCfg: JSONObject) {
        updateCfg(videoView, playerCfg, -1)
    }

    @JvmStatic
    fun updateCfg(videoView: MyVideoView?, playerCfg: JSONObject, forcePlayerType: Int) {
        var renderType = KV.get(HawkConfig.PLAY_RENDER, 1)
        var exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码") // i18n: keep
        var scale = KV.get(HawkConfig.PLAY_SCALE, 0)
        renderType = playerCfg.optInt("pr", renderType)
        scale = playerCfg.optInt("sc", scale)
        exoDecode = playerCfg.optString("exo", exoDecode)
        val exoDecodeChanged = applyExoDecode(exoDecode)
        val renderViewFactory: PlayerRenderViewFactory = when (renderType) {
            1 -> EngineSurfaceRenderViewFactory.create()
            else -> EngineTextureRenderViewFactory.create()
        }
        if (videoView != null) {
            if (exoDecodeChanged && videoView.mediaPlayer is ExoPlayer) {
                videoView.requireKernelRebuild()
                LOG.i("echo-exo-decode-changed: rebuild kernel on next start")
            }
            videoView.setRenderViewFactory(renderViewFactory)
            videoView.setScreenScaleType(scale)
        }
    }

    private fun applyExoDecode(exoDecode: String): Boolean {
        val prefer = "软解码" == exoDecode // i18n: keep
        if (ExoPlayer.isPreferSoftwareDecode() == prefer) return false
        ExoPlayer.setPreferSoftwareDecode(prefer)
        return true
    }

    @JvmStatic
    fun isExoDecodeApplied(playerCfg: JSONObject?): Boolean {
        val exoDecode = if (playerCfg == null) null else playerCfg.optString("exo", "硬解码") // i18n: keep
        return isExoDecodeApplied(exoDecode, ExoPlayer.isPreferSoftwareDecode())
    }

    @JvmStatic
    fun isExoDecodeApplied(exoDecode: String?, preferSoftwareDecode: Boolean): Boolean {
        return ("软解码" == exoDecode) == preferSoftwareDecode // i18n: keep
    }

    @JvmStatic
    fun isLocalProxyUrl(url: String?): Boolean = SourcePolicy.isLocalProxyUrl(url)

    @JvmStatic
    fun decodeKindOf(
        codecName: String?,
        hardwareAccelerated: Boolean,
        softwareOnly: Boolean,
    ): PlayerDecodeKind = DecodeKindRule.kindOf(codecName, hardwareAccelerated, softwareOnly)

    @JvmStatic
    fun videoDecodeKindOf(codecName: String?, softwareOnly: Boolean): PlayerDecodeKind =
        DecodeKindRule.videoKindOf(codecName, softwareOnly)

    /** 参数面板「解码方式」的选中项：实际生效优先，未初始化时回落到本片档位。0=硬解码 1=软解码。 */
    @JvmStatic
    fun decodeDisplayIndex(actualKind: PlayerDecodeKind, configuredExo: String?): Int = when (actualKind) {
        PlayerDecodeKind.SOFTWARE -> 1
        PlayerDecodeKind.HARDWARE -> 0
        PlayerDecodeKind.UNKNOWN -> if ("软解码" == configuredExo) 1 else 0 // i18n: keep
    }

    @JvmStatic
    fun pendingDecodeIndex(
        pending: String?,
        pendingAt: Long,
        pendingGeneration: Int,
        now: Long,
        currentGeneration: Int,
        timeoutMs: Long,
    ): Int? {
        if (pending == null) return null
        if (now - pendingAt > timeoutMs) return null
        if (pendingGeneration != currentGeneration) return null
        return if ("软解码" == pending) 1 else 0 // i18n: keep
    }

    /**
     * 自动软解回退是否会落到当前已经使用的 codec 上（设备没有硬解时常见）。
     * 只在实际已跑在软解器上时才可能"切了没变化"：当前是硬解器一律允许回退；
     * media3 会按 format 支持再重排软解候选，所以不拿排序前的首候选去否决硬解回退。
     */
    @JvmStatic
    fun shouldSkipSoftDecodeFallback(
        softCandidateName: String?,
        currentCodecName: String?,
        currentKind: PlayerDecodeKind,
    ): Boolean {
        if (currentKind != PlayerDecodeKind.SOFTWARE) return false
        if (currentCodecName.isNullOrEmpty()) return false
        if (!softCandidateName.isNullOrEmpty()) return softCandidateName == currentCodecName
        return true
    }

    @JvmStatic
    fun shouldSkipHardDecodeFallback(
        hardCandidateName: String?,
        currentCodecName: String?,
        currentKind: PlayerDecodeKind,
    ): Boolean {
        if (currentKind == PlayerDecodeKind.HARDWARE) return true
        if (currentCodecName.isNullOrEmpty()) return false
        if (hardCandidateName.isNullOrEmpty()) return false
        return hardCandidateName == currentCodecName
    }

    @JvmStatic
    fun getPlayerName(playType: Int): String {
        return when (playType) {
            13 -> str(R.string.player_nearby_tvbox)
            else -> str(R.string.player_exo)
        }
    }

    private var mPlayersExistInfo: HashMap<Int, Boolean>? = null

    @JvmStatic
    fun invalidatePlayersExistInfo() {
        mPlayersExistInfo = null
    }

    @JvmStatic
    fun getPlayersExistInfo(): HashMap<Int, Boolean> {
        val cached = mPlayersExistInfo
        if (cached != null) return cached
        val playersExist = HashMap<Int, Boolean>()
        playersExist[2] = true
        playersExist[13] = RemoteTVBox.getAvalible() != null
        mPlayersExistInfo = playersExist
        return playersExist
    }

    @JvmStatic
    fun getPlayerExist(playType: Int): Boolean {
        val playersExistInfo = getPlayersExistInfo()
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.getValue(playType)
        } else {
            return false
        }
    }

    @JvmStatic
    fun getExistPlayerTypes(): ArrayList<Int> {
        val playersExistInfo = getPlayersExistInfo()
        val existPlayers = ArrayList<Int>()
        for (playerType in playersExistInfo.keys) {
            if (playersExistInfo.getValue(playerType)) {
                existPlayers.add(playerType)
            }
        }
        return existPlayers
    }

    @JvmStatic
    fun runExternalPlayer(playerType: Int, activity: Activity, url: String, title: String, subtitle: String, headers: HashMap<String, String>?): Boolean {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers, 0L)
    }

    @JvmStatic
    fun runExternalPlayer(playerType: Int, activity: Activity, url: String, title: String, subtitle: String, headers: HashMap<String, String>?, progress: Long): Boolean {
        var callResult = false
        when (playerType) {
            13 -> {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers)
            }
        }
        return callResult
    }

    @JvmStatic
    fun getRenderName(renderType: Int): String {
        return if (renderType == 1) {
            "SurfaceView"
        } else {
            "TextureView"
        }
    }

    @JvmStatic
    fun getScaleName(screenScaleType: Int): String {
        return when (screenScaleType) {
            AppPlayerView.SCREEN_SCALE_16_9 -> "16:9"
            AppPlayerView.SCREEN_SCALE_4_3 -> "4:3"
            AppPlayerView.SCREEN_SCALE_MATCH_PARENT -> str(R.string.player_scale_fill)
            AppPlayerView.SCREEN_SCALE_ORIGINAL -> str(R.string.player_scale_origin)
            AppPlayerView.SCREEN_SCALE_CENTER_CROP -> str(R.string.player_scale_crop)
            else -> str(R.string.common_default)
        }
    }

    private fun str(resId: Int): String {
        val app: Context? = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId)
    }

    @JvmStatic
    fun getDisplaySpeed(speed: Long, show: Boolean): String {
        return if (speed > 1048576)
            DecimalFormat("#.00").format(speed / 1048576.0) + "MB/s"
        else if (speed > 1024)
            (speed / 1024).toString() + "KB/s"
        else
            if (speed > 0) speed.toString() + "B/s" else (if (show) "0B/s" else "")
    }
}
