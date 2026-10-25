package com.github.avbox.osc.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.github.avbox.osc.R
import com.github.avbox.osc.player.effect.PictureEffectUnavailableReason
import com.github.avbox.osc.player.effect.PicturePreset
import com.github.avbox.osc.player.effect.PictureProfile
import com.github.avbox.osc.player.state.PictureParamsState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PictureParams(state: PictureParamsState) {
    var tuning by remember(state) { mutableStateOf(state.tuning) }
    DisposableEffect(Unit) {
        onDispose { state.onCompareChanged(false) }
    }
    Column(Modifier.fillMaxWidth()) {
        state.unavailableReason.hintRes()?.let { hintRes ->
            Text(
                text = stringResource(hintRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = playerTextSize(R.dimen.ts_18),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
        }
        ParamsGroupHeader(R.string.player_picture_preset, R.drawable.player_ic_params_preset)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
            verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
        ) {
            PicturePreset.entries.forEach { preset ->
                SheetButton(
                    text = stringResource(preset.labelRes()),
                    selected = state.preset == preset,
                    onClick = { state.onPresetSelected(preset) },
                    contentPadding = playerDim(R.dimen.vs_20),
                    iconRes = if (preset == PicturePreset.Custom) R.drawable.ic_edit else null,
                )
            }
        }
        Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
        ParamsGroupDivider()
        ParamsGroupHeader(R.string.player_anime4k_title, valueText = state.anime4kTierText)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
        ) {
            SheetButton(
                text = stringResource(if (state.anime4kEnabled) R.string.common_on else R.string.common_off),
                selected = state.anime4kEnabled,
                onClick = { state.onAnime4kToggled(!state.anime4kEnabled) },
                contentPadding = playerDim(R.dimen.vs_20),
                modifier = Modifier.weight(1f),
            )
            if (state.anime4kEnabled) {
                SheetButton(
                    text = stringResource(R.string.player_anime4k_deblur),
                    selected = state.anime4kDeblur,
                    onClick = { state.onAnime4kDeblurToggled(!state.anime4kDeblur) },
                    contentPadding = playerDim(R.dimen.vs_20),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (state.anime4kEnabled) {
            var sharpen by remember(state) { mutableStateOf(state.anime4kSharpen) }
            PictureSlider(R.string.player_anime4k_sharpen, sharpen, 0f..1f, "%.2f") {
                sharpen = it
                state.onAnime4kSharpenChanged(it)
            }
        }
        if (state.anime4kUnavailable) {
            Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
            Text(
                text = stringResource(R.string.player_anime4k_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = playerTextSize(R.dimen.ts_18),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
        ParamsGroupDivider()
        if (state.preset.adjustable) {
            PictureSlider(
                R.string.player_picture_saturation,
                tuning.saturation,
                PictureProfile.MIN_SATURATION..PictureProfile.MAX_SATURATION,
                "%.2f",
            ) {
                tuning = tuning.copy(saturation = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_contrast,
                tuning.contrast,
                PictureProfile.MIN_CONTRAST..PictureProfile.MAX_CONTRAST,
                "%.2f",
            ) {
                tuning = tuning.copy(contrast = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_brightness,
                tuning.brightness,
                PictureProfile.MIN_BRIGHTNESS..PictureProfile.MAX_BRIGHTNESS,
                "%+.3f",
            ) {
                tuning = tuning.copy(brightness = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_gamma,
                tuning.gamma,
                PictureProfile.MIN_GAMMA..PictureProfile.MAX_GAMMA,
                "%.2f",
            ) {
                tuning = tuning.copy(gamma = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_hue,
                tuning.hue,
                PictureProfile.MIN_HUE..PictureProfile.MAX_HUE,
                "%+.0f",
            ) {
                tuning = tuning.copy(hue = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_temperature,
                tuning.temperature,
                PictureProfile.MIN_TEMPERATURE..PictureProfile.MAX_TEMPERATURE,
                "%+.0f",
            ) {
                tuning = tuning.copy(temperature = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_sharpness,
                tuning.sharpness,
                PictureProfile.MIN_SHARPNESS..PictureProfile.MAX_SHARPNESS,
                "%.2f",
            ) {
                tuning = tuning.copy(sharpness = it)
                state.onTuningChanged(tuning)
            }
            PictureSlider(
                R.string.player_picture_shadow,
                tuning.shadowLift,
                PictureProfile.MIN_SHADOW_LIFT..PictureProfile.MAX_SHADOW_LIFT,
                "%.2f",
            ) {
                tuning = tuning.copy(shadowLift = it)
                state.onTuningChanged(tuning)
            }
            ParamsGroupDivider()
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
        ) {
            HoldCompareButton(state, Modifier.weight(1f))
            SheetActionButton(
                text = stringResource(R.string.player_picture_reset),
                onClick = state.onReset,
                iconRes = R.drawable.player_ic_params_reset,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PictureSlider(
    @StringRes labelRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onValueChange: (Float) -> Unit,
) {
    ParamsGroupHeader(labelRes, valueText = String.format(Locale.US, format, value))
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
}

@Composable
private fun HoldCompareButton(state: PictureParamsState, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    SheetButton(
        text = stringResource(R.string.player_picture_compare),
        onClick = {},
        selected = pressed,
        onPressChange = {
            pressed = it
            state.onCompareChanged(it)
        },
        iconRes = R.drawable.player_ic_params_compare,
        modifier = modifier,
    )
}

private fun PictureEffectUnavailableReason.hintRes(): Int? = when (this) {
    PictureEffectUnavailableReason.None -> null
    PictureEffectUnavailableReason.Tunneling -> R.string.player_picture_unavailable_tunnel
    PictureEffectUnavailableReason.Hdr -> R.string.player_picture_unavailable_hdr
    PictureEffectUnavailableReason.DecoderUnsupported -> R.string.player_picture_unavailable_decoder
}

@StringRes
private fun PicturePreset.labelRes(): Int = when (this) {
    PicturePreset.Original -> R.string.player_scale_origin
    PicturePreset.Natural -> R.string.player_picture_preset_natural
    PicturePreset.Vivid -> R.string.player_picture_preset_vivid
    PicturePreset.Clear -> R.string.player_picture_preset_clear
    PicturePreset.Bright -> R.string.player_picture_preset_bright
    PicturePreset.Cinema -> R.string.player_picture_preset_cinema
    PicturePreset.Soft -> R.string.player_picture_preset_soft
    PicturePreset.Warm -> R.string.player_picture_preset_warm
    PicturePreset.Cool -> R.string.player_picture_preset_cool
    PicturePreset.Comfort -> R.string.player_picture_preset_comfort
    PicturePreset.Anime -> R.string.player_picture_preset_anime
    PicturePreset.Sport -> R.string.player_picture_preset_sport
    PicturePreset.Game -> R.string.player_picture_preset_game
    PicturePreset.Custom -> R.string.player_picture_preset_custom
}
