package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 观看历史落库判据:只累计平滑推进,回拖与 seek 跳变都不算在播(直播守卫在 markWatched,纯 JVM 覆盖不到) */
class PlaybackProgressTest {

    @Test
    fun stepAdvance_countsSmoothPlayback() {
        assertEquals(1_000, PlaybackProgress.stepAdvanceMs(1_000, 0))
        assertEquals(2_000, PlaybackProgress.stepAdvanceMs(3_000, 1_000))
    }

    @Test
    fun stepAdvance_ignoresStalledPosition() {
        // 续播/片头跳过让位置在缓冲期就非 0
        assertEquals(0, PlaybackProgress.stepAdvanceMs(90_000, 90_000))
    }

    @Test
    fun stepAdvance_ignoresJumpAndRewind() {
        // 起始位置迟到落位的 seek、手动跳转、回拖
        assertEquals(0, PlaybackProgress.stepAdvanceMs(90_000, 0))
        assertEquals(0, PlaybackProgress.stepAdvanceMs(0, 90_000))
    }

    @Test
    fun shouldMarkWatched_needsAccumulatedAdvance() {
        assertFalse(PlaybackProgress.shouldMarkWatched(999, "src|1#线路A#0", ""))
        assertTrue(PlaybackProgress.shouldMarkWatched(1_000, "src|1#线路A#0", ""))
    }

    @Test
    fun shouldMarkWatched_sendsOncePerEpisode() {
        assertFalse(PlaybackProgress.shouldMarkWatched(5_000, "src|1#线路A#0", "src|1#线路A#0"))
        assertTrue(PlaybackProgress.shouldMarkWatched(5_000, "src|1#线路A#1", "src|1#线路A#0"))
    }
}
