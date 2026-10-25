package com.github.avbox.osc.player.engine.iso

import java.nio.charset.StandardCharsets

internal class UdfImageBuilder(
    private val useMetadataPartition: Boolean,
    private val fragmented: Boolean = false,
    private val withBdmv: Boolean = true,
    private val crossBlockFids: Boolean = false,
    private val hybridRecognition: Boolean = false,
    private val nestedBdmv: Boolean = false,
) {
    private val bytes = ByteArray(BLOCK_COUNT * SECTOR_SIZE)

    private val metadataLayout: Boolean get() = useMetadataPartition && !crossBlockFids

    fun image(): ByteArray {
        build()
        return bytes.copyOf()
    }

    fun reader(): RangeBlockReader {
        val data = image()
        return RangeBlockReader { start, length ->
            if (start < 0 || length <= 0 || start + length > data.size) {
                throw IsoFormatException("test reader range: $start/$length")
            }
            data.copyOfRange(start.toInt(), (start + length).toInt())
        }
    }

    private fun build() {
        writeRecognition()
        writeAnchor()
        writeVolumeDescriptors()
        if (useMetadataPartition && !crossBlockFids && !nestedBdmv) {
            writeMetadataLayout()
        } else {
            writePhysicalLayout()
        }
        writeFileData()
    }

    private fun writeRecognition() {
        if (hybridRecognition) {
            putByte(16, 0, 1)
            writeAscii(16, 1, "CD001")
            putByte(16, 6, 1)
            putByte(17, 0, 0xFF)
            writeAscii(17, 1, "CD001")
            putByte(17, 6, 1)
            writeAscii(18, 1, "BEA01")
            writeAscii(19, 1, "NSR02")
            writeAscii(20, 1, "TEA01")
        } else {
            writeAscii(16, 1, "BEA01")
            writeAscii(17, 1, "NSR03")
            writeAscii(18, 1, "TEA01")
        }
    }

    private fun writeAnchor() {
        putTag(256, 0, TAG_ANCHOR)
        putLe32(256, 16, 16L * SECTOR_SIZE)
        putLe32(256, 20, 32L)
    }

    private fun writeVolumeDescriptors() {
        putTag(32, 0, TAG_PRIMARY_VOLUME)
        putTag(33, 0, TAG_PARTITION)
        putLe16(33, 22, 0)
        putLe32(33, 188, PARTITION_START)
        putLe32(33, 192, 64)
        putTag(34, 0, TAG_LOGICAL_VOLUME)
        putLe32(34, 212, SECTOR_SIZE.toLong())
        putLe32(34, 248, SECTOR_SIZE.toLong())
        putLe32(34, 252, 0)
        putLe16(34, 256, if (metadataLayout) 1 else 0)
        putLe32(34, 268, if (metadataLayout) 2L else 1L)
        putByte(34, PARTITION_MAP_START, PARTITION_MAP_PHYSICAL)
        putByte(34, PARTITION_MAP_START + 1, 6)
        putLe16(34, PARTITION_MAP_START + 2, 1)
        putLe16(34, PARTITION_MAP_START + 4, 0)
        if (metadataLayout) {
            writeMetadataMap(34, PARTITION_MAP_START + 6)
        }
        putTag(35, 0, TAG_TERMINATING)
    }

    private fun writeMetadataMap(block: Int, offset: Int) {
        putByte(block, offset, PARTITION_MAP_METADATA)
        putByte(block, offset + 1, 64)
        putByte(block, offset + 4, 0)
        writeAscii(block, offset + 5, METADATA_IDENTIFIER)
        putLe16(block, offset + 36, 1)
        putLe16(block, offset + 38, 0)
        putLe32(block, offset + 40, 0)
        putLe32(block, offset + 52, 1)
        putLe16(block, offset + 56, 1)
    }

    private fun writePhysicalLayout() {
        if (nestedBdmv) {
            writeNestedPhysicalLayout()
            return
        }
        writeFileSet(300, 1, 0)
        writeFileEntry(301, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(2, SECTOR_SIZE.toLong()))
        writeEntryFids(
            block = 302,
            children = listOf(Fid("BDMV", 3, 0, true)),
        )
        writeFileEntry(303, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(4, SECTOR_SIZE.toLong()))
        writeEntryFids(
            block = 304,
            children = if (withBdmv) listOf(Fid("STREAM", 5, 0, true)) else emptyList(),
        )
        val streamChildren = if (crossBlockFids) {
            crossingFids()
        } else {
            listOf(
                Fid("00000.m2ts", 7, 0, false),
                Fid("00001.m2ts", 8, 0, false),
            )
        }
        val streamLength = if (crossBlockFids) {
            streamChildren.sumOf { fidLength(it) }.toLong()
        } else {
            SECTOR_SIZE.toLong()
        }
        val streamDataBlock = if (crossBlockFids) CROSSING_FID_BLOCK - PARTITION_START.toInt() else 6
        writeFileEntry(305, FILE_TYPE_DIRECTORY, streamLength, ICB_AD_SHORT, shortAd(streamDataBlock, streamLength))
        writeEntryFids(
            block = if (crossBlockFids) CROSSING_FID_BLOCK else 306,
            children = streamChildren,
        )
        writeFileEntry(307, FILE_TYPE_REGULAR, FIRST_FILE_SIZE, ICB_AD_SHORT, shortAd(30, FIRST_FILE_SIZE))
        writeFileEntry(308, FILE_TYPE_REGULAR, SECOND_FILE_SIZE, ICB_AD_LONG, secondFileAd())
    }

    private fun writeNestedPhysicalLayout() {
        writeFileSet(300, 1, 0)
        writeFileEntry(301, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(2, SECTOR_SIZE.toLong()))
        writeEntryFids(block = 302, children = listOf(Fid("MOVIE", 3, 0, true)))
        writeFileEntry(303, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(4, SECTOR_SIZE.toLong()))
        writeEntryFids(
            block = 304,
            children = if (withBdmv) listOf(Fid("BDMV", 5, 0, true)) else emptyList(),
        )
        writeFileEntry(305, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(6, SECTOR_SIZE.toLong()))
        writeEntryFids(block = 306, children = listOf(Fid("STREAM", 7, 0, true)))
        writeFileEntry(307, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(8, SECTOR_SIZE.toLong()))
        writeEntryFids(
            block = 308,
            children = listOf(Fid("00000.m2ts", 9, 0, false), Fid("00001.m2ts", 10, 0, false)),
        )
        writeFileEntry(309, FILE_TYPE_REGULAR, FIRST_FILE_SIZE, ICB_AD_SHORT, shortAd(30, FIRST_FILE_SIZE))
        writeFileEntry(310, FILE_TYPE_REGULAR, SECOND_FILE_SIZE, ICB_AD_LONG, secondFileAd())
    }

    private fun crossingFids(): List<Fid> =
        (0 until CROSSING_FID_CHILDREN).map { index ->
            Fid("000%02d.m2ts".format(index), if (index % 2 == 0) 7 else 8, 0, false)
        }

    private fun writeMetadataLayout() {
        writeExtendedFileEntry(
            block = 300,
            fileType = FILE_TYPE_REGULAR,
            infoLength = METADATA_FILE_BLOCKS * SECTOR_SIZE.toLong(),
            adType = ICB_AD_SHORT,
            descriptors = shortAd(2, METADATA_FILE_BLOCKS * SECTOR_SIZE.toLong()),
        )
        writeFileSet(302, 1, 1)
        writeFileEntry(303, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(2, SECTOR_SIZE.toLong()))
        writeEntryFids(block = 304, children = listOf(Fid("BDMV", 3, 1, true)))
        writeFileEntry(305, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(4, SECTOR_SIZE.toLong()))
        writeEntryFids(
            block = 306,
            children = if (withBdmv) listOf(Fid("STREAM", 5, 1, true)) else emptyList(),
        )
        writeFileEntry(307, FILE_TYPE_DIRECTORY, SECTOR_SIZE.toLong(), ICB_AD_SHORT, shortAd(6, SECTOR_SIZE.toLong()))
        writeEntryFids(
            block = 308,
            children = listOf(
                Fid("00000.m2ts", 7, 1, false),
                Fid("00001.m2ts", 8, 1, false),
            ),
        )
        writeFileEntry(309, FILE_TYPE_REGULAR, FIRST_FILE_SIZE, ICB_AD_LONG, longAd(30, FIRST_FILE_SIZE, 0))
        writeFileEntry(310, FILE_TYPE_REGULAR, SECOND_FILE_SIZE, ICB_AD_LONG, secondFileAd())
    }

    private fun secondFileAd(): ByteArray =
        if (fragmented) {
            longAd(32, HALF_FILE_SIZE, 0) + longAd(36, HALF_FILE_SIZE, 0)
        } else {
            longAd(32, HALF_FILE_SIZE, 0) + longAd(34, HALF_FILE_SIZE, 0)
        }

    private fun writeFileData() {
        fill(FIRST_DATA_BLOCK, FIRST_FILE_SIZE.toInt(), 0x5A)
        fill(SECOND_DATA_BLOCK, SECOND_FILE_SIZE.toInt(), 0x6B)
    }

    private fun writeFileSet(block: Int, rootBlock: Long, rootPartitionRef: Int) {
        putTag(block, 0, TAG_FILE_SET)
        putLe32(block, 400, SECTOR_SIZE.toLong())
        putLe32(block, 404, rootBlock)
        putLe16(block, 408, rootPartitionRef)
    }

    private fun writeFileEntry(
        block: Int,
        fileType: Int,
        infoLength: Long,
        adType: Int,
        descriptors: ByteArray,
    ) {
        putTag(block, 0, TAG_FILE_ENTRY)
        putLe16(block, 20, 4)
        putLe16(block, 24, 1)
        putByte(block, 27, fileType)
        putLe16(block, 34, adType)
        putLe64(block, 56, infoLength)
        putLe64(block, 64, infoLength)
        putLe32(block, 168, 0)
        putLe32(block, 172, descriptors.size.toLong())
        put(block, 176, descriptors)
    }

    private fun writeExtendedFileEntry(
        block: Int,
        fileType: Int,
        infoLength: Long,
        adType: Int,
        descriptors: ByteArray,
    ) {
        putTag(block, 0, TAG_EXTENDED_FILE_ENTRY)
        putLe16(block, 20, 4)
        putLe16(block, 24, 1)
        putByte(block, 27, fileType)
        putLe16(block, 34, adType)
        putLe64(block, 56, infoLength)
        putLe64(block, 64, infoLength)
        putLe64(block, 72, infoLength / SECTOR_SIZE)
        putLe32(block, 208, 0)
        putLe32(block, 212, descriptors.size.toLong())
        put(block, 216, descriptors)
    }

    private fun writeEntryFids(block: Int, children: List<Fid>) {
        var offset = 0
        for (child in children) {
            writeFid(block, offset, child)
            offset += fidLength(child)
        }
    }

    private fun fidLength(child: Fid): Int = align4(FID_HEADER_SIZE + child.name.length + 1)

    private fun writeFid(block: Int, offset: Int, child: Fid) {
        val name = dstring(child.name)
        putTag(block, offset, TAG_FILE_IDENTIFIER)
        putLe16(block, offset + 16, 1)
        putByte(block, offset + 18, if (child.directory) FID_DIRECTORY else 0)
        putByte(block, offset + 19, name.size)
        putLe32(block, offset + 20, SECTOR_SIZE.toLong())
        putLe32(block, offset + 24, child.block.toLong())
        putLe16(block, offset + 28, child.partitionRef)
        putLe16(block, offset + 36, 0)
        put(block, offset + FID_HEADER_SIZE, name)
    }

    private fun shortAd(block: Int, length: Long): ByteArray {
        val bytes = ByteArray(8)
        writeLe32(bytes, 0, length)
        writeLe32(bytes, 4, block.toLong())
        return bytes
    }

    private fun longAd(block: Int, length: Long, partitionRef: Int): ByteArray {
        val bytes = ByteArray(16)
        writeLe32(bytes, 0, length)
        writeLe32(bytes, 4, block.toLong())
        writeLe16(bytes, 8, partitionRef)
        return bytes
    }

    private fun dstring(value: String): ByteArray {
        val bytes = ByteArray(value.length + 1)
        bytes[0] = 8
        for (index in value.indices) {
            bytes[index + 1] = value[index].code.toByte()
        }
        return bytes
    }

    private fun fill(startBlock: Int, length: Int, value: Int) {
        val start = startBlock * SECTOR_SIZE
        for (index in 0 until length) {
            bytes[start + index] = value.toByte()
        }
    }

    private fun putTag(block: Int, offset: Int, identifier: Int) {
        putLe16(block, offset, identifier)
        putLe16(block, offset + 2, 3)
        putLe32(block, offset + 13, block.toLong())
    }

    private fun writeAscii(block: Int, offset: Int, value: String) {
        put(block, offset, value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun put(block: Int, offset: Int, value: ByteArray) {
        System.arraycopy(value, 0, bytes, block * SECTOR_SIZE + offset, value.size)
    }

    private fun putByte(block: Int, offset: Int, value: Int) {
        bytes[block * SECTOR_SIZE + offset] = value.toByte()
    }

    private fun putLe16(block: Int, offset: Int, value: Int) {
        val position = block * SECTOR_SIZE + offset
        bytes[position] = value.toByte()
        bytes[position + 1] = (value shr 8).toByte()
    }

    private fun putLe32(block: Int, offset: Int, value: Long) {
        writeLe32(bytes, block * SECTOR_SIZE + offset, value)
    }

    private fun putLe64(block: Int, offset: Int, value: Long) {
        for (index in 0 until 8) {
            bytes[block * SECTOR_SIZE + offset + index] = (value shr (index * 8)).toByte()
        }
    }

    private fun writeLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
    }

    private fun writeLe32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
        target[offset + 2] = (value shr 16).toByte()
        target[offset + 3] = (value shr 24).toByte()
    }

    private class Fid(
        val name: String,
        val block: Int,
        val partitionRef: Int,
        val directory: Boolean,
    )

    private companion object {
        const val BLOCK_COUNT = 400
        const val PARTITION_START = 300L
        const val METADATA_FILE_BLOCKS = 16
        const val FIRST_FILE_SIZE = 1921L
        const val SECOND_FILE_SIZE = 8192L
        const val HALF_FILE_SIZE = 4096L
        const val FIRST_DATA_BLOCK = 330
        const val SECOND_DATA_BLOCK = 332
        const val CROSSING_FID_BLOCK = 340
    }
}

internal const val CROSSING_FID_CHILDREN = 50
