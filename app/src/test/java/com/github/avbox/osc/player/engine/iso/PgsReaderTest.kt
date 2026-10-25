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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PgsReaderTest {

    @Test
    fun completeDisplaySet_emitsOneSample() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(displaySet()))

        val format = requireNotNull(output.format)
        assertEquals(MimeTypes.APPLICATION_PGS, format.sampleMimeType)
        val sample = output.samples.single()
        assertEquals(displaySet().size, sample.size)
        assertEquals(1_000_000, sample.timeUs)
        assertTrue(sample.flags and C.BUFFER_FLAG_KEY_FRAME != 0)
    }

    @Test
    fun displaySetSplitAcrossPes_isReassembled() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val set = displaySet()
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(set.copyOfRange(0, set.size / 2)))
        reader.packetStarted(1_032_000, 0)
        reader.consume(ParsableByteArray(set.copyOfRange(set.size / 2, set.size)))

        assertEquals(1, output.samples.size)
        assertEquals(1_000_000, output.samples.single().timeUs)
    }

    @Test
    fun twoDisplaySets_emitTwoSamples() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(displaySet()))
        reader.packetStarted(1_500_000, 0)
        reader.consume(ParsableByteArray(displaySet()))

        assertEquals(2, output.samples.size)
        assertEquals(listOf(1_000_000L, 1_500_000L), output.samples.map { it.timeUs })
    }

    @Test
    fun endWithoutPcs_isIgnored() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(segment(SEGMENT_END, ByteArray(0))))

        assertEquals(0, output.samples.size)
        assertEquals(MimeTypes.APPLICATION_PGS, output.format?.sampleMimeType)
    }

    @Test
    fun noPts_dropsDisplaySet() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        reader.packetStarted(C.TIME_UNSET, 0)
        reader.consume(ParsableByteArray(displaySet()))

        assertEquals(0, output.samples.size)
        assertEquals(MimeTypes.APPLICATION_PGS, output.format?.sampleMimeType)
    }

    @Test
    fun oversizeDisplaySet_isDropped() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val big = segment(SEGMENT_PCS, ByteArray(19)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_ODS, ByteArray(60_000)) +
            segment(SEGMENT_END, ByteArray(0))
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(big))

        assertEquals(0, output.samples.size)
        assertEquals(MimeTypes.APPLICATION_PGS, output.format?.sampleMimeType)
    }

    @Test
    fun seek_resetsPendingDisplaySet() {
        val output = FakeTrackOutput()
        val reader = newReader(output)
        val set = displaySet()
        reader.packetStarted(1_000_000, 0)
        reader.consume(ParsableByteArray(set.copyOfRange(0, set.size / 2)))
        reader.seek()
        reader.packetStarted(2_000_000, 0)
        reader.consume(ParsableByteArray(set.copyOfRange(set.size / 2, set.size)))

        assertEquals(0, output.samples.size)
    }

    private fun newReader(output: FakeTrackOutput): PgsReader {
        val reader = PgsReader("chi")
        reader.createTracks(FakeExtractorOutput(output), TsPayloadReader.TrackIdGenerator(0, 1))
        return reader
    }

    private fun displaySet(): ByteArray =
        segment(SEGMENT_PCS, ByteArray(19)) +
            segment(SEGMENT_WDS, ByteArray(10)) +
            segment(SEGMENT_PDS, ByteArray(1277)) +
            segment(SEGMENT_ODS, ByteArray(4096)) +
            segment(SEGMENT_END, ByteArray(0))

    private fun segment(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body

    private class FakeExtractorOutput(private val trackOutput: TrackOutput) : ExtractorOutput {

        override fun track(id: Int, type: Int): TrackOutput = trackOutput

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class FakeTrackOutput : TrackOutput {

        private val buffer = ByteArray(4 shl 20)

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
        const val SEGMENT_PCS = 0x16
        const val SEGMENT_WDS = 0x17
        const val SEGMENT_PDS = 0x14
        const val SEGMENT_ODS = 0x15
        const val SEGMENT_END = 0x80

        @BeforeClass
        @JvmStatic
        fun disableLegacyLimitCheck() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false)
        }
    }
}
