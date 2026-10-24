package com.github.tvbox.osc.player

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject

class PlaybackConfigDelegate(private val host: Host) {

    interface Host {
        fun vod(): VodInfo?

        fun sourceBean(): SourceBean?

        fun playerCfg(): JSONObject?

        fun setPlayerCfg(cfg: JSONObject)

        fun commitPlayerCfg(mutate: (JSONObject) -> Unit, persist: Boolean): Boolean

        fun attemptState(): PlaybackAttemptState

        fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState)
    }

    fun initPlayerCfg() {
        val cfg = try {
            JSONObject(requireNotNull(host.vod()).playerCfg)
        } catch (th: Throwable) {
            JSONObject()
        }
        try {
            if (!cfg.has("pl")) {
                val source = host.sourceBean()
                val sourcePlayerType = if (source == null) -1 else source.playerType
                cfg.put("pl", if (sourcePlayerType == -1) (KV.get(HawkConfig.PLAY_TYPE, 2) as Int) else sourcePlayerType)
            }
            val configuredType = cfg.optInt("pl", 2)
            if (configuredType == 0 || configuredType == 1 || !PlayerHelper.getPlayerExist(configuredType)) {
                cfg.put("pl", 2)
            }
            cfg.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1))
            if (cfg.optInt("exoSet", 0) == 0) {
                cfg.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")) // i18n: keep
            }
            if (!cfg.has("sc")) {
                cfg.put("sc", KV.get(HawkConfig.PLAY_SCALE, 0))
            }
            if (!cfg.has("sp")) {
                cfg.put("sp", 1.0f)
            }
            if (!cfg.has("st")) {
                cfg.put("st", 0)
            }
            if (!cfg.has("et")) {
                cfg.put("et", 0)
            }
        } catch (th: Throwable) {
            LOG.d("PlaybackController", "initPlayerCfg fill-up failed, keep parsed part")
        }
        host.setPlayerCfg(cfg)
    }

    fun setAllowSwitchPlayer(allow: Boolean) {
        host.updateAttemptState {
            if (allow) it.copy(allowSwitchPlayer = true) else it.copy(allowSwitchPlayer = false, autoSwitchedPlayerType = -1)
        }
    }

    fun setAllowDecodeFallback(allow: Boolean) {
        if (allow) return
        host.updateAttemptState { it.copy(hasAutoSwitchedDecode = true, autoSwitchedDecodeOld = null) }
    }

    fun playerCfgForPersist(): JSONObject? {
        val cfg = host.playerCfg() ?: return null
        return try {
            val copy = JSONObject(cfg.toString())
            val st = host.attemptState()
            if (st.autoSwitchedPlayerType >= 0) {
                copy.put("pl", st.autoSwitchedPlayerType)
            }
            if (st.autoSwitchedDecodeOld != null) {
                copy.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld)
            }
            copy
        } catch (th: Throwable) {
            cfg
        }
    }

    fun syncDecodeFromGlobal() {
        val cfg = host.playerCfg() ?: return
        val st = host.attemptState()
        val autoExo = st.autoSwitchedDecodeOld != null && "exo" == st.autoSwitchedDecodeKey
        if (cfg.optInt("exoSet", 0) != 0 || autoExo) return
        val global = KV.get(HawkConfig.EXO_DECODE, "硬解码") // i18n: keep
        if (cfg.optString("exo", "") == global) return
        host.commitPlayerCfg({ it.put("exo", global) }, false)
    }
}
