package com.github.avbox.osc.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGestureHandlerTest {

    private class Recorder : VideoGestureActions {
        val calls = mutableListOf<String>()
        var playback = true

        override fun inPlayback(): Boolean = playback
        override fun onSingleTap() { calls += "singleTap" }
        override fun onDoubleTapTogglePlay() { calls += "doubleTap" }
        override fun onLongPressStart() { calls += "longPressStart" }
        override fun onLongPressEnd() { calls += "longPressEnd" }
        override fun onSeekPreview(totalDeltaX: Float) { calls += "seekPreview:$totalDeltaX" }
        override fun onSeekCommit() { calls += "seekCommit" }
        override fun onSeekCancel() { calls += "seekCancel" }
        override fun onBrightnessSlide(totalDeltaY: Float) { calls += "brightness:$totalDeltaY" }
        override fun onVolumeSlide(totalDeltaY: Float) { calls += "volume:$totalDeltaY" }
    }

    private fun session(
        inPlayback: Boolean = true,
        canChangePosition: Boolean = true,
        enableInNormal: Boolean = true,
        fullScreen: Boolean = true,
        locked: Boolean = false,
        previewMode: Boolean = false,
        paused: Boolean = false,
        gestureEnabled: Boolean = true,
        verticalSlidingDisabled: Boolean = false,
        width: Int = 1000,
        height: Int = 600,
        screenWidth: Int = 1000,
        edge: Boolean = false,
        fromTopBand: Boolean = false,
    ) = VideoGestureSession(
        inPlayback, canChangePosition, enableInNormal, fullScreen, locked, previewMode,
        paused, gestureEnabled, verticalSlidingDisabled, width, height, screenWidth, edge, fromTopBand,
    )

    @Test
    fun singleTapFiresExactlyOnceAfterDoubleTapWindow() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertEquals(
            "抬手应被记为一笔待定点击",
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 1000L),
        )
        assertEquals("此时还不该派发单击(要先等第二下)", emptyList<String>(), r.calls)
        h.markSingleTapConfirmed()
        assertEquals(listOf("singleTap"), r.calls)
    }

    @Test
    fun doubleTapTogglesPlayAndSuppressesSingleTap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        h.beginSession(session(), 505f, 302f)
        assertEquals(
            "第二下应判为双击",
            VideoGestureHandler.EndResult.DOUBLE_TAP,
            h.endSession(cancelled = false, nowMs = 1120L),
        )
        assertEquals(listOf("doubleTap"), r.calls)
        assertFalse(h.withinDoubleTapWindow(1120L))
    }

    @Test
    fun secondTapOutsideWindowIsNotDoubleTap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        h.beginSession(session(), 500f, 300f)
        h.endSession(cancelled = false, nowMs = 1400L)
        assertEquals("超窗不该有双击", emptyList<String>(), r.calls)
    }

    @Test
    fun longPressBoostsAndRestoresOnRelease() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertTrue("静止不动应能触发长按", h.maybeLongPress())
        h.endSession(cancelled = false, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    @Test
    fun longPressDoesNotFireAfterMovement() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.onMove(600f, 300f, slop = 8f)
        assertFalse("动过了就不该长按", h.maybeLongPress())
        assertTrue("没有 longPressStart", r.calls.none { it.startsWith("longPress") })
    }

    @Test
    fun longPressIgnoredWhilePaused() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(paused = true), 500f, 300f)
        assertFalse("暂停态不该提速", h.maybeLongPress())
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun longPressRestoredEvenWhenCancelled() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.maybeLongPress()
        h.endSession(cancelled = true, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    @Test
    fun horizontalDragPreviewThenCommit() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertTrue(h.onMove(600f, 300f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)
        assertEquals(listOf("seekPreview:100.0", "seekCommit"), r.calls)
    }

    @Test
    fun cancelledSeekIsNotCommitted() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        h.onMove(600f, 300f, slop = 8f)
        h.endSession(cancelled = true, nowMs = 1200L)
        assertEquals(listOf("seekPreview:100.0", "seekCancel"), r.calls)
        assertTrue("绝不能提交", r.calls.none { it == "seekCommit" })
    }

    @Test
    fun horizontalDragRejectedWhenPositionChangeDisabled() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(canChangePosition = false), 500f, 300f)
        assertFalse(h.onMove(600f, 300f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)
        assertTrue("不该有 seek", r.calls.none { it.startsWith("seek") })
    }

    @Test
    fun leftHalfVerticalDragAdjustsBrightness() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 200f, 300f)
        assertTrue(h.onMove(200f, 400f, slop = 8f))
        assertEquals(listOf("brightness:100.0"), r.calls)
    }

    @Test
    fun rightHalfVerticalDragAdjustsVolume() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 800f, 300f)
        assertTrue(h.onMove(800f, 400f, slop = 8f))
        assertEquals(listOf("volume:100.0"), r.calls)
    }

    @Test
    fun verticalDragRejectedInNormalStateWhenDisabled() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(fullScreen = false, enableInNormal = false), 200f, 300f)
        assertFalse(h.onMove(200f, 400f, slop = 8f))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun verticalSlidingDisabledBlocksOnlyVertical() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(verticalSlidingDisabled = true), 200f, 300f)
        assertFalse("竖滑应被拦", h.onMove(200f, 400f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)

        val r2 = Recorder()
        val h2 = VideoGestureHandler(r2)
        h2.beginSession(session(verticalSlidingDisabled = true), 200f, 300f)
        assertTrue("横滑仍应放行", h2.onMove(320f, 300f, slop = 8f))
        assertTrue(r2.calls.any { it.startsWith("seekPreview") })
    }

    @Test
    fun previewModeRejectsVerticalDrag() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(previewMode = true), 200f, 300f)
        assertFalse(h.onMove(200f, 400f, slop = 8f))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun nonPlaybackOnlyAllowsSingleTap() {
        val r = Recorder()
        r.playback = false
        val h = VideoGestureHandler(r)
        assertEquals(
            "加载/错误遮罩期不该整段吞掉手势",
            GestureVerdict.CLAIMED,
            h.beginSession(session(inPlayback = false), 500f, 300f),
        )
        assertFalse("非在播不该长按提速", h.maybeLongPress())
        assertFalse("非在播不该横滑 seek", h.onMove(700f, 300f, slop = 8f))
        assertFalse("非在播不该竖滑调亮度", h.onMove(200f, 500f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1000L)
        assertTrue("非在播的滑动不该派发任何动作", r.calls.isEmpty())

        h.beginSession(session(inPlayback = false), 500f, 300f)
        assertEquals(
            "抬手仍应记为一笔待定点击",
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 2000L),
        )
        assertTrue(h.markSingleTapConfirmed())
        assertEquals("非在播只放行单击", listOf("singleTap"), r.calls)
    }

    @Test
    fun edgeBandIsIgnored() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        assertEquals(GestureVerdict.IGNORE, h.beginSession(session(edge = true), 2f, 300f))
    }

    @Test
    fun lockedSessionClaimsAndCallsSingleTapOnRelease() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        assertEquals(GestureVerdict.CLAIMED, h.beginSession(session(locked = true), 500f, 300f))
        h.endSession(cancelled = false, nowMs = 1000L)
        assertEquals(listOf("singleTap"), r.calls)
    }

    @Test
    fun lockedSessionDoesNotSeek() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(locked = true), 500f, 300f)
        assertFalse(h.onMove(700f, 300f, slop = 8f))
        h.endSession(cancelled = false, nowMs = 1200L)
        assertTrue(r.calls.none { it.startsWith("seek") })
    }

    @Test
    fun jitterBelowSlopStaysATap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 500f, 300f)
        assertFalse("未越 slop", h.onMove(503f, 302f, slop = 8f))
        assertEquals(
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 1000L),
        )
        h.markSingleTapConfirmed()
        assertEquals(listOf("singleTap"), r.calls)
    }

    @Test
    fun systemInterruptedSeekOnlyCancels() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.onMove(500f, 300f, slop = 8f)
        h.endSession(cancelled = true, nowMs = 1200L)
        assertEquals(listOf("seekPreview:200.0", "seekCancel"), r.calls)
        assertTrue("中断绝不能提交 seek", r.calls.none { it == "seekCommit" })
        assertTrue("中断绝不能转成竖滑", r.calls.none { it.startsWith("volume") || it.startsWith("brightness") })
    }

    @Test
    fun systemInterruptDuringLongPressRestoresSpeedOnly() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.maybeLongPress()
        h.endSession(cancelled = true, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    @Test
    fun pendingTapIsStillInsideDoubleTapWindowShortlyAfter() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        assertTrue("刚抬手时仍在窗口内(不能立刻发单击)", h.withinDoubleTapWindow(1100L))
        assertFalse("超过窗口后不再是双击候选", h.withinDoubleTapWindow(1400L))
    }

    @Test
    fun doubleTapLeavesNoStraySingleTap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertEquals(
            VideoGestureHandler.EndResult.TAP_PENDING,
            h.endSession(cancelled = false, nowMs = 1000L),
        )
        assertTrue("第一下后应存在待定单击", h.tapPending)
        h.beginSession(session(), 300f, 300f)
        assertEquals(
            VideoGestureHandler.EndResult.DOUBLE_TAP,
            h.endSession(cancelled = false, nowMs = 1120L),
        )
        assertFalse("判成双击后不得再留待定单击", h.tapPending)
        assertFalse(
            "双击之后补发的单击确认必须无效",
            h.markSingleTapConfirmed(),
        )
        assertEquals("整个序列只应有双击", listOf("doubleTap"), r.calls)
    }

    @Test
    fun singleTapConfirmedOnceAndIdempotent() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        assertTrue(h.markSingleTapConfirmed())
        assertFalse("重复确认必须无效", h.markSingleTapConfirmed())
        assertEquals(listOf("singleTap"), r.calls)
    }

    @Test
    fun lockedTapDispatchesImmediatelyWithoutPending() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(locked = true), 300f, 300f)
        h.endSession(cancelled = false, nowMs = 1000L)
        assertFalse("锁屏点击应立即派发,不留待定", h.tapPending)
        assertFalse("确认必须是空操作", h.markSingleTapConfirmed())
        assertEquals(listOf("singleTap"), r.calls)
    }

    @Test
    fun longPressOwnsTheSessionUntilRelease() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertTrue(h.maybeLongPress())
        assertFalse("横移不该 seek", h.onMove(600f, 300f, slop = 8f))
        assertFalse("竖移不该调亮度", h.onMove(300f, 500f, slop = 8f))
        assertFalse("右半屏竖移不该调音量", h.onMove(800f, 500f, slop = 8f))
        assertEquals("长按期间根本不该选出滑动模式", VideoGestureHandler.Mode.UNDECIDED, h.currentMode)
        h.endSession(cancelled = false, nowMs = 1500L)
        assertEquals(
            "整个会话只应有倍速开始/恢复,不得夹带滑动动作",
            listOf("longPressStart", "longPressEnd"),
            r.calls,
        )
    }

    @Test
    fun longPressStillRestoresAfterMovement() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.maybeLongPress()
        h.onMove(700f, 400f, slop = 8f)
        h.endSession(cancelled = false, nowMs = 1500L)
        assertEquals(listOf("longPressStart", "longPressEnd"), r.calls)
    }

    @Test
    fun longPressDoesNotBecomeATap() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        h.maybeLongPress()
        h.endSession(cancelled = false, nowMs = 1500L)
        assertFalse("长按不该留下待定单击", h.tapPending)
        assertFalse(h.markSingleTapConfirmed())
        assertTrue(r.calls.none { it == "singleTap" })
    }

    @Test
    fun smallVerticalMoveBelowCommitThresholdDoesNothing() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertFalse(h.onMove(300f, 340f, slop = 8f))
        assertFalse(h.onMove(300f, 360f, slop = 8f))
        assertTrue("未越阈值不该改亮度", r.calls.none { it.startsWith("brightness") })
        assertTrue("未越阈值不该改音量", r.calls.none { it.startsWith("volume") })
    }

    @Test
    fun verticalMoveBeyondCommitThresholdStartsAdjusting() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertTrue(h.onMove(300f, 450f, slop = 8f))
        assertTrue(r.calls.any { it.startsWith("brightness") })
    }

    @Test
    fun smallHorizontalMoveDoesNotSeek() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertFalse(h.onMove(340f, 302f, slop = 8f))
        assertTrue("小幅横移不该 seek,实际: ${r.calls}", r.calls.none { it.startsWith("seekPreview") })
    }

    @Test
    fun horizontalMoveBeyondCommitThresholdSeeks() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertTrue(h.onMove(460f, 302f, slop = 8f))
        assertTrue(r.calls.any { it.startsWith("seekPreview") })
    }

    @Test
    fun horizontalSeekStartsOnceThresholdCrossed() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 300f)
        assertFalse(h.onMove(340f, 302f, slop = 8f))
        assertTrue(h.onMove(500f, 302f, slop = 8f))
        assertTrue(r.calls.any { it.startsWith("seekPreview") })
    }

    @Test
    fun topBandVerticalDragNeverAdjustsBrightnessOrVolume() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(fromTopBand = true), 300f, 30f)
        assertFalse(h.onMove(300f, 500f, slop = 8f))
        assertFalse(h.onMove(800f, 600f, slop = 8f))
        assertTrue("顶端带不该改亮度", r.calls.none { it.startsWith("brightness") })
        assertTrue("顶端带不该改音量", r.calls.none { it.startsWith("volume") })
    }

    @Test
    fun topBandStillAllowsHorizontalSeek() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(fromTopBand = true), 300f, 30f)
        assertTrue(h.onMove(500f, 40f, slop = 8f))
        assertTrue(r.calls.any { it.startsWith("seekPreview") })
    }

    @Test
    fun midScreenVerticalDragStillAdjusts() {
        val r = Recorder()
        val h = VideoGestureHandler(r)
        h.beginSession(session(), 300f, 1200f)
        assertTrue(h.onMove(300f, 1400f, slop = 8f))
        assertTrue(r.calls.any { it.startsWith("brightness") })
    }
}
