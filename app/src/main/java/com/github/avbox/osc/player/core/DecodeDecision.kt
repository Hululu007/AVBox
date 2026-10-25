package com.github.avbox.osc.player.core

import org.json.JSONObject

object DecodeDecision {

    @JvmStatic
    fun isExoDecodeApplied(playerCfg: JSONObject?): Boolean {
        val exoDecode = if (playerCfg == null) null else playerCfg.optString("exo", "硬解码") // i18n: keep
        return isExoDecodeApplied(exoDecode, CodecPreferences.preferSoftwareDecode)
    }

    @JvmStatic
    fun isExoDecodeApplied(exoDecode: String?, preferSoftwareDecode: Boolean): Boolean {
        return ("软解码" == exoDecode) == preferSoftwareDecode // i18n: keep
    }

    /**
     * 参数面板「解码方式」的选中项：实际生效优先，未初始化时回落到本片档位。0=硬解码 1=软解码。
     */
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
}
