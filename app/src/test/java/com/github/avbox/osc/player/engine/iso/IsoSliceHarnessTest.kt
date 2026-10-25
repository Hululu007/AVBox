package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IsoSliceHarnessTest {

    @Test
    fun realSlice_producesTracksAndSamples() {
        val path = System.getenv("ISO_SLICE")
        assumeTrue("ISO_SLICE not set", !path.isNullOrEmpty())
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false)
        val slice = File(path).readBytes()
        val report = StringBuilder()
        val output = RecordingOutput(report)
        val extractor = IsoExtractorsFactory(BdTsPayloadReaderFactory(lpcmEnabled = { true }))
            .createExtractors()[0]
        extractor.init(output)

        val logicalLength = slice.size / 192L * 188L
        var position = 0L
        var input = DefaultExtractorInput(SliceReader(slice, position), position, logicalLength)
        val holder = PositionHolder()
        var guard = 0
        var seeks = 0
        val results = IntArray(3)
        val positions = StringBuilder()
        while (guard < MAX_READS) {
            guard++
            val result = extractor.read(input, holder)
            if (result in 0..2) results[result]++
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (result == Extractor.RESULT_SEEK) {
                seeks++
                position = holder.position
                extractor.seek(position, 0)
                input = DefaultExtractorInput(SliceReader(slice, position), position, logicalLength)
                if (seeks > MAX_SEEKS) break
            }
            if (guard % 5000 == 0) positions.append(" p$guard=${input.position}")
        }
        val firstPass = output.tracks.map { it.id to it.count }
        extractor.seek(0, 0)
        input = DefaultExtractorInput(SliceReader(slice, 0), 0, logicalLength)
        var second = 0
        while (second < MAX_READS / 2) {
            second++
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (result == Extractor.RESULT_SEEK) {
                extractor.seek(holder.position, 0)
                input = DefaultExtractorInput(SliceReader(slice, holder.position), holder.position, logicalLength)
            }
        }
        report.appendLine("secondPass reads=$second")
        output.tracks.forEach { track ->
            val before = firstPass.firstOrNull { it.first == track.id }?.second ?: 0
            report.appendLine(
                "  track#${track.id} firstPass=$before secondPass=${track.count - before}",
            )
        }
        extractor.release()

        report.appendLine(
            "readCalls=$guard seeks=$seeks slice=${slice.size} logical=$logicalLength " +
                "results=${results.toList()}$positions",
        )
        output.dump(report)
        File(System.getProperty("java.io.tmpdir"), "iso-harness-report.txt").writeText(report.toString())
    }

    private class SliceReader(private val bytes: ByteArray, start: Long = 0) : DataReader {

        private var position = start
        private val length = bytes.size / 192L * 188L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= this.length) return C.RESULT_END_OF_INPUT
            var read = 0
            val target = minOf(length.toLong(), this.length - position).toInt()
            while (read < target) {
                val absolute = position + read
                val packet = absolute / 188L * 192L
                val inPacket = (absolute % 188L).toInt()
                val chunk = minOf(188 - inPacket, target - read)
                val raw = (packet + M2TS_ATS_SIZE + inPacket).toInt()
                if (raw + chunk > bytes.size) break
                System.arraycopy(bytes, raw, buffer, offset + read, chunk)
                read += chunk
            }
            position += read
            return if (read == 0) C.RESULT_END_OF_INPUT else read
        }
    }

    private class RecordingOutput(private val report: StringBuilder) : ExtractorOutput {

        val tracks = mutableListOf<RecordingTrack>()

        override fun track(id: Int, type: Int): TrackOutput = RecordingTrack(id, type).also {
            tracks.add(it)
            report.appendLine("track id=$id type=$type")
        }

        override fun endTracks() {
            report.appendLine("endTracks")
        }

        override fun seekMap(seekMap: SeekMap) {
            report.appendLine("seekMap durationUs=${seekMap.durationUs} seekable=${seekMap.isSeekable}")
        }

        fun dump(report: StringBuilder) {
            for (track in tracks) {
                report.appendLine(
                    "track#${track.id} type=${track.type} format=${track.format?.let { describe(it) }} " +
                        "samples=${track.count} bytes=${track.bytes}",
                )
                report.appendLine("  firstPts=${track.firstPtsUs} lastPts=${track.lastPtsUs}")
                report.appendLine("  keyFrames=${track.keyFrames} firstKeyPts=${track.firstKeyPtsUs}")
                if (track.nonMonotonic > 0) report.appendLine("  NON_MONOTONIC=${track.nonMonotonic}")
                if (track.sizes.isNotEmpty()) {
                    report.appendLine(
                        "  size min=${track.sizes.min()} max=${track.sizes.max()} " +
                            "distinct=${track.sizes.toSet().take(6)}",
                    )
                }
                if (track.deltas.isNotEmpty()) {
                    report.appendLine(
                        "  deltaUs min=${track.deltas.min()} max=${track.deltas.max()} " +
                            "distinct=${track.deltas.toSet().take(6)}",
                    )
                }
            }
        }

        private fun describe(format: Format): String =
            "${format.sampleMimeType} ${format.sampleRate}Hz ch=${format.channelCount} " +
                "pcm=${format.pcmEncoding} bitrate=${format.averageBitrate}"
    }

    private class RecordingTrack(val id: Int, val type: Int) : TrackOutput {

        var format: Format? = null
            private set
        var count = 0
            private set
        var bytes = 0L
            private set
        var firstPtsUs = C.TIME_UNSET
            private set
        var lastPtsUs = C.TIME_UNSET
            private set
        var nonMonotonic = 0
            private set
        var keyFrames = 0
            private set
        var firstKeyPtsUs = C.TIME_UNSET
            private set
        val sizes = mutableListOf<Int>()
        val deltas = mutableListOf<Long>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
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
            count++
            bytes += size
            if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
                keyFrames++
                if (firstKeyPtsUs == C.TIME_UNSET) firstKeyPtsUs = timeUs
            }
            if (sizes.size < MAX_RECORDED) sizes.add(size)
            if (firstPtsUs == C.TIME_UNSET) {
                firstPtsUs = timeUs
            } else {
                val delta = timeUs - lastPtsUs
                if (delta <= 0) nonMonotonic++
                if (deltas.size < MAX_RECORDED) deltas.add(delta)
            }
            lastPtsUs = timeUs
        }
    }

    private companion object {
        const val MAX_READS = 60000
        const val MAX_SEEKS = 4
        const val MAX_RECORDED = 400
    }
}
