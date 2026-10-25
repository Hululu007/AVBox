package com.github.avbox.osc.player.state

import com.github.avbox.osc.bean.CastVideo
import com.github.avbox.osc.player.effect.PictureEffectUnavailableReason
import com.github.avbox.osc.player.effect.PicturePreset
import com.github.avbox.osc.player.effect.PictureProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateOverlayTest {

    private fun pictureParams() = PictureParamsState(
        preset = PicturePreset.Original,
        tuning = PictureProfile(),
        unavailableReason = PictureEffectUnavailableReason.None,
        anime4kTierText = "",
        anime4kEnabled = false,
        anime4kUnavailable = false,
        anime4kSharpen = 0f,
        anime4kDeblur = false,
        onAnime4kToggled = {},
        onAnime4kSharpenChanged = {},
        onAnime4kDeblurToggled = {},
        onPresetSelected = {},
        onTuningChanged = {},
        onReset = {},
        onCompareChanged = {},
    )

    private fun paramsSheet() = ParamsSheetState(
        speed = ParamsChoice(emptyList(), 0) {},
        player = ParamsChoice(emptyList(), 0) {},
        scale = ParamsChoice(emptyList(), 0) {},
        picture = pictureParams(),
        timeStartText = "",
        timeEndText = "",
        onSetTimeStart = {},
        onSetTimeEnd = {},
        onResetTime = {},
        onSearchDanmu = null,
    )

    @Test
    fun clearOverlays_closesEveryOverlay() {
        val state = PlayerUiState()
        state.openSelectDialog(SelectDialogState("选择", listOf("A"), 0) {})
        state.openParamsSheet(paramsSheet())
        state.openDanmuSettingSheet(DanmuSettingSheetState(onOpenSearch = {}))
        state.openDanmuSearchSheet(DanmuSearchSheetState("第1集", "片名") {})
        state.openSubtitleSheet(
            SubtitleSheetState(
                exoInternal = false,
                hasInternal = false,
                onSelectInternal = {},
                onSelectLocal = {},
                onSelectRemote = {},
            ),
        )
        state.openSubtitleSearchSheet(SubtitleSearchSheetState("片名") { _, _ -> })
        state.openCastSheet(CastSheetState(CastVideo("http://example.com/v.mp4", "片名", null, 0L)) {})
        state.episodeSheetOpen = true
        assertTrue(state.overlayPanelOpen)

        state.clearOverlays()

        assertFalse(state.overlayPanelOpen)
        assertNull(state.selectDialog)
        assertNull(state.paramsSheet)
        assertNull(state.danmuSettingSheet)
        assertNull(state.danmuSearchSheet)
        assertNull(state.subtitleSheet)
        assertNull(state.subtitleSearchSheet)
        assertNull(state.castSheet)
        assertFalse(state.episodeSheetOpen)
    }

    @Test
    fun clearOverlays_keepsParamsTab() {
        val state = PlayerUiState()
        state.selectParamsTab(ParamsTab.Picture)

        state.clearOverlays()

        assertEquals(ParamsTab.Picture, state.paramsTab)
    }
}
