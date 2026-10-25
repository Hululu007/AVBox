package com.github.avbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IsoProbeRequestTest {

    @Before
    fun resetSignal() {
        IsoSessionSignal.reset()
    }

    @Test
    fun probe_asksForFirstChunkInSingleRequest() {
        val factory = FakeFactory(IMAGE)
        val probe = IsoResolver().probe(URI, factory)

        assertEquals(1, factory.created)
        assertEquals(IMAGE.size.toLong(), probe.totalLength)
        assertEquals(PROBE_SIZE, probe.firstBytes.size)
        assertArrayEquals(IMAGE.copyOfRange(0, PROBE_SIZE), probe.firstBytes)
    }

    @Test
    fun probe_fillsRemainderWhenUpstreamCapsFirstResponse() {
        val factory = FakeFactory(IMAGE, firstCap = CAPPED_BYTES)
        val probe = IsoResolver().probe(URI, factory)

        assertEquals(2, factory.created)
        assertEquals(IMAGE.size.toLong(), probe.totalLength)
        assertEquals(PROBE_SIZE, probe.firstBytes.size)
        assertArrayEquals(IMAGE.copyOfRange(0, PROBE_SIZE), probe.firstBytes)
    }

    @Test
    fun probe_readsTotalFromContentLengthWhenRangeIgnored() {
        val factory = FakeFactory(IMAGE, status = 200)
        val probe = IsoResolver().probe(URI, factory)

        assertEquals(1, factory.created)
        assertEquals(IMAGE.size.toLong(), probe.totalLength)
        assertEquals(PROBE_SIZE, probe.firstBytes.size)
        assertArrayEquals(IMAGE.copyOfRange(0, PROBE_SIZE), probe.firstBytes)
    }

    @Test
    fun probe_fallsBackWhenResponseCarriesNoLengthHeaders() {
        val factory = FakeFactory(IMAGE, status = 200, sendHeaders = false)
        val probe = IsoResolver().probe(URI, factory)

        assertEquals(1, factory.created)
        assertEquals(IMAGE.size.toLong(), probe.totalLength)
        assertEquals(PROBE_SIZE, probe.firstBytes.size)
        assertArrayEquals(IMAGE.copyOfRange(0, PROBE_SIZE), probe.firstBytes)
    }

    @Test
    fun probe_rejectsServerWithoutRangeSupport() {
        val factory = FakeFactory(IMAGE, status = 200, acceptRanges = "none")
        val resolver = IsoResolver()

        val error = runCatching { resolver.probe(URI, factory) }.exceptionOrNull()

        assertEquals("range unsupported", error?.message)
    }

    private class FakeFactory(
        private val image: ByteArray,
        private val status: Int = 206,
        private val firstCap: Int = 0,
        private val acceptRanges: String? = null,
        private val sendHeaders: Boolean = true,
    ) : DataSource.Factory {

        var created = 0
            private set

        override fun createDataSource(): DataSource {
            created++
            return FakeSource(image, status, firstCap, created == 1, acceptRanges, sendHeaders)
        }
    }

    private class FakeSource(
        private val image: ByteArray,
        private val status: Int,
        private val firstCap: Int,
        private val firstRequest: Boolean,
        private val acceptRanges: String?,
        private val sendHeaders: Boolean,
    ) : DataSource {

        private var cursor = 0
        private var remaining = 0
        private var headers: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            cursor = dataSpec.position.toInt()
            val ranged = dataSpec.length != C.LENGTH_UNSET.toLong()
            val requested = if (ranged) dataSpec.length.toInt() else image.size - cursor
            val cap = if (firstRequest && firstCap > 0) firstCap else Int.MAX_VALUE
            remaining = minOf(requested, image.size - cursor, cap)
            headers = buildHeaders(dataSpec.position)
            return if (ranged) requested.toLong() else remaining.toLong()
        }

        private fun buildHeaders(position: Long): Map<String, List<String>> {
            val base = if (acceptRanges != null) mapOf(ACCEPT_RANGES_HEADER to listOf(acceptRanges)) else emptyMap()
            if (status != 206) return base + (CONTENT_LENGTH_HEADER to listOf(image.size.toString()))
            val range = "bytes $position-${position + remaining - 1}/${image.size}"
            return base +
                (CONTENT_RANGE_HEADER to listOf(range)) +
                (CONTENT_LENGTH_HEADER to listOf(remaining.toString()))
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

        override fun getUri(): Uri = URI

        override fun getResponseHeaders(): Map<String, List<String>> = if (sendHeaders) headers else emptyMap()
    }

    private companion object {
        const val PROBE_SIZE = 4096
        const val CAPPED_BYTES = 1024
        val IMAGE: ByteArray = ByteArray(8192) { (it * 31 % 251).toByte() }
        val URI: Uri = Uri.parse("https://iso.test/movie.iso")
    }
}
