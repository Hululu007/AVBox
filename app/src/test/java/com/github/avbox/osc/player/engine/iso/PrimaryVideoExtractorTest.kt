package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryVideoExtractorTest {

    @Test
    fun dropsSecondaryVideoPidOncePrimaryIsDeclared() {
        val fixture = Fixture()
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.declare(SECONDARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.endTracks()
        fixture.read()

        assertEquals(
            listOf(PRIMARY_PID to C.TRACK_TYPE_VIDEO, AUDIO_PID to C.TRACK_TYPE_AUDIO),
            fixture.output.tracks,
        )
        assertTrue(fixture.output.endTracksCalled)
    }

    @Test
    fun keepsSecondaryVideoPidWhenPrimaryNotDeclaredYet() {
        val fixture = Fixture()
        fixture.declare(SECONDARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.endTracks()
        fixture.read()

        assertEquals(
            listOf(SECONDARY_PID to C.TRACK_TYPE_VIDEO, PRIMARY_PID to C.TRACK_TYPE_VIDEO),
            fixture.output.tracks,
        )
    }

    @Test
    fun keepsEveryVideoPidWithoutPlaylistVideoPid() {
        val fixture = Fixture(primaryPid = null)
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.declare(SECONDARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.endTracks()
        fixture.read()

        assertEquals(2, fixture.output.tracks.size)
    }

    @Test
    fun dropsOnlyVideoTracks() {
        val fixture = Fixture()
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.declare(SECONDARY_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.declare(SECONDARY_PID, C.TRACK_TYPE_TEXT).format(AUDIO_FORMAT)
        fixture.endTracks()
        fixture.read()

        assertEquals(3, fixture.output.tracks.size)
    }

    @Test
    fun declaresTracksOnlyWhenTheyDeliverAFormat() {
        val fixture = Fixture()
        val audio = fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO)
        fixture.declare(PHANTOM_PID, C.TRACK_TYPE_AUDIO)

        assertTrue(fixture.output.tracks.isEmpty())

        audio.format(AUDIO_FORMAT)

        assertEquals(listOf(AUDIO_PID to C.TRACK_TYPE_AUDIO), fixture.output.tracks)
    }

    @Test
    fun sampleDataBeforeFormatMaterializesTrack() {
        val fixture = Fixture()
        val video = fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO)
        video.sampleData(ParsableByteArray(ByteArray(165)), 165)

        assertEquals(listOf(PRIMARY_PID to C.TRACK_TYPE_VIDEO), fixture.output.tracks)
        assertEquals(165, fixture.output.trackOutputs[0].sampleBytes)
    }

    @Test
    fun keepsEveryByteWrittenBeforeFormat() {
        val fixture = Fixture()
        val video = fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO)
        video.sampleData(ParsableByteArray(ByteArray(165)), 165)
        video.format(VIDEO_FORMAT)
        video.sampleData(ParsableByteArray(ByteArray(1277)), 1277)
        video.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, 1442, 0, null)

        assertEquals(1442, fixture.output.trackOutputs[0].sampleBytes)
        assertEquals(1, fixture.output.trackOutputs.size)
    }

    @Test
    fun dropsTracksThatNeverWriteDataOrFormat() {
        val fixture = Fixture()
        val video = fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO)
        video.sampleData(ParsableByteArray(ByteArray(8)), 8)
        fixture.declare(PHANTOM_PID, C.TRACK_TYPE_AUDIO)
        fixture.endTracks()
        fixture.clock.advance(HOLD_MS)
        fixture.read()

        assertTrue(fixture.output.endTracksCalled)
        assertEquals(listOf(PRIMARY_PID to C.TRACK_TYPE_VIDEO), fixture.output.tracks)
    }

    @Test
    fun holdsEndTracksWhileDeclaredTrackHasNoFormat() {
        val fixture = Fixture()
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.declare(PHANTOM_PID, C.TRACK_TYPE_AUDIO)
        fixture.endTracks()

        assertFalse(fixture.output.endTracksCalled)

        fixture.read()

        assertFalse(fixture.output.endTracksCalled)
    }

    @Test
    fun releasesEndTracksOnceEveryDeclaredTrackHasFormat() {
        val fixture = Fixture()
        val audio = fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO)
        val late = fixture.declare(LATE_PID, C.TRACK_TYPE_TEXT)
        audio.format(AUDIO_FORMAT)
        fixture.endTracks()

        assertFalse(fixture.output.endTracksCalled)

        late.format(AUDIO_FORMAT)
        fixture.read()

        assertTrue(fixture.output.endTracksCalled)
        assertEquals(
            listOf(AUDIO_PID to C.TRACK_TYPE_AUDIO, LATE_PID to C.TRACK_TYPE_TEXT),
            fixture.output.tracks,
        )
    }

    @Test
    fun dropsSilentTracksWhenHoldExpires() {
        val fixture = Fixture()
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.declare(PHANTOM_PID, C.TRACK_TYPE_AUDIO)
        fixture.endTracks()

        fixture.clock.advance(HOLD_MS)
        fixture.read()

        assertTrue(fixture.output.endTracksCalled)
        assertEquals(listOf(AUDIO_PID to C.TRACK_TYPE_AUDIO), fixture.output.tracks)
    }

    @Test
    fun ignoresFormatsArrivingAfterHoldExpired() {
        val fixture = Fixture()
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        val phantom = fixture.declare(PHANTOM_PID, C.TRACK_TYPE_AUDIO)
        fixture.endTracks()
        fixture.clock.advance(HOLD_MS)
        fixture.read()

        phantom.format(AUDIO_FORMAT)
        phantom.sampleData(ParsableByteArray(ByteArray(4)), 4)

        assertEquals(listOf(AUDIO_PID to C.TRACK_TYPE_AUDIO), fixture.output.tracks)
        assertEquals(1, fixture.output.trackOutputs.size)
    }

    @Test
    fun holdsEndTracksWhileDeclaredVideoHasNoFormat() {
        val fixture = Fixture()
        val video = fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO)
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.endTracks()
        fixture.clock.advance(HOLD_MS * 3)
        fixture.read()

        assertFalse(fixture.output.endTracksCalled)

        video.format(VIDEO_FORMAT)
        fixture.read()

        assertTrue(fixture.output.endTracksCalled)
        assertEquals(
            listOf(AUDIO_PID to C.TRACK_TYPE_AUDIO, PRIMARY_PID to C.TRACK_TYPE_VIDEO),
            fixture.output.tracks,
        )
    }

    @Test
    fun keepsHoldingWithoutAnyAudioVideoFormat() {
        val fixture = Fixture()
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO)
        fixture.endTracks()
        fixture.clock.advance(HOLD_MS * 3)
        fixture.read()

        assertFalse(fixture.output.endTracksCalled)

        fixture.read(Extractor.RESULT_END_OF_INPUT)

        assertTrue(fixture.output.endTracksCalled)
        assertTrue(fixture.output.tracks.isEmpty())
    }

    @Test
    fun releasesFormatsOfDeclaredTracksAfterFlush() {
        val fixture = Fixture()
        val audio = fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO)
        audio.format(AUDIO_FORMAT)
        fixture.endTracks()
        fixture.read()

        audio.format(EAC3_FORMAT)

        assertEquals(2, fixture.output.trackOutputs[0].formats.size)
        assertEquals(MimeTypes.AUDIO_E_AC3, fixture.output.trackOutputs[0].formats[1].sampleMimeType)
    }

    @Test
    fun declaresTracksEagerlyAfterSeekReinit() {
        val fixture = Fixture()
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.endTracks()
        fixture.read()
        assertTrue(fixture.output.endTracksCalled)

        val second = RecordingOutput()
        val seekMap = Unseekable()
        fixture.extractor.seek(0L, 0L)
        fixture.extractor.init(second)
        fixture.delegate.output.seekMap(seekMap)
        fixture.declare(LATE_PID, C.TRACK_TYPE_TEXT)

        assertEquals(listOf(LATE_PID to C.TRACK_TYPE_TEXT), second.tracks)
        assertTrue(second.seekMapCalled)

        fixture.delegate.output.endTracks()

        assertTrue(second.endTracksCalled)
    }

    @Test
    fun forwardsSeekMapAndRelease() {
        val fixture = Fixture()
        fixture.delegate.output.seekMap(Unseekable())
        fixture.extractor.release()

        assertTrue(fixture.output.seekMapCalled)
        assertTrue(fixture.delegate.releaseCalled)
    }

    @Test
    fun suppressesBlacklistedAudioTrackOnly() {
        val fixture = Fixture(suppressedAudio = setOf(AUDIO_PID))
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.declare(PHANTOM_PID, C.TRACK_TYPE_AUDIO).format(AUDIO_FORMAT)
        fixture.endTracks()
        fixture.read()

        assertEquals(
            listOf(PRIMARY_PID to C.TRACK_TYPE_VIDEO, PHANTOM_PID to C.TRACK_TYPE_AUDIO),
            fixture.output.tracks,
        )
    }

    @Test
    fun suppressionIgnoresVideoTracks() {
        val fixture = Fixture(suppressedAudio = setOf(PRIMARY_PID))
        fixture.declare(PRIMARY_PID, C.TRACK_TYPE_VIDEO).format(VIDEO_FORMAT)
        fixture.endTracks()
        fixture.read()

        assertEquals(listOf(PRIMARY_PID to C.TRACK_TYPE_VIDEO), fixture.output.tracks)
    }

    @Test
    fun suppressedAudioDataIsDiscarded() {
        val fixture = Fixture(suppressedAudio = setOf(AUDIO_PID))
        val audio = fixture.declare(AUDIO_PID, C.TRACK_TYPE_AUDIO)
        audio.format(AUDIO_FORMAT)
        audio.sampleMetadata(0L, 0, 64, 0, null)
        fixture.endTracks()
        fixture.read()

        assertTrue(fixture.output.tracks.isEmpty())
        assertTrue(fixture.output.endTracksCalled)
    }

    private class Fixture(
        private val primaryPid: Int? = PRIMARY_PID,
        private val suppressedAudio: Set<Int> = emptySet(),
    ) {

        val clock = FakeClock()
        val delegate = FakeExtractor()
        val output = RecordingOutput()
        val extractor = PrimaryVideoExtractor(delegate, { primaryPid }, clock::elapsed, { it in suppressedAudio })

        init {
            extractor.init(output)
        }

        fun declare(id: Int, type: Int): TrackOutput = delegate.output.track(id, type)

        fun endTracks() = delegate.output.endTracks()

        fun read(result: Int = Extractor.RESULT_CONTINUE) {
            delegate.readResult = result
            extractor.read(INPUT, POSITION)
        }
    }

    private class FakeClock {
        private var current = 0L

        fun elapsed(): Long = current

        fun advance(delta: Long) {
            current += delta
        }
    }

    private class FakeExtractor : Extractor {

        lateinit var output: ExtractorOutput

        var readResult = Extractor.RESULT_CONTINUE
        var releaseCalled = false

        override fun sniff(input: ExtractorInput): Boolean = false

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = readResult

        override fun release() {
            releaseCalled = true
        }
    }

    private class RecordingOutput : ExtractorOutput {

        val tracks = ArrayList<Pair<Int, Int>>()
        val trackOutputs = ArrayList<RecordingTrackOutput>()
        var endTracksCalled = false
        var seekMapCalled = false
        private var ended = false

        override fun track(id: Int, type: Int): TrackOutput {
            if (ended) return DiscardingOutput
            tracks.add(id to type)
            val created = RecordingTrackOutput()
            trackOutputs.add(created)
            return created
        }

        override fun endTracks() {
            endTracksCalled = true
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) {
            seekMapCalled = true
        }
    }

    private class RecordingTrackOutput : TrackOutput {

        val formats = ArrayList<Format>()
        var sampleBytes = 0

        override fun format(format: Format) {
            formats.add(format)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = length

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            sampleBytes += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    private object DiscardingOutput : TrackOutput {

        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = length

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    private class Unseekable : SeekMap {
        override fun isSeekable(): Boolean = false
        override fun getDurationUs(): Long = C.TIME_UNSET
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints = error("unused")
    }

    private object INPUT : ExtractorInput {

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0

        override fun readFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) =
            true

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) = Unit

        override fun skip(length: Int): Int = length

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = true

        override fun skipFully(length: Int) = Unit

        override fun peek(buffer: ByteArray, offset: Int, length: Int): Int = 0

        override fun peekFully(
            buffer: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean,
        ) = true

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int) = Unit

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean = true

        override fun advancePeekPosition(length: Int) = Unit

        override fun resetPeekPosition() = Unit

        override fun getPeekPosition(): Long = 0L

        override fun getPosition(): Long = 0L

        override fun getLength(): Long = C.LENGTH_UNSET.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Unit = throw e
    }

    private companion object {
        const val PRIMARY_PID = 0x1011
        const val SECONDARY_PID = 0x1015
        const val AUDIO_PID = 0x1100
        const val PHANTOM_PID = 0x1101
        const val LATE_PID = 0x1200
        const val HOLD_MS = 1_500L

        val POSITION = PositionHolder()

        val VIDEO_FORMAT: Format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .build()

        val AUDIO_FORMAT: Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .build()

        val EAC3_FORMAT: Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .build()
    }
}
