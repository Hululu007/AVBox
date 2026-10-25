package com.github.avbox.osc.player.effect

enum class PicturePreset {
    Original, Natural, Vivid, Clear, Bright, Cinema, Soft, Warm, Cool, Comfort, Anime, Sport, Game, Custom;

    val adjustable: Boolean get() = this == Custom
}

data class PictureProfile(
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val hue: Float = 0f,
    val temperature: Float = 0f,
    val sharpness: Float = 0f,
    val shadowLift: Float = 0f,
    val threshold: Float = DEFAULT_THRESHOLD,
) {

    val redGain: Float
        get() = if (temperature >= 0f) 1f + temperature * WARM_GAIN else 1f + temperature * COOL_GAIN

    val blueGain: Float
        get() = if (temperature >= 0f) 1f - temperature * COOL_GAIN else 1f - temperature * WARM_GAIN

    val isColorNoOp: Boolean
        get() = saturation == 1f && contrast == 1f && brightness == 0f && redGain == 1f && blueGain == 1f

    val isToneNoOp: Boolean get() = gamma == 1f && hue == 0f

    val isDetailNoOp: Boolean get() = sharpness == 0f && shadowLift == 0f

    val isNoOp: Boolean get() = isColorNoOp && isToneNoOp && isDetailNoOp

    fun clamped(): PictureProfile = copy(
        saturation = saturation.sane(MIN_SATURATION, MAX_SATURATION, 1f),
        contrast = contrast.sane(MIN_CONTRAST, MAX_CONTRAST, 1f),
        brightness = brightness.sane(MIN_BRIGHTNESS, MAX_BRIGHTNESS, 0f),
        gamma = gamma.sane(MIN_GAMMA, MAX_GAMMA, 1f),
        hue = hue.sane(MIN_HUE, MAX_HUE, 0f),
        temperature = temperature.sane(MIN_TEMPERATURE, MAX_TEMPERATURE, 0f),
        sharpness = sharpness.sane(MIN_SHARPNESS, MAX_SHARPNESS, 0f),
        shadowLift = shadowLift.sane(MIN_SHADOW_LIFT, MAX_SHADOW_LIFT, 0f),
    )

    companion object {

        const val DEFAULT_THRESHOLD = 0.03f

        private const val WARM_GAIN = 0.0015f
        private const val COOL_GAIN = 0.0012f

        const val MIN_SATURATION = 0.5f
        const val MAX_SATURATION = 2.0f
        const val MIN_CONTRAST = 0.5f
        const val MAX_CONTRAST = 1.8f
        const val MIN_BRIGHTNESS = -0.2f
        const val MAX_BRIGHTNESS = 0.2f
        const val MIN_GAMMA = 0.5f
        const val MAX_GAMMA = 2.0f
        const val MIN_HUE = -180f
        const val MAX_HUE = 180f
        const val MIN_TEMPERATURE = -100f
        const val MAX_TEMPERATURE = 100f
        const val MIN_SHARPNESS = 0f
        const val MAX_SHARPNESS = 0.8f
        const val MIN_SHADOW_LIFT = 0f
        const val MAX_SHADOW_LIFT = 0.6f

        val OFF = PictureProfile()

        fun of(preset: PicturePreset): PictureProfile = when (preset) {
            PicturePreset.Natural -> basic(1.02f, 1.02f, 0.0f, 0.04f, 0.035f, 0.0f)
            PicturePreset.Vivid -> basic(1.26f, 1.12f, 0.01f, 0.14f, 0.035f, 0.0f)
            PicturePreset.Clear -> basic(1.04f, 1.12f, 0.0f, 0.36f, 0.025f, 0.02f)
            PicturePreset.Bright -> style(1.04f, 1.05f, 0.01f, 0.04f, 0.06f, 1.01f, 0.0f)
            PicturePreset.Cinema -> style(1.04f, 1.14f, -0.03f, 0.03f, 0.03f, 0.97f, 26.0f)
            PicturePreset.Soft -> style(0.95f, 0.94f, 0.005f, 0.0f, 0.05f, 1.03f, 14.0f)
            PicturePreset.Warm -> style(1.05f, 1.04f, 0.0f, 0.03f, 0.02f, 1.0f, 42.0f)
            PicturePreset.Cool -> style(1.04f, 1.05f, 0.0f, 0.03f, 0.02f, 1.0f, -42.0f)
            PicturePreset.Comfort -> style(0.92f, 0.93f, -0.01f, 0.0f, 0.06f, 1.04f, 58.0f)
            PicturePreset.Anime -> basic(1.24f, 1.10f, 0.02f, 0.28f, 0.025f, 0.02f)
            PicturePreset.Sport -> basic(1.12f, 1.14f, 0.02f, 0.24f, DEFAULT_THRESHOLD, 0.04f)
            PicturePreset.Game -> basic(1.08f, 1.14f, 0.02f, 0.30f, 0.025f, 0.04f)
            PicturePreset.Original, PicturePreset.Custom -> OFF
        }

        private fun basic(
            saturation: Float,
            contrast: Float,
            brightness: Float,
            sharpness: Float,
            threshold: Float,
            shadowLift: Float,
        ) = PictureProfile(
            saturation = saturation,
            contrast = contrast,
            brightness = brightness,
            sharpness = sharpness,
            threshold = threshold,
            shadowLift = shadowLift,
        )

        private fun style(
            saturation: Float,
            contrast: Float,
            brightness: Float,
            sharpness: Float,
            shadowLift: Float,
            gamma: Float,
            temperature: Float,
        ) = basic(saturation, contrast, brightness, sharpness, DEFAULT_THRESHOLD, shadowLift)
            .copy(gamma = gamma, temperature = temperature)
    }
}

private fun Float.sane(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback
