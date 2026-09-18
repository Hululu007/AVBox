package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeTotalsTest {

    @Test
    fun numberedEpisodes_areCounted() {
        assertEquals(26, EpisodeTotals.episodeCount((1..26).map { "%02d".format(it) }))
        assertEquals(2, EpisodeTotals.episodeCount(listOf("第1集", "第2集")))
        assertEquals(2, EpisodeTotals.episodeCount(listOf("01.mp4", "02.mp4")))
        assertEquals(2, EpisodeTotals.episodeCount(listOf("EP01", "EP02")))
    }

    @Test
    fun movieWithMultipleVersions_isNotCounted() {
        val versions = listOf(
            "[4.0GB]Mayday.2026.1080p中英字幕.mp4 [20秒前置]",
            "[7.9GB]Mayday.2026.1080p高码率,官方中英字幕",
        )
        assertNull(EpisodeTotals.episodeCount(versions))
        assertNull(EpisodeTotals.episodeCount(listOf("HD", "国语HD")))
        assertNull(EpisodeTotals.episodeCount(listOf("原声")))
        assertNull(EpisodeTotals.episodeCount(emptyList()))
    }

    @Test
    fun sameEpisodeInSeveralLanguages_isNotCounted() {
        assertNull(EpisodeTotals.episodeCount(listOf("国语 01", "粤语 01")))
        assertNull(EpisodeTotals.episodeCount(listOf("01", "01")))
    }

    @Test
    fun numberedEpisodeDetection() {
        assertTrue(EpisodeTotals.isNumberedEpisode("02"))
        assertTrue(EpisodeTotals.isNumberedEpisode("第2集"))
        assertFalse(EpisodeTotals.isNumberedEpisode("[4.0GB]Mayday.2026.1080p中英字幕.mp4"))
        assertFalse(EpisodeTotals.isNumberedEpisode("HD"))
        assertFalse(EpisodeTotals.isNumberedEpisode(null))
    }
}
