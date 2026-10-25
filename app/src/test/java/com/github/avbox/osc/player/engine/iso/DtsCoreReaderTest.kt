package com.github.avbox.osc.player.engine.iso

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
import org.junit.Test

class DtsCoreReaderTest {

    @Test
    fun emitsCoreFramesAndSkipsExtensionSubstream() {
        val fixture = Fixture()

        fixture.packetStarted(0L)
        fixture.consume(concat(coreFrame(0xAA.toByte()), extensionSubstream(24), coreFrame(0xBB.toByte())))

        assertEquals(listOf(FRAME_SIZE, FRAME_SIZE), fixture.output.sampleSizes)
        assertEquals(listOf(0L, FRAME_DURATION_US), fixture.output.sampleTimes)
        assertEquals(1, fixture.output.formats.size)
        assertEquals(MimeTypes.AUDIO_DTS, fixture.output.formats[0].sampleMimeType)
        assertEquals(6, fixture.output.formats[0].channelCount)
        assertEquals(48_000, fixture.output.formats[0].sampleRate)
        assertArrayEquals(coreFrame(0xAA.toByte()), fixture.output.samples[0])
        assertArrayEquals(coreFrame(0xBB.toByte()), fixture.output.samples[1])
    }

    @Test
    fun resyncsAfterGarbage() {
        val fixture = Fixture()

        fixture.packetStarted(0L)
        fixture.consume(concat(byteArrayOf(0x00, 0x11, 0x7F, 0xFE.toByte()), coreFrame(0xCC.toByte())))

        assertEquals(1, fixture.output.sampleSizes.size)
        assertArrayEquals(coreFrame(0xCC.toByte()), fixture.output.samples[0])
    }

    @Test
    fun keepsOneSampleAcrossPackets() {
        val fixture = Fixture()
        val frame = coreFrame(0xDD.toByte())

        fixture.packetStarted(0L)
        fixture.consume(frame.copyOfRange(0, 40))
        fixture.packetStarted(5_000L)
        fixture.consume(frame.copyOfRange(40, frame.size))

        assertEquals(1, fixture.output.sampleSizes.size)
        assertEquals(0L, fixture.output.sampleTimes[0])
        assertArrayEquals(frame, fixture.output.samples[0])
    }

    @Test
    fun seekResetsToSyncSearchAndKeepsFormat() {
        val fixture = Fixture()
        fixture.packetStarted(0L)
        fixture.consume(coreFrame(0xEE.toByte()))

        fixture.reader.seek()
        fixture.packetStarted(1_000_000L)
        fixture.consume(coreFrame(0xFF.toByte()))

        assertEquals(2, fixture.output.sampleSizes.size)
        assertEquals(1_000_000L, fixture.output.sampleTimes[1])
        assertEquals(1, fixture.output.formats.size)
    }

    private class Fixture {

        val output = RecordingOutput()
        val reader = DtsCoreReader(LANGUAGE, 0, MimeTypes.VIDEO_MP2T)

        init {
            val generator = TsPayloadReader.TrackIdGenerator(PROGRAM, TRACK_ID, 1)
            reader.createTracks(output, generator)
        }

        fun packetStarted(timeUs: Long) = reader.packetStarted(timeUs, 0)

        fun consume(bytes: ByteArray) = reader.consume(ParsableByteArray(bytes))
    }

    private class RecordingOutput : ExtractorOutput {

        val formats = ArrayList<Format>()
        val samples = ArrayList<ByteArray>()
        val sampleSizes = ArrayList<Int>()
        val sampleTimes = ArrayList<Long>()

        override fun track(id: Int, type: Int): TrackOutput = RecordingTrackOutput(this)

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class RecordingTrackOutput(private val owner: RecordingOutput) : TrackOutput {

        private val buffer = java.io.ByteArrayOutputStream()

        override fun format(format: Format) {
            owner.formats.add(format)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            buffer.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = error("unused")

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            owner.samples.add(buffer.toByteArray())
            owner.sampleSizes.add(size)
            owner.sampleTimes.add(timeUs)
            buffer.reset()
        }
    }

    private companion object {
        const val PROGRAM = 1
        const val TRACK_ID = 3
        const val LANGUAGE = "zho"
        const val FRAME_SIZE = 96
        const val FRAME_DURATION_US = 10_666L

        fun coreFrame(marker: Byte): ByteArray {
            val frame = ByteArray(FRAME_SIZE)
            frame[0] = 0x7F
            frame[1] = 0xFE.toByte()
            frame[2] = 0x80.toByte()
            frame[3] = 0x01
            frame[4] = 0x00
            frame[5] = 0x3C
            frame[6] = 0x05
            frame[7] = 0xF2.toByte()
            frame[8] = 0x75
            frame[9] = 0xE0.toByte()
            frame[10] = 0x02
            for (i in 18 until FRAME_SIZE) frame[i] = marker
            return frame
        }

        fun extensionSubstream(size: Int): ByteArray {
            val bytes = ByteArray(size)
            bytes[0] = 0x64
            bytes[1] = 0x58
            bytes[2] = 0x20
            bytes[3] = 0x25
            return bytes
        }

        fun concat(vararg parts: ByteArray): ByteArray {
            val total = parts.sumOf { it.size }
            val out = ByteArray(total)
            var offset = 0
            for (part in parts) {
                System.arraycopy(part, 0, out, offset, part.size)
                offset += part.size
            }
            return out
        }
    }
}
