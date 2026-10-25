package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BdTsPayloadReaderFactoryTest {

    private val factory = BdTsPayloadReaderFactory()

    @Test
    fun defaultStreams_getReader() {
        for (type in intArrayOf(
            TsExtractor.TS_STREAM_TYPE_H264,
            TsExtractor.TS_STREAM_TYPE_H265,
            TsExtractor.TS_STREAM_TYPE_AAC_ADTS,
            TsExtractor.TS_STREAM_TYPE_AC3,
            TsExtractor.TS_STREAM_TYPE_E_AC3,
            TsExtractor.TS_STREAM_TYPE_DTS,
            TsExtractor.TS_STREAM_TYPE_HDMV_DTS,
            SCTE35_STREAM_TYPE,
        )) {
            assertNotNull("streamType=$type", factory.createPayloadReader(type, esInfo(type)))
        }
    }

    @Test
    fun hdmvDts_requiresFlag() {
        assertNull(
            BdTsPayloadReaderFactory(0)
                .createPayloadReader(TsExtractor.TS_STREAM_TYPE_HDMV_DTS, esInfo(TsExtractor.TS_STREAM_TYPE_HDMV_DTS)),
        )
        assertNotNull(
            factory.createPayloadReader(
                TsExtractor.TS_STREAM_TYPE_HDMV_DTS,
                esInfo(TsExtractor.TS_STREAM_TYPE_HDMV_DTS),
            ),
        )
    }

    @Test
    fun bluRayOnlyStreams_areRegisteredOrIgnored() {
        val trueHd = factory.createPayloadReader(
            BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
            esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
        )
        assertNotNull(trueHd)
        assertTrue(trueHd is PesReader)
        val pgs = factory.createPayloadReader(
            BdTsPayloadReaderFactory.STREAM_TYPE_PGS,
            esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_PGS),
        )
        assertNotNull(pgs)
        assertTrue(pgs is PesReader)
        assertNotNull(
            factory.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_LPCM,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM),
            ),
        )
    }

    @Test
    fun pgs_switchOff_doesNotRegister() {
        val disabled = BdTsPayloadReaderFactory(pgsEnabled = { false })
        assertNull(
            disabled.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_PGS,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_PGS),
            ),
        )
    }

    @Test
    fun trueHd_overridesDefaultFactory() {
        assertNull(
            DefaultTsPayloadReaderFactory(0).createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
            ),
        )
        assertNotNull(
            factory.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
            ),
        )
    }

    @Test
    fun trueHd_switchOff_doesNotRegister() {
        val disabled = BdTsPayloadReaderFactory(trueHdCoreEnabled = { false })
        assertNull(
            disabled.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
            ),
        )
    }

    @Test
    fun lpcm_matchesDc2H262AndIsIntercepted() {
        assertEquals(TsExtractor.TS_STREAM_TYPE_DC2_H262, BdTsPayloadReaderFactory.STREAM_TYPE_LPCM)
        val defaultReader = DefaultTsPayloadReaderFactory(0).createPayloadReader(
            TsExtractor.TS_STREAM_TYPE_DC2_H262,
            esInfo(TsExtractor.TS_STREAM_TYPE_DC2_H262),
        )
        val lpcmReader = factory.createPayloadReader(
            BdTsPayloadReaderFactory.STREAM_TYPE_LPCM,
            esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM),
        )
        assertNotNull(defaultReader)
        assertNotNull(lpcmReader)
        assertTrue(lpcmReader is PesReader)
    }

    @Test
    fun lpcm_switchOff_doesNotRegister() {
        val disabled = BdTsPayloadReaderFactory(lpcmEnabled = { false })
        assertNull(
            disabled.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_LPCM,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM),
            ),
        )
    }

    @Test
    fun lpcm_declaresProvisionalRawFormat() {
        val format = provisionalFormatOf(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM)
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(48_000, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
    }

    @Test
    fun trueHd_declaresProvisionalAc3Format() {
        val format = provisionalFormatOf(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD)
        assertEquals(MimeTypes.AUDIO_AC3, format.sampleMimeType)
        assertEquals(48_000, format.sampleRate)
        assertEquals(6, format.channelCount)
    }

    @Test
    fun ac3AndEac3_declareMatchingProvisionalFormats() {
        assertEquals(
            MimeTypes.AUDIO_AC3,
            provisionalFormatOf(BdTsPayloadReaderFactory.STREAM_TYPE_AC3).sampleMimeType,
        )
        assertEquals(
            MimeTypes.AUDIO_E_AC3,
            provisionalFormatOf(BdTsPayloadReaderFactory.STREAM_TYPE_EAC3).sampleMimeType,
        )
    }

    @Test
    fun dts_declaresProvisionalDtsFormat() {
        val format = provisionalFormatOf(BdTsPayloadReaderFactory.STREAM_TYPE_DTS_HD_MA)
        assertEquals(MimeTypes.AUDIO_DTS, format.sampleMimeType)
        assertEquals(6, format.channelCount)
    }

    @Test
    fun pgs_declaresOnlyItsOwnFormat() {
        val output = FormatRecordingOutput()
        requireNotNull(
            factory.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_PGS,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_PGS),
            ),
        ).init(TimestampAdjuster(0L), output, TsPayloadReader.TrackIdGenerator(PROGRAM, TRACK_ID, 1))

        assertEquals(1, output.formats.size)
        assertEquals(MimeTypes.APPLICATION_PGS, output.formats[0].sampleMimeType)
    }

    private fun provisionalFormatOf(streamType: Int): Format {
        val output = FormatRecordingOutput()
        val reader = requireNotNull(factory.createPayloadReader(streamType, esInfo(streamType)))
        reader.init(TimestampAdjuster(0L), output, TsPayloadReader.TrackIdGenerator(PROGRAM, TRACK_ID, 1))
        assertEquals("streamType=$streamType formats=${output.formats}", 1, output.formats.size)
        return output.formats[0]
    }

    private class FormatRecordingOutput : ExtractorOutput {

        val formats = ArrayList<Format>()

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
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
            }

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

    private fun esInfo(streamType: Int) = TsPayloadReader.EsInfo(
        streamType,
        null,
        TsPayloadReader.EsInfo.AUDIO_TYPE_UNDEFINED,
        emptyList(),
        ByteArray(0),
    )

    private companion object {
        const val SCTE35_STREAM_TYPE = 0x86
        const val PROGRAM = 1
        const val TRACK_ID = 0x1100
    }
}
