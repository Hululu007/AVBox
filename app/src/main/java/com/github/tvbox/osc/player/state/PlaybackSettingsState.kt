package com.github.tvbox.osc.player.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

data class PlaybackSettingsConfig(
    val playType: Int,
    val playRender: Int,
    val playScale: Int,
    val exoDecode: String,
    val anime4kTier: String,
    val kernelPrewarm: Boolean,
    val playTunnel: Boolean,
    val preferAac: Boolean,
    val autoSwitchLine: Boolean,
    val m3u8Purify: Boolean,
    val bufferTimes: Int,
    val playCache: Boolean,
    val exoCacheSizeMb: Int,
    val preloadNextEpisode: Boolean,
    val preloadDuration: Int,
    val anime4kEnabled: Boolean,
    val anime4kDeblur: Boolean,
)

object PlaybackSettingsState {

    private var current by mutableStateOf(load())

    val config: PlaybackSettingsConfig get() = current

    private fun load(): PlaybackSettingsConfig = PlaybackSettingsConfig(
        playType = KV.get(HawkConfig.PLAY_TYPE, 2),
        playRender = KV.get(HawkConfig.PLAY_RENDER, 1),
        playScale = KV.get(HawkConfig.PLAY_SCALE, 0),
        exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码"), // i18n: keep
        anime4kTier = Anime4kTier.byName(KV.get(HawkConfig.ANIME4K_TIER, Anime4kTier.default.name)).name,
        kernelPrewarm = KV.get(HawkConfig.KERNEL_PREWARM, HawkConfig.KERNEL_PREWARM_DEFAULT),
        playTunnel = KV.get(HawkConfig.PLAY_TUNNEL, false),
        preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false),
        autoSwitchLine = KV.get(HawkConfig.AUTO_SWITCH_LINE, HawkConfig.AUTO_SWITCH_LINE_DEFAULT),
        m3u8Purify = KV.get(HawkConfig.M3U8_PURIFY, false),
        bufferTimes = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT),
        playCache = KV.get(HawkConfig.PLAY_CACHE, false),
        exoCacheSizeMb = KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT),
        preloadNextEpisode = KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false),
        preloadDuration = KV.get(HawkConfig.PRELOAD_DURATION, HawkConfig.PRELOAD_DURATION_DEFAULT),
        anime4kEnabled = KV.get(HawkConfig.ANIME4K_ENABLED, false),
        anime4kDeblur = KV.get(HawkConfig.ANIME4K_DEBLUR, false),
    )

    fun setPlayType(type: Int) {
        KV.put(HawkConfig.PLAY_TYPE, type)
        current = current.copy(playType = type)
    }

    fun setPlayRender(render: Int) {
        KV.put(HawkConfig.PLAY_RENDER, render)
        current = current.copy(playRender = render)
    }

    fun setPlayScale(scale: Int) {
        KV.put(HawkConfig.PLAY_SCALE, scale)
        current = current.copy(playScale = scale)
    }

    fun setExoDecode(decode: String) {
        KV.put(HawkConfig.EXO_DECODE, decode)
        current = current.copy(exoDecode = decode)
    }

    fun setAnime4kTier(tier: String) {
        KV.put(HawkConfig.ANIME4K_TIER, tier)
        current = current.copy(anime4kTier = tier)
    }

    fun setKernelPrewarm(enabled: Boolean) {
        KV.put(HawkConfig.KERNEL_PREWARM, enabled)
        current = current.copy(kernelPrewarm = enabled)
    }

    fun setPlayTunnel(enabled: Boolean) {
        KV.put(HawkConfig.PLAY_TUNNEL, enabled)
        current = current.copy(playTunnel = enabled)
    }

    fun setPreferAac(enabled: Boolean) {
        KV.put(HawkConfig.PLAY_PREFER_AAC, enabled)
        current = current.copy(preferAac = enabled)
    }

    fun setAutoSwitchLine(enabled: Boolean) {
        KV.put(HawkConfig.AUTO_SWITCH_LINE, enabled)
        current = current.copy(autoSwitchLine = enabled)
    }

    fun setM3u8Purify(enabled: Boolean) {
        KV.put(HawkConfig.M3U8_PURIFY, enabled)
        current = current.copy(m3u8Purify = enabled)
    }

    fun setBufferTimes(times: Int) {
        KV.put(HawkConfig.BUFFER_TIMES, times)
        current = current.copy(bufferTimes = times)
    }

    fun setPlayCache(enabled: Boolean) {
        KV.put(HawkConfig.PLAY_CACHE, enabled)
        current = current.copy(playCache = enabled)
    }

    fun setExoCacheSizeMb(sizeMb: Int) {
        KV.put(HawkConfig.EXO_CACHE_SIZE_MB, sizeMb)
        current = current.copy(exoCacheSizeMb = sizeMb)
    }

    fun setPreloadNextEpisode(enabled: Boolean) {
        KV.put(HawkConfig.PRELOAD_NEXT_EPISODE, enabled)
        current = current.copy(preloadNextEpisode = enabled)
    }

    fun setPreloadDuration(seconds: Int) {
        KV.put(HawkConfig.PRELOAD_DURATION, seconds)
        current = current.copy(preloadDuration = seconds)
    }

    fun setAnime4kEnabled(enabled: Boolean) {
        KV.put(HawkConfig.ANIME4K_ENABLED, enabled)
        current = current.copy(anime4kEnabled = enabled)
    }

    fun setAnime4kDeblur(enabled: Boolean) {
        KV.put(HawkConfig.ANIME4K_DEBLUR, enabled)
        current = current.copy(anime4kDeblur = enabled)
    }
}
