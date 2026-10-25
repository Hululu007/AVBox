package com.github.avbox.osc.player.engine.iso

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PgsParseProbeTest {

    @Test
    fun realDisplaySets_produceBitmapCues() {
        val path = System.getenv("PGS_SETS")
        assumeTrue("PGS_SETS not set", !path.isNullOrEmpty())
        val report = StringBuilder()
        val parser = PgsParser()
        var sets = 0
        var bitmapCues = 0

        DataInputStream(FileInputStream(File(path))).use { input ->
            while (input.available() > 0) {
                val pid = input.readInt()
                val length = input.readInt()
                val data = ByteArray(length)
                input.readFully(data)
                sets++
                val cues = mutableListOf<CuesWithTiming>()
                parser.parse(data, 0, data.size, SubtitleParser.OutputOptions.allCues()) { cues.add(it) }
                report.appendLine("set#$sets pid=0x%04x bytes=%d events=%d".format(pid, length, cues.size))
                for (event in cues) {
                    for (cue in event.cues) {
                        val bitmap = cue.bitmap
                        if (bitmap != null) {
                            bitmapCues++
                            report.appendLine(
                                "  bitmap=${bitmap.width}x${bitmap.height} startUs=${event.startTimeUs} durUs=${event.durationUs}",
                            )
                        } else {
                            report.appendLine("  textCue startUs=${event.startTimeUs}")
                        }
                    }
                }
            }
        }

        report.appendLine("sets=$sets bitmapCues=$bitmapCues")
        File(System.getProperty("java.io.tmpdir"), "pgs-report.txt").writeText(report.toString())
        assertTrue("no display set parsed", sets > 0)
        assertTrue("no bitmap cue produced", bitmapCues > 0)
    }

    private companion object {

        @BeforeClass
        @JvmStatic
        fun disableLegacyLimitCheck() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false)
        }
    }
}
