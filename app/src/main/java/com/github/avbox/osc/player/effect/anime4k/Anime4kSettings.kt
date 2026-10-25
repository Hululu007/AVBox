package com.github.avbox.osc.player.effect.anime4k

import androidx.annotation.StringRes
import com.github.avbox.osc.R
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV

internal const val ANIME4K_ASSET_DIR = "Anime4K/"

internal const val ANIME4K_CLAMP_HIGHLIGHTS = "Anime4K_Clamp_Highlights.glsl"

internal const val ANIME4K_DEBLUR_DOG = "Anime4K_Deblur_DoG.glsl"

private const val RESTORE_CNN_S = "Anime4K_Restore_CNN_S.glsl"
private const val RESTORE_CNN_M = "Anime4K_Restore_CNN_M.glsl"
private const val RESTORE_CNN_L = "Anime4K_Restore_CNN_L.glsl"
private const val UPSCALE_CNN_X2_S = "Anime4K_Upscale_CNN_x2_S.glsl"
private const val UPSCALE_CNN_X2_M = "Anime4K_Upscale_CNN_x2_M.glsl"
private const val UPSCALE_CNN_X2_L = "Anime4K_Upscale_CNN_x2_L.glsl"

enum class Anime4kTier(
    @StringRes val labelRes: Int,
    val restore: String?,
    val upscale: String?,
) {
    Light(R.string.player_anime4k_tier_light, RESTORE_CNN_S, null),
    Standard(R.string.player_anime4k_tier_standard, RESTORE_CNN_S, UPSCALE_CNN_X2_S),
    High(R.string.player_anime4k_tier_high, RESTORE_CNN_M, UPSCALE_CNN_X2_M),
    Ultra(R.string.player_anime4k_tier_ultra, RESTORE_CNN_L, UPSCALE_CNN_X2_L),
    UpscaleOnly(R.string.player_anime4k_tier_upscale, null, UPSCALE_CNN_X2_S),
    ;

    val assets: List<String> get() = listOfNotNull(restore, upscale)

    companion object {

        val default: Anime4kTier = Standard

        fun current(): Anime4kTier = byName(KV.get(KVKeys.ANIME4K_TIER, default.name))

        fun byName(name: String): Anime4kTier = entries.firstOrNull { it.name == name } ?: default
    }
}

object Anime4kSettings {

    const val DEFAULT_SHARPEN = 1.00f

    @Volatile
    private var sharpenCached = -1f

    fun enabled(): Boolean = KV.get(KVKeys.ANIME4K_ENABLED, false)

    fun deblur(): Boolean = KV.get(KVKeys.ANIME4K_DEBLUR, false)

    fun sharpen(): Float {
        if (sharpenCached < 0f) {
            sharpenCached = KV.get(KVKeys.ANIME4K_SHARPEN, DEFAULT_SHARPEN).coerceIn(0f, 1f)
        }
        return sharpenCached
    }

    fun setSharpen(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        sharpenCached = clamped
        KV.put(KVKeys.ANIME4K_SHARPEN, clamped)
    }
}

object Anime4kStatus {

    @Volatile
    private var failed = false

    fun unavailable(): Boolean = failed

    fun onBuildSucceeded() {
        failed = false
    }

    fun onBuildFailed() {
        failed = true
    }

    @Volatile
    var bypassed = false

    fun bypass(): Boolean = bypassed

}
