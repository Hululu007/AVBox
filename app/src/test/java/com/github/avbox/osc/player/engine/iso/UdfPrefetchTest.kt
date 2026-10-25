package com.github.avbox.osc.player.engine.iso

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdfPrefetchTest {

    @Test
    fun prefetchedRangeServesLaterReadWithoutRequest() {
        val image = ByteArray(IMAGE_SIZE) { (it and 0xFF).toByte() }
        val reads = AtomicInteger()
        val parser = UdfParser(counting(image, reads), SECTOR_SIZE, image.size.toLong(), HashMap())

        assertTrue(parser.prefetch(OFFSET, LENGTH))
        assertEquals(1, reads.get())
        val data = parser.readRange(OFFSET, SECTOR_SIZE)

        assertEquals(1, reads.get())
        assertEquals(image[OFFSET.toInt()].toInt() and 0xFF, data[0].toInt() and 0xFF)
        assertEquals(1, parser.readRequests())
    }

    @Test
    fun prefetchedSpanAvoidsSecondWindowRequest() {
        val image = ByteArray(IMAGE_SIZE) { 1 }
        val reads = AtomicInteger()
        val parser = UdfParser(counting(image, reads), SECTOR_SIZE, image.size.toLong(), HashMap())
        val tail = SPAN - SECTOR_SIZE

        parser.prefetch(0L, SPAN)
        parser.readRange(0L, SECTOR_SIZE)
        parser.readRange(tail.toLong(), SECTOR_SIZE)

        assertEquals(1, reads.get())
    }

    @Test
    fun prefetchFailurePropagates() {
        val parser = UdfParser(
            RangeBlockReader { _, _ -> throw IsoFormatException("boom") },
            SECTOR_SIZE,
            IMAGE_SIZE.toLong(),
            HashMap(),
        )

        assertThrows(IsoFormatException::class.java) { parser.prefetch(0L, SECTOR_SIZE) }
    }

    private fun counting(image: ByteArray, reads: AtomicInteger): RangeBlockReader =
        RangeBlockReader { start, length ->
            reads.incrementAndGet()
            image.copyOfRange(start.toInt(), (start + length).toInt())
        }

    private companion object {
        const val IMAGE_SIZE = 512 * 1024
        const val OFFSET = 8L * 1024L
        const val LENGTH = 4 * 1024
        const val SPAN = 256 * 1024
    }
}
