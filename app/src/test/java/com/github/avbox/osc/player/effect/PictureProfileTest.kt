package com.github.avbox.osc.player.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureProfileTest {

    @Test
    fun originalAndCustom_areIdentity() {
        assertTrue(PictureProfile.of(PicturePreset.Original).isNoOp)
        assertTrue(PictureProfile.of(PicturePreset.Custom).isNoOp)
        assertEquals(PictureProfile.OFF, PictureProfile.of(PicturePreset.Original))
    }

    @Test
    fun everyNamedPreset_changesThePicture() {
        PicturePreset.entries
            .filter { it != PicturePreset.Original && it != PicturePreset.Custom }
            .forEach { preset ->
                assertFalse("$preset 应是有效果的预设", PictureProfile.of(preset).isNoOp)
            }
    }

    @Test
    fun presetTable_keepsSourceValues() {
        val vivid = PictureProfile.of(PicturePreset.Vivid)
        assertEquals(1.26f, vivid.saturation, 0.0001f)
        assertEquals(1.12f, vivid.contrast, 0.0001f)
        assertEquals(0.01f, vivid.brightness, 0.0001f)
        assertEquals(0.14f, vivid.sharpness, 0.0001f)
        assertEquals(0.035f, vivid.threshold, 0.0001f)
        assertEquals(0f, vivid.shadowLift, 0.0001f)
        assertEquals(1f, vivid.gamma, 0.0001f)
        assertEquals(0f, vivid.hue, 0.0001f)
        assertEquals(0f, vivid.temperature, 0.0001f)

        val cinema = PictureProfile.of(PicturePreset.Cinema)
        assertEquals(0.97f, cinema.gamma, 0.0001f)
        assertEquals(26f, cinema.temperature, 0.0001f)
        assertEquals(PictureProfile.DEFAULT_THRESHOLD, cinema.threshold, 0.0001f)

        val clear = PictureProfile.of(PicturePreset.Clear)
        assertEquals(0.025f, clear.threshold, 0.0001f)
    }

    @Test
    fun onlyCustomPreset_expandsSliders() {
        PicturePreset.entries.forEach { preset ->
            assertEquals(preset == PicturePreset.Custom, preset.adjustable)
        }
    }

    @Test
    fun clamped_bringsOutOfRangeBackIntoSliderRange() {
        val wild = PictureProfile(
            saturation = 9f,
            contrast = 0f,
            brightness = 1f,
            gamma = 0f,
            hue = 999f,
            temperature = -999f,
            sharpness = 5f,
            shadowLift = -3f,
        )
        val fixed = wild.clamped()
        assertEquals(PictureProfile.MAX_SATURATION, fixed.saturation, 0.0001f)
        assertEquals(PictureProfile.MIN_CONTRAST, fixed.contrast, 0.0001f)
        assertEquals(PictureProfile.MAX_BRIGHTNESS, fixed.brightness, 0.0001f)
        assertEquals(PictureProfile.MIN_GAMMA, fixed.gamma, 0.0001f)
        assertEquals(PictureProfile.MAX_HUE, fixed.hue, 0.0001f)
        assertEquals(PictureProfile.MIN_TEMPERATURE, fixed.temperature, 0.0001f)
        assertEquals(PictureProfile.MAX_SHARPNESS, fixed.sharpness, 0.0001f)
        assertEquals(PictureProfile.MIN_SHADOW_LIFT, fixed.shadowLift, 0.0001f)
    }

    @Test
    fun clamped_replacesNaNWithDefault() {
        val fixed = PictureProfile(hue = Float.NaN, saturation = Float.NaN).clamped()
        assertEquals(0f, fixed.hue, 0f)
        assertEquals(1f, fixed.saturation, 0f)
        assertTrue(fixed.clamped().isNoOp)
    }

    @Test
    fun temperatureGains_areNeutralOnlyAtZero() {
        assertTrue(PictureProfile.OFF.isColorNoOp)
        assertEquals(1f, PictureProfile.OFF.redGain, 0f)
        assertEquals(1f, PictureProfile.OFF.blueGain, 0f)
        assertTrue(PictureProfile(temperature = 42f).redGain > 1f)
        assertTrue(PictureProfile(temperature = 42f).blueGain < 1f)
        assertTrue(PictureProfile(temperature = -42f).redGain < 1f)
        assertTrue(PictureProfile(temperature = -42f).blueGain > 1f)
    }

    @Test
    fun noOpFlags_splitByPass() {
        assertTrue(PictureProfile(gamma = 1.2f).isToneNoOp.not())
        assertTrue(PictureProfile(gamma = 1.2f).isColorNoOp)
        assertTrue(PictureProfile(sharpness = 0.2f).isDetailNoOp.not())
        assertTrue(PictureProfile(hue = 30f).isColorNoOp)
    }
}
