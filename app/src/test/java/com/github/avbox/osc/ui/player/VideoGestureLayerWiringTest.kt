package com.github.avbox.osc.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.avbox.osc.ui.ComposeTestActivity
import com.github.avbox.osc.testing.TestApplication
import com.github.avbox.osc.player.gesture.VideoGestureActions
import com.github.avbox.osc.player.gesture.VideoGestureHandler
import com.github.avbox.osc.player.gesture.VideoGestureSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestApplication::class)
class VideoGestureLayerWiringTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComposeTestActivity>()

    private class Recorder : VideoGestureActions {
        val calls = mutableListOf<String>()
        override fun inPlayback(): Boolean = true
        override fun onSingleTap() { calls += "singleTap" }
        override fun onDoubleTapTogglePlay() { calls += "doubleTap" }
        override fun onLongPressStart() { calls += "longPressStart" }
        override fun onLongPressEnd() { calls += "longPressEnd" }
        override fun onSeekPreview(totalDeltaX: Float) { calls += "seekPreview" }
        override fun onSeekCommit() { calls += "seekCommit" }
        override fun onSeekCancel() { calls += "seekCancel" }
        override fun onBrightnessSlide(totalDeltaY: Float) { calls += "brightness" }
        override fun onVolumeSlide(totalDeltaY: Float) { calls += "volume" }
    }

    private fun alwaysClaim(): (androidx.compose.ui.unit.IntSize, Float) -> VideoGestureSession =
        { sz, _ ->
            VideoGestureSession(
                inPlayback = true,
                canChangePosition = true,
                enableInNormal = true,
                fullScreen = true,
                locked = false,
                previewMode = false,
                paused = false,
                gestureEnabled = true,
                verticalSlidingDisabled = false,
                width = 1000,
                height = 600,
                screenWidth = 1000,
                edge = false,
            )
        }

    @Test
    fun childConsumedTouchIsNotHandledByGestureLayer() {
        val r = Recorder()
        val handler = VideoGestureHandler(r)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("overlay")
                    .videoGestureLayer(
                        handler = handler,
                        sessionProvider = alwaysClaim(),
                        onTapPending = { handler.markSingleTapConfirmed() },
                    ),
            ) {
                Box(
                    Modifier
                        .size(80.dp)
                        .testTag("controlButton")
                        .background(Color.Blue)
                        .clickable { },
                )
            }
        }
        rule.onNodeWithTag("controlButton").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        assertTrue(
            "子控件消费的触摸不该进入手势层,实际派发: ${r.calls}",
            r.calls.none { it == "singleTap" || it == "doubleTap" },
        )
    }

    @Test
    fun emptyAreaTapStillReachesGestureLayer() {
        val r = Recorder()
        val handler = VideoGestureHandler(r)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("overlay")
                    .videoGestureLayer(
                        handler = handler,
                        sessionProvider = alwaysClaim(),
                        onTapPending = { handler.markSingleTapConfirmed() },
                    ),
            ) {
                Box(
                    Modifier
                        .size(80.dp)
                        .testTag("controlButton")
                        .background(Color.Blue)
                        .clickable { },
                )
            }
        }
        rule.onNodeWithTag("overlay").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(right - 20f, bottom - 20f))
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        assertTrue("空白区单击应派发 singleTap,实际: ${r.calls}", r.calls.contains("singleTap"))
    }

    @Ignore("Robolectric 虚拟时钟下无法稳定表达两次注入的双击时序;判定逻辑由状态机用例覆盖,时序由真机走查覆盖")
    @Test
    fun doubleTapReachesTheLayerAndSuppressesSingleTap() {
        val r = Recorder()
        val handler = VideoGestureHandler(r)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("overlay")
                    .videoGestureLayer(
                        handler = handler,
                        sessionProvider = alwaysClaim(),
                        onTapPending = { handler.markSingleTapConfirmed() },
                    ),
            )
        }
        val c = androidx.compose.ui.geometry.Offset(400f, 300f)
        rule.onNodeWithTag("overlay").performTouchInput {
            down(c)
            up()
        }
        rule.onNodeWithTag("overlay").performTouchInput {
            down(c)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("overlay").performTouchInput {
            down(c)
            up()
        }
        rule.waitForIdle()
        assertTrue("双击应派发 doubleTap,实际: ${r.calls}", r.calls.contains("doubleTap"))
        assertTrue(
            "双击不该再派发单击,实际: ${r.calls}",
            r.calls.none { it == "singleTap" },
        )
    }
    @Test
    fun horizontalDragPreviewsAndCommits() {
        val r = Recorder()
        val handler = VideoGestureHandler(r)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("overlay")
                    .videoGestureLayer(
                        handler = handler,
                        sessionProvider = alwaysClaim(),
                        onTapPending = { handler.markSingleTapConfirmed() },
                    ),
            )
        }
        rule.onNodeWithTag("overlay").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(300f, 300f))
            moveTo(androidx.compose.ui.geometry.Offset(500f, 300f))
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        assertTrue("应派发 seekPreview,实际: ${r.calls}", r.calls.contains("seekPreview"))
        assertTrue("抬手应提交 seek,实际: ${r.calls}", r.calls.contains("seekCommit"))
    }
}
