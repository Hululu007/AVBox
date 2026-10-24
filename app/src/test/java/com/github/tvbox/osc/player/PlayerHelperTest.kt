package com.github.tvbox.osc.player

import com.github.tvbox.osc.player.core.PlayerDecodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHelperTest {

    @Test
    fun displaySpeed_usesByteUnitsWithBinaryBase() {
        assertEquals("512B/s", PlayerHelper.getDisplaySpeed(512L, true))
        assertEquals("900KB/s", PlayerHelper.getDisplaySpeed(900L * 1024, true))
        assertTrue(PlayerHelper.getDisplaySpeed(3L * 1024 * 1024, true).endsWith("MB/s"))
    }

    @Test
    fun displaySpeed_zeroTextFollowsShowFlag() {
        assertEquals("0B/s", PlayerHelper.getDisplaySpeed(0L, true))
        assertEquals("", PlayerHelper.getDisplaySpeed(0L, false))
    }

    @Test
    fun exoDecodeApplied_onlySoftValueMatchesAppliedFlag() {
        assertTrue(PlayerHelper.isExoDecodeApplied("软解码", true))
        assertTrue(PlayerHelper.isExoDecodeApplied("硬解码", false))
        assertTrue(PlayerHelper.isExoDecodeApplied("", false))
        assertTrue(PlayerHelper.isExoDecodeApplied(null, false))
        assertFalse(PlayerHelper.isExoDecodeApplied("软解码", false))
        assertFalse(PlayerHelper.isExoDecodeApplied("硬解码", true))
        assertFalse(PlayerHelper.isExoDecodeApplied(null, true))
    }

    @Test
    fun decodeKindOf_missingNameIsUnknown() {
        assertEquals(PlayerDecodeKind.UNKNOWN, PlayerHelper.decodeKindOf(null, true, false))
        assertEquals(PlayerDecodeKind.UNKNOWN, PlayerHelper.decodeKindOf("", true, false))
    }

    @Test
    fun decodeKindOf_softwareNameWinsOverHardwareFlag() {
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.decodeKindOf("c2.android.aac.decoder", true, false))
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.decodeKindOf("OMX.google.aac.decoder", false, false))
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.decodeKindOf("OMX.ffmpeg.aac.decoder", false, false))
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.decodeKindOf("c2.qti.aac.decoder.sw", true, false))
    }

    @Test
    fun decodeKindOf_softwareOnlyFlagWinsOverHardwareFlag() {
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.decodeKindOf("c2.qti.aac.decoder", true, true))
    }

    @Test
    fun decodeKindOf_vendorHardwareDecoderIsHardware() {
        assertEquals(PlayerDecodeKind.HARDWARE, PlayerHelper.decodeKindOf("c2.qti.aac.decoder", true, false))
        assertEquals(PlayerDecodeKind.HARDWARE, PlayerHelper.decodeKindOf("OMX.qcom.audio.decoder.aac", true, false))
    }

    @Test
    fun decodeKindOf_vendorNameWithoutHardwareFlagIsUnknown() {
        assertEquals(PlayerDecodeKind.UNKNOWN, PlayerHelper.decodeKindOf("OMX.qcom.audio.decoder.aac", false, false))
    }

    @Test
    fun videoDecodeKindOf_onlySoftwareNamesOrFlagAreSoftware() {
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.videoDecodeKindOf("OMX.google.hevc.decoder", false))
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.videoDecodeKindOf("c2.android.hevc.decoder", false))
        assertEquals(PlayerDecodeKind.SOFTWARE, PlayerHelper.videoDecodeKindOf("c2.qti.hevc.decoder", true))
        assertEquals(PlayerDecodeKind.HARDWARE, PlayerHelper.videoDecodeKindOf("c2.qti.hevc.decoder", false))
        assertEquals(PlayerDecodeKind.HARDWARE, PlayerHelper.videoDecodeKindOf("OMX.qcom.video.decoder.hevc", false))
        assertEquals(PlayerDecodeKind.UNKNOWN, PlayerHelper.videoDecodeKindOf("", false))
        assertEquals(PlayerDecodeKind.UNKNOWN, PlayerHelper.videoDecodeKindOf(null, false))
    }

    @Test
    fun softDecodeFallbackSkippedOnlyWhenTargetIsCurrentCodec() {
        assertTrue(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                "OMX.google.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                "c2.android.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                "c2.android.hevc.decoder",
                "c2.qti.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
        assertFalse(PlayerHelper.shouldSkipSoftDecodeFallback(null, null, PlayerDecodeKind.UNKNOWN))
        assertFalse(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                "OMX.google.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
        assertFalse(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                null,
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.UNKNOWN,
            ),
        )
        assertTrue(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                null,
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            PlayerHelper.shouldSkipSoftDecodeFallback(
                null,
                "c2.qti.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
    }

    @Test
    fun hardDecodeFallbackSkippedWhenHardwareTargetIsCurrentOrAbsent() {
        assertTrue(
            PlayerHelper.shouldSkipHardDecodeFallback(
                "c2.qti.hevc.decoder",
                "c2.qti.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
        assertTrue(
            PlayerHelper.shouldSkipHardDecodeFallback(
                "OMX.google.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            PlayerHelper.shouldSkipHardDecodeFallback(
                "c2.qti.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(PlayerHelper.shouldSkipHardDecodeFallback(null, "OMX.google.hevc.decoder", PlayerDecodeKind.SOFTWARE))
        assertFalse(PlayerHelper.shouldSkipHardDecodeFallback("c2.qti.hevc.decoder", null, PlayerDecodeKind.SOFTWARE))
        assertFalse(PlayerHelper.shouldSkipHardDecodeFallback("c2.qti.hevc.decoder", "OMX.google.hevc.decoder", PlayerDecodeKind.UNKNOWN))
    }

    @Test
    fun decodeDisplayIndex_actualKindWinsOverConfigured() {
        assertEquals(1, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.SOFTWARE, "硬解码"))
        assertEquals(1, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.SOFTWARE, "软解码"))
        assertEquals(0, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.HARDWARE, "软解码"))
        assertEquals(0, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.HARDWARE, "硬解码"))
        assertEquals(1, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.UNKNOWN, "软解码"))
        assertEquals(0, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.UNKNOWN, "硬解码"))
        assertEquals(0, PlayerHelper.decodeDisplayIndex(PlayerDecodeKind.UNKNOWN, null))
    }

    @Test
    fun pendingDecodeIndex_livesUntilTimeoutOrCodecGenerationChanges() {
        val now = 10_000L
        assertEquals(1, PlayerHelper.pendingDecodeIndex("软解码", now - 1_000, 3, now, 3, 10_000) ?: -1)
        assertEquals(0, PlayerHelper.pendingDecodeIndex("硬解码", now - 1_000, 3, now, 3, 10_000) ?: -1)
        assertNull(PlayerHelper.pendingDecodeIndex(null, now, 3, now, 3, 10_000))
        assertNull(PlayerHelper.pendingDecodeIndex("软解码", now - 11_000, 3, now, 3, 10_000))
        assertNull(PlayerHelper.pendingDecodeIndex("软解码", now - 1_000, 3, now, 4, 10_000))
    }
}
