package com.github.avbox.osc.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.avbox.osc.ui.ComposeTestActivity
import com.github.avbox.osc.testing.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestApplication::class)
class ComposeGestureHarnessTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun clickReachesComposeNode() {
        var clicks = 0
        rule.setContent {
            Box(
                Modifier
                    .size(100.dp)
                    .testTag("target")
                    .background(Color.Red)
                    .clickable { clicks++ },
            )
        }
        rule.onNodeWithTag("target").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun pointerInputReceivesRawEvents() {
        val events = mutableListOf<String>()
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("surface")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                events.add(if (e.changes.any { it.pressed }) "down/move" else "up")
                            }
                        }
                    },
            )
        }
        rule.onNodeWithTag("surface").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertTrue("pointerInput 未收到任何事件: $events", events.isNotEmpty())
        assertTrue("未观察到按下事件: $events", events.any { it == "down/move" })
    }

    @Test
    fun parentObservesChildConsumption() {
        val seen = mutableListOf<Boolean>()
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("parent")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Final)
                                e.changes.forEach { seen.add(it.isConsumed) }
                            }
                        }
                    },
            ) {
                Box(
                    Modifier
                        .size(60.dp)
                        .testTag("child")
                        .background(Color.Blue)
                        .clickable { },
                )
            }
        }
        rule.onNodeWithTag("child").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertTrue("父层未收到任何事件", seen.isNotEmpty())
        assertTrue("父层在 Final pass 未观察到子控件的消费标记: $seen", seen.any { it })
    }
}
