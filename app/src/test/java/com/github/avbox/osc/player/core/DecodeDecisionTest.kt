package com.github.avbox.osc.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeDecisionTest {

    @Test
    fun exoDecodeApplied_onlySoftValueMatchesAppliedFlag() {
        assertTrue(DecodeDecision.isExoDecodeApplied("软解码", true))
        assertTrue(DecodeDecision.isExoDecodeApplied("硬解码", false))
        assertTrue(DecodeDecision.isExoDecodeApplied("", false))
        assertTrue(DecodeDecision.isExoDecodeApplied(null, false))
        assertFalse(DecodeDecision.isExoDecodeApplied("软解码", false))
        assertFalse(DecodeDecision.isExoDecodeApplied("硬解码", true))
        assertFalse(DecodeDecision.isExoDecodeApplied(null, true))
    }

    @Test
    fun decodeKindOf_missingNameIsUnknown() {
        assertEquals(PlayerDecodeKind.UNKNOWN, DecodeKindRule.kindOf(null, true, false))
        assertEquals(PlayerDecodeKind.UNKNOWN, DecodeKindRule.kindOf("", true, false))
    }

    @Test
    fun decodeKindOf_softwareNameWinsOverHardwareFlag() {
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.kindOf("c2.android.aac.decoder", true, false))
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.kindOf("OMX.google.aac.decoder", false, false))
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.kindOf("OMX.ffmpeg.aac.decoder", false, false))
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.kindOf("c2.qti.aac.decoder.sw", true, false))
    }

    @Test
    fun decodeKindOf_softwareOnlyFlagWinsOverHardwareFlag() {
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.kindOf("c2.qti.aac.decoder", true, true))
    }

    @Test
    fun decodeKindOf_vendorHardwareDecoderIsHardware() {
        assertEquals(PlayerDecodeKind.HARDWARE, DecodeKindRule.kindOf("c2.qti.aac.decoder", true, false))
        assertEquals(PlayerDecodeKind.HARDWARE, DecodeKindRule.kindOf("OMX.qcom.audio.decoder.aac", true, false))
    }

    @Test
    fun decodeKindOf_vendorNameWithoutHardwareFlagIsUnknown() {
        assertEquals(PlayerDecodeKind.UNKNOWN, DecodeKindRule.kindOf("OMX.qcom.audio.decoder.aac", false, false))
    }

    @Test
    fun videoDecodeKindOf_onlySoftwareNamesOrFlagAreSoftware() {
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.videoKindOf("OMX.google.hevc.decoder", false))
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.videoKindOf("c2.android.hevc.decoder", false))
        assertEquals(PlayerDecodeKind.SOFTWARE, DecodeKindRule.videoKindOf("c2.qti.hevc.decoder", true))
        assertEquals(PlayerDecodeKind.HARDWARE, DecodeKindRule.videoKindOf("c2.qti.hevc.decoder", false))
        assertEquals(PlayerDecodeKind.HARDWARE, DecodeKindRule.videoKindOf("OMX.qcom.video.decoder.hevc", false))
        assertEquals(PlayerDecodeKind.UNKNOWN, DecodeKindRule.videoKindOf("", false))
        assertEquals(PlayerDecodeKind.UNKNOWN, DecodeKindRule.videoKindOf(null, false))
    }

    @Test
    fun softDecodeFallbackSkippedOnlyWhenTargetIsCurrentCodec() {
        assertTrue(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                "OMX.google.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                "c2.android.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                "c2.android.hevc.decoder",
                "c2.qti.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
        assertFalse(DecodeDecision.shouldSkipSoftDecodeFallback(null, null, PlayerDecodeKind.UNKNOWN))
        assertFalse(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                "OMX.google.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
        assertFalse(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                null,
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.UNKNOWN,
            ),
        )
        assertTrue(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                null,
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            DecodeDecision.shouldSkipSoftDecodeFallback(
                null,
                "c2.qti.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
    }

    @Test
    fun hardDecodeFallbackSkippedWhenHardwareTargetIsCurrentOrAbsent() {
        assertTrue(
            DecodeDecision.shouldSkipHardDecodeFallback(
                "c2.qti.hevc.decoder",
                "c2.qti.hevc.decoder",
                PlayerDecodeKind.HARDWARE,
            ),
        )
        assertTrue(
            DecodeDecision.shouldSkipHardDecodeFallback(
                "OMX.google.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(
            DecodeDecision.shouldSkipHardDecodeFallback(
                "c2.qti.hevc.decoder",
                "OMX.google.hevc.decoder",
                PlayerDecodeKind.SOFTWARE,
            ),
        )
        assertFalse(DecodeDecision.shouldSkipHardDecodeFallback(null, "OMX.google.hevc.decoder", PlayerDecodeKind.SOFTWARE))
        assertFalse(DecodeDecision.shouldSkipHardDecodeFallback("c2.qti.hevc.decoder", null, PlayerDecodeKind.SOFTWARE))
        assertFalse(DecodeDecision.shouldSkipHardDecodeFallback("c2.qti.hevc.decoder", "OMX.google.hevc.decoder", PlayerDecodeKind.UNKNOWN))
    }

    @Test
    fun decodeDisplayIndex_actualKindWinsOverConfigured() {
        assertEquals(1, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.SOFTWARE, "硬解码"))
        assertEquals(1, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.SOFTWARE, "软解码"))
        assertEquals(0, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.HARDWARE, "软解码"))
        assertEquals(0, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.HARDWARE, "硬解码"))
        assertEquals(1, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.UNKNOWN, "软解码"))
        assertEquals(0, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.UNKNOWN, "硬解码"))
        assertEquals(0, DecodeDecision.decodeDisplayIndex(PlayerDecodeKind.UNKNOWN, null))
    }

    @Test
    fun pendingDecodeIndex_livesUntilTimeoutOrCodecGenerationChanges() {
        val now = 10_000L
        assertEquals(1, DecodeDecision.pendingDecodeIndex("软解码", now - 1_000, 3, now, 3, 10_000) ?: -1)
        assertEquals(0, DecodeDecision.pendingDecodeIndex("硬解码", now - 1_000, 3, now, 3, 10_000) ?: -1)
        assertNull(DecodeDecision.pendingDecodeIndex(null, now, 3, now, 3, 10_000))
        assertNull(DecodeDecision.pendingDecodeIndex("软解码", now - 11_000, 3, now, 3, 10_000))
        assertNull(DecodeDecision.pendingDecodeIndex("软解码", now - 1_000, 3, now, 4, 10_000))
    }
}
