package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class Ac3CoreReaderTest {

    @Test
    fun validCoreFrame_emitsAc3Sample() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(coreFrame()))

        val format = requireNotNull(output.format)
        assertEquals(MimeTypes.AUDIO_AC3, format.sampleMimeType)
        assertEquals(48000, format.sampleRate)
        assertEquals(6, format.channelCount)
        assertEquals(640000, format.bitrate)
        val sample = output.samples.single()
        assertEquals(2560, sample.size)
        assertEquals(1_000_000, sample.timeUs)
        assertTrue(sample.flags and C.BUFFER_FLAG_KEY_FRAME != 0)
    }

    @Test
    fun falseSyncWithInvalidHeader_isIgnored() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(falseSyncFrame()))

        assertEquals(0, output.samples.size)
        assertNull(output.format)
    }

    @Test
    fun pesWithoutSync_isSkipped() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(ByteArray(2560)))

        assertEquals(0, output.samples.size)
    }

    @Test
    fun chunkedFeed_stillEmitsSingleFrame() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val bytes = coreFrame()
        reader.packetStarted(1_000_000, 0)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(100, bytes.size - offset)
            reader.consume(ParsableByteArray(bytes.copyOfRange(offset, offset + length)))
            offset += length
        }

        assertEquals(1, output.samples.size)
        assertEquals(2560, output.samples.single().size)
    }

    @Test
    fun tinyChunks_splitHeaderIsReassembled() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val bytes = coreFrame()
        reader.packetStarted(1_000_000, 0)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(3, bytes.size - offset)
            reader.consume(ParsableByteArray(bytes.copyOfRange(offset, offset + length)))
            offset += length
        }

        assertEquals(1, output.samples.size)
        assertEquals(2560, output.samples.single().size)
    }

    @Test
    fun frameSpanningPes_keepsFirstTimestamp() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val bytes = coreFrame()
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(bytes.copyOfRange(0, 1000)))
        reader.packetStarted(1_032_000, 0)
        reader.consume(ParsableByteArray(bytes.copyOfRange(1000, bytes.size)))

        assertEquals(1_000_000, output.samples.single().timeUs)
    }

    @Test
    fun secondFrame_usesItsOwnPesTimestamp() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(coreFrame()))
        reader.packetStarted(1_032_000, 0)
        reader.consume(ParsableByteArray(coreFrame()))

        assertEquals(listOf(1_000_000L, 1_032_000L), output.samples.map { it.timeUs })
    }

    private fun newReader(output: FakeTrackOutput): Ac3CoreReader {
        val reader = Ac3CoreReader("chi")
        reader.createTracks(FakeExtractorOutput(output), TsPayloadReader.TrackIdGenerator(0, 1))
        return reader
    }

    private fun coreFrame(): ByteArray {
        val header = byteArrayOf(
            0x0B, 0x77, 0x41, 0xAD.toByte(), 0x24, 0x30, 0xE1.toByte(), 0xFF.toByte(),
            0xFC.toByte(), 0x6C, 0x92.toByte(), 0x50, 0x00, 0x03, 0xE9.toByte(),
        )
        return header + ByteArray(2560 - header.size)
    }

    private fun falseSyncFrame(): ByteArray {
        val header = byteArrayOf(
            0x0B, 0x77, 0x5C, 0x58, 0x2D, 0x2F, 0x7A, 0xAB.toByte(),
        )
        return header + ByteArray(2560 - header.size)
    }

    private class FakeExtractorOutput(private val trackOutput: TrackOutput) : ExtractorOutput {

        override fun track(id: Int, type: Int): TrackOutput = trackOutput

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class FakeTrackOutput : TrackOutput {

        private val buffer = ByteArray(1 shl 20)

        var format: Format? = null
            private set

        val samples = mutableListOf<Sample>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.readBytes(buffer, 0, length)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            length

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            samples.add(Sample(timeUs, flags, size))
        }
    }

    private data class Sample(val timeUs: Long, val flags: Int, val size: Int)

    private companion object {

        @BeforeClass
        @JvmStatic
        fun disableLegacyLimitCheck() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false)
        }
    }
}
