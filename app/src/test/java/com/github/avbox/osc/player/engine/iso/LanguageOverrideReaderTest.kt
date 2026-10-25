package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageOverrideReaderTest {

    private val captured = ArrayList<Format>()

    private val output = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {
                captured.add(format)
            }

            override fun sampleData(data: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                C.RESULT_END_OF_INPUT

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = Unit

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) = Unit
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class FakeReader(private val pid: Int) : ElementaryStreamReader {
        override fun createTracks(output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
            output.track(pid, C.TRACK_TYPE_AUDIO).format(
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build(),
            )
        }

        override fun packetStarted(timeUs: Long, flags: Int) = Unit

        override fun consume(data: ParsableByteArray) = Unit

        override fun seek() = Unit
    }

    @Test
    fun appliesLanguageForPid() {
        val reader = LanguageOverrideReader(FakeReader(0x1100)) { pid ->
            if (pid == 0x1100) "jpn" else null
        }
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(0x1100, 1))
        assertEquals(1, captured.size)
        assertEquals("ja", captured[0].language)
        assertEquals(MimeTypes.AUDIO_DTS, captured[0].sampleMimeType)
    }

    @Test
    fun keepsFormatWhenPidUnknown() {
        val reader = LanguageOverrideReader(FakeReader(0x1200)) { null }
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(0x1200, 1))
        assertEquals(1, captured.size)
        assertNull(captured[0].language)
    }

    @Test
    fun createTracksWithoutGeneratedIds() {
        val reader = LanguageOverrideReader(FakeReader(0x1100)) { "eng" }
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(0x1100, 1))
        assertEquals("en", captured[0].language)
    }
}
