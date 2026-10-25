package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClpiIndexTest {

    @Test
    fun parsesCoarseAndFineEntries() {
        val data = clpi(
            points = listOf(
                Point(finePts = 0, fineSpn = 0, ptsHigh = 720, spnHigh = 0, angle = false),
                Point(finePts = 100, fineSpn = 50, ptsHigh = 720, spnHigh = 0, angle = false),
                Point(finePts = 200, fineSpn = 120, ptsHigh = 721, spnHigh = 0, angle = true),
            ),
            sourcePackets = 1000,
        )
        val index = requireNotNull(parseClpi("00016.clpi", data))
        assertEquals("00016.clpi", index.clipName)
        assertEquals(1000L, index.sourcePackets)
        assertEquals(3, index.accessPoints.size)

        assertEquals(720L shl 18, index.accessPoints[0].ptsTicks)
        assertEquals(720L shl 18 or (100L shl 8), index.accessPoints[1].ptsTicks)
        assertEquals((720L shl 18) or (200L shl 8), index.accessPoints[2].ptsTicks)
        assertEquals(50L, index.accessPoints[1].spn)
        assertEquals(120L * SOURCE_PACKET_SIZE, index.accessPoints[2].packetOffset)
        assertEquals(120L * SOURCE_PACKET_SIZE + SOURCE_PACKET_HEADER, index.accessPoints[2].syncOffset)
        assertTrue(index.accessPoints[2].angleChange)
        assertTrue(!index.accessPoints[0].angleChange)
    }

    @Test
    fun accessPointAtPicksLastNotAfterTarget() {
        val data = clpi(
            points = listOf(
                Point(0, 0, 720, 0, false),
                Point(100, 50, 720, 0, false),
                Point(200, 120, 721, 0, false),
            ),
            sourcePackets = 1000,
        )
        val index = requireNotNull(parseClpi("00016.clpi", data))
        assertNull(index.accessPointAt(0))
        assertEquals(index.accessPoints[0], index.accessPointAt(720L shl 18))
        assertEquals(index.accessPoints[1], index.accessPointAt((720L shl 18) + (100L shl 8)))
        assertEquals(index.accessPoints[1], index.accessPointAt((720L shl 18) + (100L shl 8) + 12_345))
        assertEquals(index.accessPoints[2], index.accessPointAt(Long.MAX_VALUE / 2))
        assertEquals(25_600L, index.minGapTicks())
        assertEquals(25_600L, index.maxGapTicks())
    }

    @Test
    fun rejectsNonHdmvSignature() {
        val data = clpi(listOf(Point(0, 0, 720, 0, false)), 100)
        data[0] = 'X'.code.toByte()
        assertNull(parseClpi("00016.clpi", data))
    }

    @Test
    fun rejectsCpiStartOutOfRange() {
        val data = clpi(listOf(Point(0, 0, 720, 0, false)), 100)
        putBe32(data, 16, 60_000)
        assertNull(parseClpi("00016.clpi", data))
    }

    @Test
    fun rejectsEmptyIndex() {
        assertNull(parseClpi("00016.clpi", ByteArray(8)))
    }

    private data class Point(
        val finePts: Int,
        val fineSpn: Long,
        val ptsHigh: Long,
        val spnHigh: Long,
        val angle: Boolean,
    )

    private fun clpi(points: List<Point>, sourcePackets: Long): ByteArray {
        val streamStart = 124
        val fineStart = 4 + points.size * 8
        val coarseBase = streamStart + 4
        val fineBase = coarseBase + points.size * 8
        val data = ByteArray(fineBase + points.size * 4 + 16)
        "HDMV".forEachIndexed { index, c -> data[index] = c.code.toByte() }
        data[4] = '0'.code.toByte()
        data[5] = '2'.code.toByte()
        data[6] = '0'.code.toByte()
        data[7] = '0'.code.toByte()
        putBe32(data, 16, 100)
        putBe32(data, 56, sourcePackets)
        putBe32(data, 100, 64)
        val bits = BitWriter(data, (100 + 4) * 8)
        bits.write(12, 0)
        bits.write(4, 1)
        bits.write(8, 1)
        bits.write(8, 1)
        bits.write(16, 0x1011)
        bits.write(10, 0)
        bits.write(4, 1)
        bits.write(16, points.size.toLong())
        bits.write(18, points.size.toLong())
        bits.write(32, (streamStart - EP_MAP_POS).toLong())
        putBe32(data, streamStart, fineStart.toLong())
        points.forEachIndexed { index, point ->
            val offset = coarseBase + index * 8
            val packed = (index.toLong() shl 14) or point.ptsHigh
            putBe32(data, offset, packed)
            putBe32(data, offset + 4, point.spnHigh)
        }
        points.forEachIndexed { index, point ->
            val offset = fineBase + index * 4
            var value = 0L
            if (point.angle) value = value or (1L shl 31)
            value = value or ((point.finePts.toLong() and 0x7FF) shl 17)
            value = value or (point.fineSpn and 0x1FFFF)
            putBe32(data, offset, value)
        }
        return data
    }

    private companion object {
        const val EP_MAP_POS = 106
    }

    private class BitWriter(private val data: ByteArray, private var bit: Int) {

        fun write(count: Int, value: Long) {
            for (index in count - 1 downTo 0) {
                val one = ((value shr index) and 1L) != 0L
                if (one) {
                    val position = bit / 8
                    data[position] = (data[position].toInt() or (1 shl (7 - (bit % 8)))).toByte()
                }
                bit++
            }
        }
    }

    private fun putBe32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value shr ((3 - index) * 8)) and 0xFF).toByte()
        }
    }
}
