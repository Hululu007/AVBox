package com.github.avbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class BdavSyncTest {

    @Test
    fun aligned192Packets_areDetected() {
        assertTrue(isBdav192(bdavImage(PACKETS), PACKETS * M2TS_PACKET_SIZE))
    }

    @Test
    fun plain188Stream_isNotDetected() {
        assertFalse(isBdav192(tsImage(PACKETS), PACKETS * M2TS_TS_SIZE))
    }

    @Test
    fun shiftedByOneByte_isNotDetected() {
        val image = bdavImage(PACKETS)
        val shifted = ByteArray(image.size + 1)
        System.arraycopy(image, 0, shifted, 1, image.size)
        assertFalse(isBdav192(shifted, shifted.size))
    }

    @Test
    fun headerShorterThanFourPackets_isNotDetected() {
        val image = bdavImage(PACKETS)
        assertFalse(isBdav192(image, M2TS_ATS_SIZE + M2TS_PACKET_SIZE * 2 + 1))
    }

    @Test
    fun pesPayload_isDetectedAsStreamData() {
        val data = packet(byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte()))
        assertTrue(hasStreamData(data, data.size))
    }

    @Test
    fun pmtSection_isDetectedAsStreamData() {
        val data = packet(byteArrayOf(0x00, 0x02, 0xB0.toByte()))
        assertTrue(hasStreamData(data, data.size))
    }

    @Test
    fun patAndNullPackets_areNotStreamData() {
        val pat = packet(byteArrayOf(0x00, 0x00, 0xB0.toByte()))
        val nullPacket = packet(null)
        val data = pat + nullPacket
        assertFalse(hasStreamData(data, data.size))
    }

    private fun packet(payload: ByteArray?): ByteArray {
        val bytes = ByteArray(M2TS_PACKET_SIZE) { 0xFF.toByte() }
        bytes[0] = 0x04
        bytes[1] = 0x7D
        bytes[2] = 0x69
        bytes[3] = 0x3C
        val ts = M2TS_ATS_SIZE
        bytes[ts] = TS_SYNC_BYTE
        bytes[ts + 1] = if (payload != null) 0x40 else 0x00
        bytes[ts + 2] = 0x00
        bytes[ts + 3] = 0x10
        payload?.copyInto(bytes, ts + 4)
        return bytes
    }

    private fun bdavImage(packets: Int): ByteArray {
        val bytes = ByteArray(packets * M2TS_PACKET_SIZE)
        for (index in 0 until packets) {
            val base = index * M2TS_PACKET_SIZE
            bytes[base] = 0x04
            bytes[base + 1] = 0x7D
            bytes[base + 2] = 0x69
            bytes[base + 3] = 0x3C
            bytes[base + M2TS_ATS_SIZE] = TS_SYNC_BYTE
        }
        return bytes
    }

    private fun tsImage(packets: Int): ByteArray {
        val bytes = ByteArray(packets * M2TS_TS_SIZE)
        for (index in 0 until packets) {
            bytes[index * M2TS_TS_SIZE] = TS_SYNC_BYTE
        }
        return bytes
    }

    private companion object {
        const val PACKETS = 8
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BdavDataSourceTest {

    @Before
    fun clearProbeCache() {
        BdavProbe.clear()
        IsoSessionSignal.reset()
    }

    @Test
    fun bdavStream_isStrippedTo188() {
        val image = bdavImage(PACKETS)
        val factory = CountingFactory(image)
        val source = BdavDataSource(factory)
        val length = source.open(DataSpec.Builder().setUri(URI).build())
        assertEquals((PACKETS * M2TS_TS_SIZE).toLong(), length)
        assertArrayEquals(payload(image, 0, M2TS_TS_SIZE), readFully(source, M2TS_TS_SIZE))
        source.close()
        assertTrue(IsoSessionSignal.escalated())
    }

    @Test
    fun bdavStream_readsAcrossPacketBoundary() {
        val image = bdavImage(PACKETS)
        val source = BdavDataSource(CountingFactory(image))
        source.open(DataSpec.Builder().setUri(URI).build())
        val count = 500
        assertArrayEquals(payload(image, 0, count), readFully(source, count))
        source.close()
    }

    @Test
    fun bdavStream_seekPositionMapsToPayload() {
        val image = bdavImage(PACKETS)
        val source = BdavDataSource(CountingFactory(image))
        val start = 300L
        val count = 400
        val length = source.open(
            DataSpec.Builder()
                .setUri(URI)
                .setPosition(start)
                .setLength(count.toLong())
                .build(),
        )
        assertEquals(count.toLong(), length)
        assertArrayEquals(payload(image, start.toInt(), count), readFully(source, count))
        source.close()
    }

    @Test
    fun plainStream_failsFast() {
        val image = tsImage(PACKETS)
        val source = BdavDataSource(CountingFactory(image))
        assertThrows(BdavSkipException::class.java) {
            source.open(DataSpec.Builder().setUri(URI).build())
        }
        source.close()
    }

    @Test
    fun stubStream_belowMinimum_failsFast() {
        val image = bdavImage(STUB_PACKETS)
        val source = BdavDataSource(CountingFactory(image))
        assertThrows(BdavSkipException::class.java) {
            source.open(DataSpec.Builder().setUri(URI).build())
        }
        source.close()
        assertFalse(IsoSessionSignal.escalated())
        assertEquals(image.size.toLong(), IsoSessionSignal.stubBytes())
    }

    @Test
    fun alignedButEmptyStream_failsFast() {
        val image = emptyBdavImage(PACKETS)
        val source = BdavDataSource(CountingFactory(image))
        assertThrows(BdavSkipException::class.java) {
            source.open(DataSpec.Builder().setUri(URI).build())
        }
        source.close()
        assertEquals(image.size.toLong(), IsoSessionSignal.stubBytes())
    }

    @Test
    fun probeRunsOncePerUri() {
        val factory = CountingFactory(bdavImage(PACKETS))
        BdavDataSource(factory).apply {
            open(DataSpec.Builder().setUri(URI).build())
            close()
        }
        val afterFirst = factory.created
        assertEquals(2, afterFirst)
        BdavDataSource(factory).apply {
            open(DataSpec.Builder().setUri(URI).build())
            close()
        }
        assertEquals(afterFirst + 1, factory.created)
    }

    private fun readFully(source: DataSource, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val current = source.read(buffer, read, count - read)
            if (current == C.RESULT_END_OF_INPUT) break
            read += current
        }
        assertEquals(count, read)
        return buffer
    }

    private fun payload(image: ByteArray, start: Int, count: Int): ByteArray {
        val packets = image.size / M2TS_PACKET_SIZE
        val stripped = ByteArray(packets * M2TS_TS_SIZE)
        for (index in 0 until packets) {
            System.arraycopy(
                image,
                index * M2TS_PACKET_SIZE + M2TS_ATS_SIZE,
                stripped,
                index * M2TS_TS_SIZE,
                M2TS_TS_SIZE,
            )
        }
        return stripped.copyOfRange(start, start + count)
    }

    private fun bdavImage(packets: Int): ByteArray {
        val bytes = ByteArray(packets * M2TS_PACKET_SIZE)
        for (index in 0 until packets) {
            val base = index * M2TS_PACKET_SIZE
            bytes[base] = 0x04
            bytes[base + 1] = 0x7D
            bytes[base + 2] = 0x69
            bytes[base + 3] = 0x3C
            val ts = base + M2TS_ATS_SIZE
            bytes[ts] = TS_SYNC_BYTE
            when (index) {
                0 -> {
                    bytes[ts + 1] = 0x40
                    bytes[ts + 2] = 0x00
                    bytes[ts + 3] = 0x10
                    bytes[ts + 4] = 0x00
                    bytes[ts + 5] = 0x00
                    bytes[ts + 6] = 0xB0.toByte()
                }
                1 -> {
                    bytes[ts + 1] = 0x41
                    bytes[ts + 2] = 0x00
                    bytes[ts + 3] = 0x10
                    bytes[ts + 4] = 0x00
                    bytes[ts + 5] = 0x00
                    bytes[ts + 6] = 0x01
                }
                else -> {
                    for (offset in 1 until M2TS_TS_SIZE) {
                        bytes[ts + offset] = (index + offset).toByte()
                    }
                }
            }
        }
        return bytes
    }

    private fun emptyBdavImage(packets: Int): ByteArray {
        val bytes = ByteArray(packets * M2TS_PACKET_SIZE)
        for (index in 0 until packets) {
            val base = index * M2TS_PACKET_SIZE
            bytes[base] = 0x04
            bytes[base + 1] = 0x7D
            bytes[base + 2] = 0x69
            bytes[base + 3] = 0x3C
            val ts = base + M2TS_ATS_SIZE
            bytes[ts] = TS_SYNC_BYTE
            bytes[ts + 1] = if (index == 0) 0x40 else 0x00
            bytes[ts + 2] = 0x00
            bytes[ts + 3] = 0x10
            for (offset in 4 until M2TS_TS_SIZE) {
                bytes[ts + offset] = 0xFF.toByte()
            }
            if (index == 0) {
                bytes[ts + 4] = 0x00
                bytes[ts + 5] = 0x00
                bytes[ts + 6] = 0xB0.toByte()
            }
        }
        return bytes
    }

    private fun tsImage(packets: Int): ByteArray {
        val bytes = ByteArray(packets * M2TS_TS_SIZE)
        for (index in 0 until packets) {
            bytes[index * M2TS_TS_SIZE] = TS_SYNC_BYTE
        }
        return bytes
    }

    private class CountingFactory(private val image: ByteArray) : DataSource.Factory {
        var created = 0
            private set

        override fun createDataSource(): DataSource {
            created++
            return ImageSource(image)
        }
    }

    private class ImageSource(private val image: ByteArray) : DataSource {
        private var cursor = 0
        private var remaining = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            cursor = dataSpec.position.toInt()
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                image.size - cursor
            } else {
                minOf(dataSpec.length.toInt(), image.size - cursor)
            }
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            val count = minOf(length, remaining)
            System.arraycopy(image, cursor, buffer, offset, count)
            cursor += count
            remaining -= count
            return count
        }

        override fun close() = Unit

        override fun getUri(): Uri? = null
    }

    private companion object {
        const val PACKETS = 6000
        const val STUB_PACKETS = 32
        val URI: Uri = Uri.parse("https://bdav.test/stream.m2ts")
    }
}
