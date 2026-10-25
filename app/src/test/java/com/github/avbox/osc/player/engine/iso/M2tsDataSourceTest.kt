package com.github.avbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M2tsDataSourceTest {

    @Test
    fun m2tsOffset_mapsEveryByte() {
        assertEquals(4L, m2tsOffset(0))
        assertEquals(191L, m2tsOffset(187))
        assertEquals(196L, m2tsOffset(188))
        assertEquals(199L, m2tsOffset(191))
        assertEquals(4 + 188L * M2TS_PACKET_SIZE, m2tsOffset(188L * M2TS_TS_SIZE))
    }

    @Test
    fun m2tsSpan_coversHeadersBetweenEdges() {
        assertEquals(M2tsSpan(4, 188), m2tsSpan(0, 188))
        assertEquals(M2tsSpan(4, 193), m2tsSpan(0, 189))
        assertEquals(M2tsSpan(191, 6), m2tsSpan(187, 2))
        assertEquals(M2tsSpan(104, 88), m2tsSpan(100, 88))
        assertEquals(M2tsSpan(4, 4L * M2TS_PACKET_SIZE - M2TS_ATS_SIZE), m2tsSpan(0, 188L * 4))
    }

    @Test
    fun m2tsLogicalLength_dropsAtsBytes() {
        assertEquals(0L, m2tsLogicalLength(0))
        assertEquals(188L, m2tsLogicalLength(192))
        assertEquals(188L, m2tsLogicalLength(383))
        assertEquals(376L, m2tsLogicalLength(384))
        assertEquals(36_030_496L * 188, m2tsLogicalLength(6_917_855_232))
    }

    @Test
    fun tailWindow_staysWithinPhysicalFile() {
        val fileSize = 64L * M2TS_PACKET_SIZE
        val window = m2tsSpan(m2tsLogicalLength(fileSize) - 112, 112)
        assertTrue(window.offset + window.length <= fileSize)
    }

    @Test
    fun m2tsReadLength_clampsToTarget() {
        assertEquals(500L, m2tsReadLength(0, C.LENGTH_UNSET.toLong(), 500))
        assertEquals(50L, m2tsReadLength(450, C.LENGTH_UNSET.toLong(), 500))
        assertEquals(100L, m2tsReadLength(100, 100, 500))
        assertEquals(100L, m2tsReadLength(400, 100, 500))
        assertEquals(50L, m2tsReadLength(450, 100, 500))
        assertEquals(0L, m2tsReadLength(500, 100, 500))
        assertThrows(IsoFormatException::class.java) {
            m2tsReadLength(501, 100, 500)
        }
        assertThrows(IsoFormatException::class.java) {
            m2tsReadLength(-1, 100, 500)
        }
    }

    @Test
    fun alignedRun_returnsEveryPayloadByte() {
        val image = image(PACKETS)
        val expected = payload(image)
        assertArrayEquals(expected, readAll(stream(image, 0, expected.size.toLong())))
    }

    @Test
    fun unalignedWindow_matchesPayloadSlice() {
        val image = image(PACKETS)
        val expected = payload(image)
        val start = 100
        val length = 400
        assertArrayEquals(
            expected.copyOfRange(start, start + length),
            readAll(stream(image, start.toLong(), length.toLong())),
        )
    }

    @Test
    fun randomWindow_alwaysMatchesPayloadSlice() {
        val image = image(PACKETS)
        val expected = payload(image)
        val random = Random(SEED)
        repeat(WINDOWS) {
            val start = random.nextInt(expected.size)
            val length = random.nextInt(expected.size - start) + 1
            assertArrayEquals(
                expected.copyOfRange(start, start + length),
                readAll(stream(image, start.toLong(), length.toLong())),
            )
        }
    }

    @Test
    fun read_stopsAtPacketBoundary() {
        val image = image(PACKETS)
        val expected = payload(image)
        val active = stream(image, 0, expected.size.toLong())
        val buffer = ByteArray(1024)
        assertEquals(188, active.read(buffer, 0, buffer.size))
        assertEquals(188, active.read(buffer, 0, buffer.size))
        assertEquals(0x47.toByte(), buffer[0])
        assertEquals(376L, active.position())
    }

    @Test
    fun read_skipsAtsAcrossBoundary() {
        val image = image(PACKETS)
        val expected = payload(image)
        val start = 150
        val length = 300
        val active = stream(image, start.toLong(), length.toLong())
        val buffer = ByteArray(1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = active.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        assertArrayEquals(expected.copyOfRange(start, start + length), out.toByteArray())
    }

    @Test
    fun position_tracksLogicalOffset() {
        val image = image(PACKETS)
        val active = stream(image, 100, 500)
        val buffer = ByteArray(64)
        var total = 0
        while (true) {
            val read = active.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
            assertEquals(100L + total, active.position())
        }
        assertEquals(500, total)
    }

    @Test
    fun shortReads_areReassembledWithoutAts() {
        val image = image(PACKETS)
        val expected = payload(image)
        val start = 37L
        val length = 900L
        val span = m2tsSpan(start, length)
        val source = ImageDataSource(image, span.offset.toInt(), READ_STEP)
        assertArrayEquals(
            expected.copyOfRange(start.toInt(), (start + length).toInt()),
            readAll(M2tsStream(source, start, length, span.length)),
        )
    }

    @Test
    fun emptyRange_returnsEndOfInput() {
        val image = image(PACKETS)
        val span = m2tsSpan(0, 1)
        val active = M2tsStream(ImageDataSource(image, span.offset.toInt()), 0, 0, span.length)
        assertEquals(C.RESULT_END_OF_INPUT, active.read(ByteArray(16), 0, 16))
        assertEquals(0L, active.position())
    }

    @Test
    fun truncatedUpstream_throwsInsteadOfFakingEof() {
        val image = image(PACKETS)
        val span = m2tsSpan(0, 400)
        val source = ImageDataSource(image, span.offset.toInt(), Int.MAX_VALUE, limit = M2TS_TS_SIZE)
        val active = M2tsStream(source, 0, 400, span.length)
        val buffer = ByteArray(256)
        var total = 0
        assertThrows(IOException::class.java) {
            while (true) {
                val read = active.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                total += read
            }
        }
        assertEquals(M2TS_TS_SIZE, total)
    }

    @Test
    fun zeroByteRead_returnsZero() {
        val image = image(PACKETS)
        val active = stream(image, 0, 188)
        assertEquals(0, active.read(ByteArray(8), 0, 0))
    }

    private fun stream(image: ByteArray, start: Long, length: Long): M2tsStream {
        val span = m2tsSpan(start, length)
        return M2tsStream(ImageDataSource(image, span.offset.toInt()), start, length, span.length)
    }

    private fun readAll(stream: M2tsStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun image(packets: Int): ByteArray {
        val bytes = ByteArray(packets * M2TS_PACKET_SIZE)
        for (index in 0 until packets) {
            val base = index * M2TS_PACKET_SIZE
            bytes[base] = 0x04
            bytes[base + 1] = 0x7D
            bytes[base + 2] = 0x69
            bytes[base + 3] = 0x3C
            val random = Random(index.toLong())
            for (offset in 0 until M2TS_TS_SIZE) {
                bytes[base + M2TS_ATS_SIZE + offset] =
                    if (offset == 0) 0x47.toByte() else random.nextInt(256).toByte()
            }
        }
        return bytes
    }

    private fun payload(image: ByteArray): ByteArray {
        val packets = image.size / M2TS_PACKET_SIZE
        val payload = ByteArray(packets * M2TS_TS_SIZE)
        for (index in 0 until packets) {
            val base = index * M2TS_PACKET_SIZE + M2TS_ATS_SIZE
            System.arraycopy(image, base, payload, index * M2TS_TS_SIZE, M2TS_TS_SIZE)
        }
        return payload
    }

    private class ImageDataSource(
        private val image: ByteArray,
        private var cursor: Int,
        private val step: Int = Int.MAX_VALUE,
        private val limit: Int = Int.MAX_VALUE,
    ) : DataSource {
        private var consumed = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long = error("open must not be called")

        override fun getUri(): Uri? = null

        override fun close() = Unit

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cursor >= image.size || consumed >= limit) return C.RESULT_END_OF_INPUT
            val count = minOf(length, step, image.size - cursor, limit - consumed)
            System.arraycopy(image, cursor, buffer, offset, count)
            cursor += count
            consumed += count
            return count
        }
    }

    private companion object {
        const val PACKETS = 64
        const val WINDOWS = 200
        const val SEED = 20261019L
        const val READ_STEP = 37
    }
}
