package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.avbox.osc.ComposeTestActivity
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.testing.TestApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

private class NoopPlacementActions : PlayerActions {
    override fun toggleControls() = Unit
    override fun keepControlsAlive() = Unit
    override fun onNextClicked() = Unit
    override fun onPreClicked() = Unit
    override fun onPlayPauseClicked() = Unit
    override fun onScaleClicked() = Unit
    override fun onScaleLongClicked() = Unit
    override fun onSpeedClicked() = Unit
    override fun onSpeedLongClicked() = Unit
    override fun onPlayerClicked() = Unit
    override fun onPlayerLongClicked() = Unit
    override fun onTimeStartClicked() = Unit
    override fun onTimeStartLongClicked() = Unit
    override fun onTimeEndClicked() = Unit
    override fun onTimeEndLongClicked() = Unit
    override fun onTimeResetClicked() = Unit
    override fun onEpisodeClicked() = Unit
    override fun onCastClicked() = Unit
    override fun onSubtitleClicked() = Unit
    override fun onSubtitleLongClicked() = Unit
    override fun onAudioTrackClicked() = Unit
    override fun onVideoTrackClicked() = Unit
    override fun onDanmuSettingClicked() = Unit
    override fun onDanmuSettingLongClicked() = Unit
    override fun onDanmuSearchClicked() = Unit
    override fun onDanmuSearchLongClicked() = Unit
    override fun onRotateClicked() = Unit
    override fun onParamsClicked() = Unit
    override fun onInfoOsdClicked() = Unit
    override fun onBackClicked() = Unit
    override fun onLockClicked() = Unit
    override fun onParseSelected(position: Int) = Unit
    override fun onSeekStarted() = Unit
    override fun onSeekPreview(progress: Int) = Unit
    override fun onSeekFinished(progress: Int) = Unit
    override fun onSeekCancelled() = Unit
    override fun onSeekStep(dir: Int) = Unit
    override fun onSeekRelative(deltaMs: Long) = Unit
    override fun refreshSystemInfo() = Unit
    override fun hideSeekHint() = Unit
    override fun hideSlideHint() = Unit
}

private fun visibleChromeState(): PlayerUiState = PlayerUiState().apply {
    updateTopLeftVisible(true)
    updateTopRightVisible(true)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestApplication::class)
class PlayerActionIconsPlacementTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    @Config(qualifiers = "w840dp-h1280dp-port-xxhdpi")
    fun portraitLargeScreenSpreadsActionIconsAcrossBottomBar() {
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("root"),
            ) {
                PlayerBottomBar(visibleChromeState(), NoopPlacementActions())
            }
        }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val width = rule.onNodeWithTag("root").fetchSemanticsNode().size.width.toFloat()
        val castCenter = rule
            .onNodeWithContentDescription(castLabel(), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center.x
        val videoCenter = rule
            .onNodeWithContentDescription(videoLabel(), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(
            "投屏图标必须落在底部图标行左端, 实际 x=$castCenter / 屏宽=$width",
            castCenter < width * 0.35f,
        )
        assertTrue(
            "视频轨图标必须落在底部图标行右端, 实际 x=$videoCenter / 屏宽=$width",
            videoCenter > width * 0.65f,
        )
        val videoWidth = rule
            .onNodeWithContentDescription(videoLabel(), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val moreWidth = rule
            .onNodeWithContentDescription(moreLabel(), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            "功能图标必须与底部动作行图标等大, 实际 $videoWidth vs $moreWidth",
            abs(videoWidth - moreWidth) <= moreWidth * 0.15f,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp-port-xxhdpi")
    fun portraitLargeScreenLeavesTopBarWithoutActionIcons() {
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("root"),
            ) {
                PlayerTopBar(visibleChromeState(), NoopPlacementActions())
            }
        }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(castLabel(), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h840dp-land-xxhdpi")
    fun landscapeLargeScreenKeepsActionIconsInTopBar() {
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("root"),
            ) {
                PlayerTopBar(visibleChromeState(), NoopPlacementActions())
            }
        }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(castLabel(), useUnmergedTree = true).assertExists()
    }

    private fun castLabel(): String = rule.activity.getString(R.string.common_cast)

    private fun videoLabel(): String = rule.activity.getString(R.string.player_menu_video_track)

    private fun moreLabel(): String = rule.activity.getString(R.string.player_menu_more)
}
