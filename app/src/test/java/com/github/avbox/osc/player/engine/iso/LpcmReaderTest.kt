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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class LpcmReaderTest {

    @Test
    fun stereo24_parsesHeaderAndConvertsTo16Bit() {
        val output = FakeTrackOutput()
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, FRAME_COUNT * 6), body(frame24(0x123456, 0xAABBCC))))

        val format = requireNotNull(output.format)
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(48000, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
        val sample = output.samples.single()
        assertEquals(FRAME_COUNT * 4, sample.size)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xBC.toByte(), 0xAA.toByte()), sample.data.copyOfRange(0, 4))
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xBC.toByte(), 0xAA.toByte()),
            sample.data.copyOfRange(sample.size - 4, sample.size),
        )
    }

    @Test
    fun everySampleCarriesKeyFrameFlag() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        feed(reader, packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, FRAME_COUNT * 6), body(frame24(0x123456, 0xAABBCC))))
        feed(reader, packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, FRAME_COUNT * 6), body(frame24(0x123456, 0xAABBCC))))

        assertEquals(2, output.samples.size)
        output.samples.forEach { sample ->
            assertTrue(sample.flags and C.BUFFER_FLAG_KEY_FRAME != 0)
        }
    }

    @Test
    fun sixteenBitSource_isOnlyByteSwapped() {
        val output = FakeTrackOutput()
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_STEREO, BITS_16, FRAME_COUNT * 4), body(byteArrayOf(0x11, 0x22, 0x33, 0x44))))

        assertEquals(C.ENCODING_PCM_16BIT, requireNotNull(output.format).pcmEncoding)
        val sample = output.samples.single()
        assertEquals(FRAME_COUNT * 4, sample.size)
        assertArrayEquals(byteArrayOf(0x22, 0x11, 0x44, 0x33), sample.data.copyOfRange(0, 4))
    }

    @Test
    fun mono_dropsPaddingChannel() {
        val output = FakeTrackOutput()
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_MONO, BITS_24, FRAME_COUNT * 6), body(frame24(1 shl 8, 2 shl 8))))

        assertEquals(1, requireNotNull(output.format).channelCount)
        val sample = output.samples.single()
        assertEquals(FRAME_COUNT * 2, sample.size)
        assertArrayEquals(byteArrayOf(0x01, 0x00), sample.data.copyOfRange(0, 2))
    }

    @Test
    fun fivePointOne_reordersLfeAndSurround() {
        val output = FakeTrackOutput()
        val frame = frame24(1 shl 8, 2 shl 8, 3 shl 8, 4 shl 8, 5 shl 8, 6 shl 8)
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_51, BITS_24, FRAME_COUNT * 18), body(frame)))

        assertEquals(6, requireNotNull(output.format).channelCount)
        val sample = output.samples.single()
        assertEquals(FRAME_COUNT * 12, sample.size)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0, 6, 0, 4, 0, 5, 0), sample.data.copyOfRange(0, 12))
    }

    @Test
    fun fivePointZero_dropsSixthSourceChannel() {
        val output = FakeTrackOutput()
        val frame = frame24(1 shl 8, 2 shl 8, 3 shl 8, 4 shl 8, 5 shl 8, 6 shl 8)
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_50, BITS_24, FRAME_COUNT * 18), body(frame)))

        assertEquals(5, requireNotNull(output.format).channelCount)
        assertArrayEquals(
            byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0),
            output.samples.single().data.copyOfRange(0, 10),
        )
    }

    @Test
    fun sevenPointOne_reordersLfeAndBackChannels() {
        val output = FakeTrackOutput()
        val frame = frame24(*IntArray(8) { (it + 1) shl 8 })
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_71, BITS_24, FRAME_COUNT * 24), body(frame)))

        assertEquals(8, requireNotNull(output.format).channelCount)
        assertArrayEquals(
            byteArrayOf(1, 0, 2, 0, 3, 0, 8, 0, 5, 0, 6, 0, 4, 0, 7, 0),
            output.samples.single().data.copyOfRange(0, 16),
        )
    }

    @Test
    fun roundingAndClamp_areAppliedOnTwentyFourBitTail() {
        val output = FakeTrackOutput()
        feed(
            newReader(output),
            packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, FRAME_COUNT * 6), body(frame24(0x3FFF80, 0x7FFF80))),
        )

        val sample = output.samples.single()
        assertEquals(FRAME_COUNT * 4, sample.size)
        assertArrayEquals(byteArrayOf(0x00, 0x40), sample.data.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), sample.data.copyOfRange(2, 4))
    }

    @Test
    fun splitChunks_produceSameBytes() {
        val whole = FakeTrackOutput()
        val split = FakeTrackOutput()
        val payload =
            packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, FRAME_COUNT * 6), body(frame24(0x123456, 0xAABBCC)))
        feed(newReader(whole), payload)
        feed(newReader(split), payload, chunkSize = 7)

        assertEquals(whole.samples.size, split.samples.size)
        assertArrayEquals(whole.samples[0].data, split.samples[0].data)
    }

    @Test
    fun headerSplitAcrossChunks_isReassembled() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val payload = packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, 6), frame24(0x123456, 0xAABBCC))
        reader.packetStarted(0, 0)
        reader.consume(ParsableByteArray(payload.copyOfRange(0, 2)))
        reader.consume(ParsableByteArray(payload.copyOfRange(2, payload.size)))
        reader.packetFinished()

        assertEquals(4, output.samples.single().size)
    }

    @Test
    fun eachPacket_stripsItsOwnHeaderAndKeepsTimestamp() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val payload = packet(header(RATE_48K, LAYOUT_STEREO, BITS_24, 6), frame24(0x123456, 0xAABBCC))
        reader.packetStarted(1000, 0)
        reader.consume(ParsableByteArray(payload))
        reader.packetFinished()
        reader.packetStarted(2000, 0)
        reader.consume(ParsableByteArray(payload))
        reader.packetFinished()

        assertEquals(2, output.samples.size)
        assertEquals(1000, output.samples[0].timeUs)
        assertEquals(2000, output.samples[1].timeUs)
        assertEquals(4, output.samples[1].size)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xBC.toByte(), 0xAA.toByte()), output.samples[1].data)
    }

    @Test
    fun highSampleRates_areAccepted() {
        val rates = mapOf(RATE_96K to 96000, RATE_192K to 192000)
        for ((rateCode, expected) in rates) {
            val output = FakeTrackOutput()
            feed(newReader(output), packet(header(rateCode, LAYOUT_STEREO, BITS_24, 6), frame24(1, 1)), )
            assertEquals(expected, requireNotNull(output.format).sampleRate)
            assertEquals(1, output.samples.size)
        }
    }

    @Test
    fun reservedSampleRate_isRejectedWithoutSamples() {
        val output = FakeTrackOutput()
        feed(newReader(output), packet(header(2, LAYOUT_STEREO, BITS_24, FRAME_COUNT * 6), body(frame24(1, 1))))

        assertTrue(output.samples.isEmpty())
        assertEquals(48000, requireNotNull(output.format).sampleRate)
        assertEquals(2, requireNotNull(output.format).channelCount)
    }

    @Test
    fun twentyBitDepth_isRejected() {
        val output = FakeTrackOutput()
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_STEREO, BITS_20, FRAME_COUNT * 6), body(frame24(1, 1))))

        assertTrue(output.samples.isEmpty())
        assertEquals(MimeTypes.AUDIO_RAW, requireNotNull(output.format).sampleMimeType)
    }

    @Test
    fun reservedLayout_isRejected() {
        val output = FakeTrackOutput()
        feed(newReader(output), packet(header(RATE_48K, LAYOUT_RESERVED, BITS_24, 6), frame24(1, 1)))

        assertTrue(output.samples.isEmpty())
    }

    private fun newReader(output: FakeTrackOutput): LpcmReader {
        val reader = LpcmReader()
        reader.createTracks(FakeExtractorOutput(output), TsPayloadReader.TrackIdGenerator(0, 1))
        return reader
    }

    private fun packet(header: ByteArray, body: ByteArray): ByteArray = header + body

    private fun feed(reader: LpcmReader, bytes: ByteArray, chunkSize: Int = Int.MAX_VALUE) {
        reader.packetStarted(0, 0)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(chunkSize, bytes.size - offset)
            reader.consume(ParsableByteArray(bytes.copyOfRange(offset, offset + length)))
            offset += length
        }
        reader.packetFinished()
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
            samples.add(Sample(timeUs, flags, buffer.copyOfRange(offset, offset + size)))
        }
    }

    class Sample(val timeUs: Long, val flags: Int, val data: ByteArray) {
        val size: Int = data.size
    }

    private companion object {

        const val FRAME_COUNT = 240

        @BeforeClass
        @JvmStatic
        fun disableLegacyLimitCheck() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false)
        }

        const val RATE_48K = 1
        const val RATE_96K = 4
        const val RATE_192K = 5

        const val LAYOUT_RESERVED = 0
        const val LAYOUT_MONO = 1
        const val LAYOUT_STEREO = 3
        const val LAYOUT_50 = 8
        const val LAYOUT_51 = 9
        const val LAYOUT_71 = 11

        const val BITS_16 = 1
        const val BITS_20 = 2
        const val BITS_24 = 3

        fun header(rateCode: Int, layoutCode: Int, bitsCode: Int, payloadLength: Int): ByteArray =
            byteArrayOf(
                (payloadLength ushr 8).toByte(),
                payloadLength.toByte(),
                ((layoutCode shl 4) or rateCode).toByte(),
                (bitsCode shl 6).toByte(),
            )

        fun frame24(vararg values: Int): ByteArray {
            val frame = ByteArray(values.size * 3)
            values.forEachIndexed { index, value ->
                frame[index * 3] = (value ushr 16).toByte()
                frame[index * 3 + 1] = (value ushr 8).toByte()
                frame[index * 3 + 2] = value.toByte()
            }
            return frame
        }

        fun body(frame: ByteArray): ByteArray {
            val payload = ByteArray(frame.size * FRAME_COUNT)
            for (index in 0 until FRAME_COUNT) {
                System.arraycopy(frame, 0, payload, index * frame.size, frame.size)
            }
            return payload
        }
    }
}
