package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoUdfEdgeCaseTest {

    @Test
    fun rejectsVirtualPartitionMap() {
        val image = UdfImageBuilder(useMetadataPartition = false).image()
        image[34 * SECTOR_SIZE + PARTITION_MAP_START] = 3

        assertTrue(openError(image).contains("unsupported partition map"))
    }

    @Test
    fun rejectsNonSectorBlockSize() {
        val image = UdfImageBuilder(useMetadataPartition = false).image()
        putLe32(image, 34 * SECTOR_SIZE + LVD_BLOCK_SIZE_OFFSET, 1024)

        assertTrue(openError(image).contains("unsupported block size"))
    }

    @Test
    fun rejectsExtendedAllocationType() {
        val image = UdfImageBuilder(useMetadataPartition = false).image()
        putLe16(image, 301 * SECTOR_SIZE + ICB_FLAGS_OFFSET, 2)

        assertTrue(openError(image).contains("unsupported allocation type"))
    }

    @Test
    fun rejectsSparseExtents() {
        val image = UdfImageBuilder(useMetadataPartition = false).image()
        putLe32(image, 305 * SECTOR_SIZE + FILE_ENTRY_DATA_OFFSET, SPARSE_FLAG or SECTOR_SIZE.toLong())

        val volume = UdfVolume.open(reader(image), image.size.toLong())
        val bdmv = requireNotNull(volume.find(volume.root(), "BDMV")) { "BDMV missing in fixture" }
        val error = assertThrows(IsoFormatException::class.java) { volume.list(bdmv) }
        assertTrue(error.message.orEmpty().contains("sparse extent"))
    }

    @Test
    fun reportsMissingStreamDirectory() {
        val image = UdfImageBuilder(useMetadataPartition = false, withBdmv = false).image()
        val volume = UdfVolume.open(reader(image), image.size.toLong())

        val error = assertThrows(IsoFormatException::class.java) { IsoLocator(volume).locateSelection() }

        assertEquals("BDMV/STREAM directory missing", error.message)
    }

    @Test
    fun findsBdmvInsideWrapperDirectory() {
        val image = UdfImageBuilder(useMetadataPartition = false, nestedBdmv = true).image()
        val volume = UdfVolume.open(reader(image), image.size.toLong())

        val selection = IsoLocator(volume).locateSelection()

        assertEquals(1, selection.segments.size)
    }

    @Test
    fun stillReportsMissingBdmvInsideWrapperDirectory() {
        val image = UdfImageBuilder(useMetadataPartition = false, withBdmv = false, nestedBdmv = true).image()
        val volume = UdfVolume.open(reader(image), image.size.toLong())

        val error = assertThrows(IsoFormatException::class.java) { IsoLocator(volume).locateSelection() }

        assertEquals("BDMV directory missing", error.message)
    }

    private fun openError(image: ByteArray): String {
        val error = assertThrows(IsoFormatException::class.java) {
            UdfVolume.open(reader(image), image.size.toLong())
        }
        return error.message.orEmpty()
    }

    private fun reader(image: ByteArray): RangeBlockReader =
        RangeBlockReader { start, length ->
            if (start < 0 || length <= 0 || start + length > image.size) {
                throw IsoFormatException("test reader range: $start/$length")
            }
            image.copyOfRange(start.toInt(), (start + length).toInt())
        }

    private fun putLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
    }

    private fun putLe32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = (value shr (index * 8)).toByte()
        }
    }

    private companion object {
        const val SPARSE_FLAG = 0x40000000L
    }
}
