package com.github.avbox.osc.ui

import com.github.avbox.osc.player.core.PlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlayUiStateTest {

    @Test
    fun isFullBox_followsFullScreenWhenNotRotating() {
        assertFalse(LivePageFrame(fullScreen = false, rotating = false, landscapeNow = true).isFullBox)
        assertTrue(LivePageFrame(fullScreen = true, rotating = false, landscapeNow = false).isFullBox)
    }

    @Test
    fun isFullBox_followsLandscapeWhileRotating() {
        assertTrue(LivePageFrame(fullScreen = true, rotating = true, landscapeNow = true).isFullBox)
        assertFalse(LivePageFrame(fullScreen = true, rotating = true, landscapeNow = false).isFullBox)
    }

    @Test
    fun frameUpdates_keepOtherSlices() {
        val vm = LivePlayViewModel()
        vm.updateFrame { it.copy(pageState = PageState.READY, fullScreen = true) }
        vm.updateOverlay { it.copy(visible = true, timeText = "12:00") }
        vm.updatePlayer { it.copy(playState = PlayState.PLAYING) }
        vm.updateTimeshift { it.copy(isBackState = true) }
        val state = vm.state.value
        assertEquals(PageState.READY, state.frame.pageState)
        assertTrue(state.frame.fullScreen)
        assertTrue(state.overlay.visible)
        assertEquals("12:00", state.overlay.timeText)
        assertEquals(PlayState.PLAYING, state.player.playState)
        assertTrue(state.timeshift.isBackState)
        assertEquals(emptyList<LiveListRow>(), state.channelList.rows)
    }

    @Test
    fun passwordDialogTarget_roundTripsThroughState() {
        val vm = LivePlayViewModel()
        vm.updatePasswordDialogTarget(3 to 5)
        assertEquals(3 to 5, vm.state.value.passwordDialogTarget)
        vm.updatePasswordDialogTarget(null)
        assertEquals(null, vm.state.value.passwordDialogTarget)
    }

    @Test
    fun channelInfoAndEpgSlicesAreIndependent() {
        val vm = LivePlayViewModel()
        vm.updateChannelInfo(ChannelInfoUi(name = "CCTV1", num = 1))
        vm.updateEpg { it.copy(channelName = "CCTV1", canCatchup = true) }
        assertEquals("CCTV1", vm.state.value.channelInfo.name)
        assertEquals("CCTV1", vm.state.value.epg.channelName)
        assertTrue(vm.state.value.epg.canCatchup)
    }
}
