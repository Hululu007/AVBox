package com.github.avbox.osc.player.engine

import android.os.Bundle
import androidx.media3.common.ColorInfo
import androidx.media3.common.PlaybackException
import com.github.avbox.osc.player.core.CodecPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEngineErrorTest {

    private fun error(code: Int): PlaybackException = PlaybackException("m", null, code, Bundle())

    @Test
    fun classifyError_ioAndParsingAreNetwork() {
        assertEquals(PlayerEngine.ERROR_KIND_NETWORK, PlayerEngine.classifyError("ERROR_CODE_IO_UNSPECIFIED"))
        assertEquals(PlayerEngine.ERROR_KIND_NETWORK, PlayerEngine.classifyError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertEquals(PlayerEngine.ERROR_KIND_NETWORK, PlayerEngine.classifyError("ERROR_CODE_PARSING_CONTAINER_MALFORMED"))
    }

    @Test
    fun classifyError_decoderIsDecode() {
        assertEquals(PlayerEngine.ERROR_KIND_DECODE, PlayerEngine.classifyError("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals(PlayerEngine.ERROR_KIND_DECODE, PlayerEngine.classifyError("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"))
    }

    @Test
    fun classifyError_unknownForOtherOrNull() {
        assertEquals(PlayerEngine.ERROR_KIND_UNKNOWN, PlayerEngine.classifyError("ERROR_CODE_UNSPECIFIED"))
        assertEquals(PlayerEngine.ERROR_KIND_UNKNOWN, PlayerEngine.classifyError(null))
    }

    @Test
    fun errorKindValues_areContract() {
        assertEquals(0, PlayerEngine.ERROR_KIND_UNKNOWN)
        assertEquals(1, PlayerEngine.ERROR_KIND_NETWORK)
        assertEquals(2, PlayerEngine.ERROR_KIND_DECODE)
    }

    @Test
    fun classifyTrackKind_audioAndVideo() {
        assertEquals(PlayerEngine.ERROR_TRACK_AUDIO, PlayerEngine.classifyTrackKind("audio/vnd.dts.hd"))
        assertEquals(PlayerEngine.ERROR_TRACK_AUDIO, PlayerEngine.classifyTrackKind("audio/ac3"))
        assertEquals(PlayerEngine.ERROR_TRACK_VIDEO, PlayerEngine.classifyTrackKind("video/hevc"))
        assertEquals(PlayerEngine.ERROR_TRACK_UNKNOWN, PlayerEngine.classifyTrackKind("application/x-media3-cues"))
        assertEquals(PlayerEngine.ERROR_TRACK_UNKNOWN, PlayerEngine.classifyTrackKind(null))
    }

    @Test
    fun trackKindValues_areContract() {
        assertEquals(0, PlayerEngine.ERROR_TRACK_UNKNOWN)
        assertEquals(1, PlayerEngine.ERROR_TRACK_AUDIO)
        assertEquals(2, PlayerEngine.ERROR_TRACK_VIDEO)
    }

    @Test
    fun exceedsDecodeCapability_forFormatAndCapabilitySignals() {
        assertTrue(PlayerEngine.exceedsDecodeCapability("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", null))
        assertTrue(PlayerEngine.exceedsDecodeCapability("ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES", null))
        assertTrue(
            PlayerEngine.exceedsDecodeCapability(
                "ERROR_CODE_DECODING_FAILED",
                "MediaCodecVideoRenderer error, format_supported=NO_EXCEEDS_CAPABILITIES",
            ),
        )
    }

    @Test
    fun exceedsDecodeCapability_falseForOtherSignals() {
        assertFalse(PlayerEngine.exceedsDecodeCapability("ERROR_CODE_DECODING_FAILED", "Decoder failed"))
        assertFalse(PlayerEngine.exceedsDecodeCapability("ERROR_CODE_DECODER_INIT_FAILED", null))
        assertFalse(PlayerEngine.exceedsDecodeCapability("ERROR_CODE_IO_BAD_HTTP_STATUS", "NO_EXCEEDS_CAPABILITIES"))
        assertFalse(PlayerEngine.exceedsDecodeCapability(null, null))
    }

    @Test
    fun isTenBitFormat_detectsDeepColorOnly() {
        assertFalse(PlayerEngine.isTenBitFormat(null))
        assertFalse(PlayerEngine.isTenBitFormat(ColorInfo.Builder().build()))
        assertFalse(
            PlayerEngine.isTenBitFormat(
                ColorInfo.Builder().setLumaBitdepth(8).setChromaBitdepth(8).build(),
            ),
        )
        assertTrue(
            PlayerEngine.isTenBitFormat(
                ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build(),
            ),
        )
        assertTrue(
            PlayerEngine.isTenBitFormat(
                ColorInfo.Builder().setLumaBitdepth(12).setChromaBitdepth(12).build(),
            ),
        )
        assertTrue(
            PlayerEngine.isTenBitFormat(
                ColorInfo.Builder().setLumaBitdepth(10).build(),
            ),
        )
    }

    @Test
    fun isParsingError_ioUnspecifiedAndParsingCodes() {
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)))
    }

    @Test
    fun isParsingError_otherErrorsNotRetried() {
        assertFalse(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertFalse(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertFalse(PlayerEngine.isParsingError(null))
    }

    @Test
    fun codecPreferences_roundTrip() {
        val original = CodecPreferences.preferSoftwareDecode
        try {
            CodecPreferences.preferSoftwareDecode = true
            assertTrue(CodecPreferences.preferSoftwareDecode)
            CodecPreferences.preferSoftwareDecode = false
            assertFalse(CodecPreferences.preferSoftwareDecode)
        } finally {
            CodecPreferences.preferSoftwareDecode = original
        }
    }
}
