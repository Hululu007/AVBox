package com.github.avbox.osc.player.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureEffectUnavailableReasonTest {

    private fun reason(
        tunneling: Boolean = false,
        hdr: Boolean = false,
        pipeOpen: Boolean = true,
        effectsActive: Boolean = true,
        hasLook: Boolean = true,
    ) = PictureEffectUnavailableReason.of(tunneling, hdr, pipeOpen, effectsActive, hasLook)

    @Test
    fun tunneling_wins() {
        assertEquals(PictureEffectUnavailableReason.Tunneling, reason(tunneling = true))
        assertEquals(
            PictureEffectUnavailableReason.Tunneling,
            reason(tunneling = true, hdr = true, pipeOpen = false, effectsActive = false),
        )
    }

    @Test
    fun hdr_beatsOtherReasons() {
        assertEquals(PictureEffectUnavailableReason.Hdr, reason(hdr = true, effectsActive = false))
        assertEquals(
            PictureEffectUnavailableReason.Hdr,
            reason(hdr = true, pipeOpen = false, effectsActive = false),
        )
    }

    @Test
    fun closedPipe_withPendingLook_neverHints() {
        assertEquals(
            PictureEffectUnavailableReason.None,
            reason(pipeOpen = false, effectsActive = false),
        )
    }

    @Test
    fun openPipe_withoutEffects_reportsDecoderUnsupported() {
        assertEquals(
            PictureEffectUnavailableReason.DecoderUnsupported,
            reason(effectsActive = false),
        )
    }

    @Test
    fun nothingToApply_neverHints() {
        assertEquals(
            PictureEffectUnavailableReason.None,
            reason(hdr = true, pipeOpen = false, effectsActive = false, hasLook = false),
        )
        assertEquals(
            PictureEffectUnavailableReason.None,
            reason(hdr = true, effectsActive = false, hasLook = false),
        )
    }

    @Test
    fun activeEffects_reportAvailable() {
        assertEquals(PictureEffectUnavailableReason.None, reason())
    }
}
