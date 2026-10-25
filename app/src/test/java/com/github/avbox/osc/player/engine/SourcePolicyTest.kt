package com.github.avbox.osc.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyTest {

    @Test
    fun rtmpLiveFlag_appendedWhenMissing() {
        assertEquals("rtmp://x/live/1 live=1", SourcePolicy.applyRtmpLiveFlag("rtmp://x/live/1", true))
    }

    @Test
    fun rtmpLiveFlag_keptWhenAlreadyPresent() {
        assertEquals("rtmp://x/live/1 live=1", SourcePolicy.applyRtmpLiveFlag("rtmp://x/live/1 live=1", true))
        assertEquals("rtmp://x/live/1?a=live=1", SourcePolicy.applyRtmpLiveFlag("rtmp://x/live/1?a=live=1", true))
    }

    @Test
    fun rtmpLiveFlag_notAppliedForVodOrNonRtmp() {
        assertEquals("rtmp://x/live/1", SourcePolicy.applyRtmpLiveFlag("rtmp://x/live/1", false))
        assertEquals("http://x/a.m3u8", SourcePolicy.applyRtmpLiveFlag("http://x/a.m3u8", true))
    }

    @Test
    fun isRtmp_matchesSchemeOnly() {
        assertTrue(SourcePolicy.isRtmp("rtmp://x/1"))
        assertFalse(SourcePolicy.isRtmp("rtmps://x/1"))
        assertFalse(SourcePolicy.isRtmp("http://x/1"))
        assertFalse(SourcePolicy.isRtmp(null))
    }

    @Test
    fun localProxy_detectsLoopbackHosts() {
        assertTrue(SourcePolicy.isLocalProxyUrl("http://127.0.0.1:9978/proxy?url=x"))
        assertTrue(SourcePolicy.isLocalProxyUrl("https://127.0.0.1:9978/x"))
        assertTrue(SourcePolicy.isLocalProxyUrl("http://localhost:9978/x"))
        assertTrue(SourcePolicy.isLocalProxyUrl("https://localhost/x"))
        assertFalse(SourcePolicy.isLocalProxyUrl("http://192.168.1.2/x"))
        assertFalse(SourcePolicy.isLocalProxyUrl(null))
        assertTrue(SourcePolicy.isLocalProxyUrl("http://127.0.0.1.example.com/x"))
    }

    private fun mode(
        isLocalProxyUrl: Boolean = false,
        isRtmp: Boolean = false,
        preloadTarget: Boolean = false,
        playCacheWanted: Boolean = false,
    ): SourcePolicy.CacheMode =
        SourcePolicy.resolveCacheMode(isLocalProxyUrl, isRtmp, preloadTarget, playCacheWanted)

    @Test
    fun cacheMode_noneWhenNothingWanted() {
        assertEquals(SourcePolicy.CacheMode.NONE, mode())
    }

    @Test
    fun cacheMode_localProxyAlwaysNone() {
        assertEquals(SourcePolicy.CacheMode.NONE, mode(isLocalProxyUrl = true, preloadTarget = true))
        assertEquals(SourcePolicy.CacheMode.NONE, mode(isLocalProxyUrl = true, playCacheWanted = true))
    }

    @Test
    fun cacheMode_rtmpAlwaysNone() {
        assertEquals(SourcePolicy.CacheMode.NONE, mode(isRtmp = true, preloadTarget = true))
        assertEquals(SourcePolicy.CacheMode.NONE, mode(isRtmp = true, playCacheWanted = true))
    }

    @Test
    fun cacheMode_preloadTargetWinsOverPlayCache() {
        assertEquals(SourcePolicy.CacheMode.PRELOAD_TARGET, mode(preloadTarget = true, playCacheWanted = true))
    }

    @Test
    fun cacheMode_playCacheWhenOnlyWanted() {
        assertEquals(SourcePolicy.CacheMode.PLAY_CACHE, mode(playCacheWanted = true))
    }
}
