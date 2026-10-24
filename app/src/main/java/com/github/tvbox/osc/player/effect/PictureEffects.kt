package com.github.tvbox.osc.player.effect

import androidx.media3.common.Effect
import com.github.tvbox.osc.player.core.RedrawPolicy
import com.github.tvbox.osc.player.effect.anime4k.Anime4kEffect
import com.github.tvbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.tvbox.osc.player.effect.anime4k.Anime4kStatus
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.lang.ref.WeakReference

enum class PictureEffectUnavailableReason {
    None, Tunneling, Hdr, DecoderUnsupported;

    companion object {
        fun of(
            tunneling: Boolean,
            hdr: Boolean,
            pipeOpen: Boolean,
            effectsActive: Boolean,
            hasLook: Boolean,
        ): PictureEffectUnavailableReason = when {
            tunneling -> Tunneling
            hdr && hasLook -> Hdr
            pipeOpen && !effectsActive && hasLook -> DecoderUnsupported
            else -> None
        }
    }
}

object PictureEffects {

    private val colorTone = ColorToneAdjustEffect()
    private val detail = DetailAdjustEffect()
    private val anime4k = Anime4kEffect()

    private val listWithoutAnime4k: List<Effect> = listOf(colorTone, detail)
    private val listWithAnime4k: List<Effect> = listOf(colorTone, detail, anime4k)

    internal fun effectsFor(anime4kEnabled: Boolean): List<Effect> =
        if (anime4kEnabled) listWithAnime4k else listWithoutAnime4k

    private var current: WeakReference<PictureEffectHost>? = null

    private var tunneling = false

    private var openedThisSession = false

    private var comparing = false

    private var anime4kOpened = false
    private var anime4kTierOpened: Anime4kTier = Anime4kTier.default
    private var anime4kDeblurOpened = false

    fun preset(): PicturePreset {
        val name = KV.get(HawkConfig.PICTURE_PRESET, PicturePreset.Original.name)
        return PicturePreset.entries.firstOrNull { it.name == name } ?: PicturePreset.Original
    }

    fun custom(): PictureProfile = PictureProfile(
        saturation = KV.get(HawkConfig.PICTURE_SATURATION, 1f),
        contrast = KV.get(HawkConfig.PICTURE_CONTRAST, 1f),
        brightness = KV.get(HawkConfig.PICTURE_BRIGHTNESS, 0f),
        gamma = KV.get(HawkConfig.PICTURE_GAMMA, 1f),
        hue = KV.get(HawkConfig.PICTURE_HUE, 0f),
        temperature = KV.get(HawkConfig.PICTURE_TEMPERATURE, 0f),
        sharpness = KV.get(HawkConfig.PICTURE_SHARPNESS, 0f),
        shadowLift = KV.get(HawkConfig.PICTURE_SHADOW_LIFT, 0f),
    ).clamped()

    fun applied(): PictureProfile {
        if (comparing) return PictureProfile.OFF
        val preset = preset()
        return if (preset.adjustable) custom() else PictureProfile.of(preset)
    }

    private fun wanted(): Boolean = !applied().isNoOp || Anime4kSettings.enabled()

    fun selectPreset(preset: PicturePreset) {
        KV.put(HawkConfig.PICTURE_PRESET, preset.name)
        push()
    }

    fun setCustom(profile: PictureProfile) {
        KV.put(HawkConfig.PICTURE_SATURATION, profile.saturation)
        KV.put(HawkConfig.PICTURE_CONTRAST, profile.contrast)
        KV.put(HawkConfig.PICTURE_BRIGHTNESS, profile.brightness)
        KV.put(HawkConfig.PICTURE_GAMMA, profile.gamma)
        KV.put(HawkConfig.PICTURE_HUE, profile.hue)
        KV.put(HawkConfig.PICTURE_TEMPERATURE, profile.temperature)
        KV.put(HawkConfig.PICTURE_SHARPNESS, profile.sharpness)
        KV.put(HawkConfig.PICTURE_SHADOW_LIFT, profile.shadowLift)
        push()
    }

    fun reset() {
        KV.put(HawkConfig.PICTURE_PRESET, PicturePreset.Original.name)
        setCustom(PictureProfile.OFF)
    }

    fun compare(original: Boolean) {
        if (comparing == original) return
        comparing = original
        Anime4kStatus.setBypass(original)
        push()
    }

    fun onPrepare(player: PictureEffectHost, tunnelingBlocked: Boolean) {
        comparing = false
        Anime4kStatus.setBypass(false)
        if (KV.get(HawkConfig.PLAYER_IS_LIVE, false)) {
            current = null
            LOG.i("echo-picture-effects skip: live")
            return
        }
        current = WeakReference(player)
        tunneling = tunnelingBlocked
        if (tunnelingBlocked) {
            LOG.i("echo-picture-effects skip: tunneling enabled")
            return
        }
        val profile = applied()
        val tier = Anime4kTier.current()
        val anime4kWanted = Anime4kSettings.enabled()
        anime4k.tier = if (anime4kWanted) tier else null
        anime4kOpened = anime4kWanted
        anime4kTierOpened = tier
        anime4kDeblurOpened = Anime4kSettings.deblur()
        colorTone.setProfile(profile)
        detail.setProfile(profile)
        val enabled = !profile.isNoOp || anime4kWanted
        openedThisSession = enabled
        if (enabled) player.applyVideoEffects(effectsFor(anime4kWanted))
    }

    fun onPlayerReleased(player: PictureEffectHost) {
        if (current?.get() === player) current = null
    }

    fun anime4kUnavailable(): Boolean {
        val player = current?.get() ?: return false
        return Anime4kSettings.enabled() && !tunneling && !player.isPictureHdrSource() &&
            Anime4kStatus.unavailable()
    }

    fun setAnime4kSharpen(value: Float) {
        Anime4kSettings.setSharpen(value)
        val player = current?.get() ?: return
        if (player.isPictureHdrSource()) return
        if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive())) {
            player.redrawVideoFrame()
        }
    }

    fun unavailableReason(): PictureEffectUnavailableReason {
        val player = current?.get() ?: return PictureEffectUnavailableReason.None
        return PictureEffectUnavailableReason.of(
            tunneling = tunneling,
            hdr = player.isPictureHdrSource(),
            pipeOpen = openedThisSession,
            effectsActive = player.isPictureEffectsActive(),
            hasLook = wanted(),
        )
    }

    fun consumeRestartNeeded(): Boolean {
        val player = current?.get() ?: return false
        if (tunneling || player.isPictureHdrSource()) return false
        val anime4kWanted = Anime4kSettings.enabled()
        if (anime4kWanted != anime4kOpened) return true
        if (anime4kWanted && Anime4kTier.current() != anime4kTierOpened) return true
        if (anime4kWanted && Anime4kSettings.deblur() != anime4kDeblurOpened) return true
        return restartNeeded(wanted(), openedThisSession, preset() == PicturePreset.Original)
    }

    internal fun restartNeeded(wantEffects: Boolean, opened: Boolean, presetOriginal: Boolean): Boolean =
        if (wantEffects) !opened else (opened && presetOriginal)

    private fun push() {
        val player = current?.get() ?: return
        val profile = applied()
        colorTone.setProfile(profile)
        detail.setProfile(profile)
        if (tunneling) return
        if (player.isPictureHdrSource()) return
        if (openedThisSession) {
            if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive())) {
                player.redrawVideoFrame()
            }
            return
        }
        if (!wanted()) return
        player.applyVideoEffects(effectsFor(anime4kOpened))
    }
}
