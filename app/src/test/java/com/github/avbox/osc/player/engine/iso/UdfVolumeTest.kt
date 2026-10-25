package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UdfVolumeTest {

    @Test
    fun physicalPartition_listsStreamAndReadsFileData() {
        val volume = open(useMetadataPartition = false)
        val bdmv = requireNotNull(volume.find(volume.root(), "BDMV")) { "BDMV missing" }
        assertTrue(bdmv.directory)
        val stream = requireNotNull(volume.find(bdmv, "STREAM")) { "STREAM missing" }
        val files = volume.list(stream)
        assertEquals(listOf("00000.m2ts", "00001.m2ts"), files.map { it.name })
        assertEquals(1921L, files[0].length)
        assertEquals(8192L, files[1].length)
        val data = volume.readEntry(files[0])
        assertEquals(1921, data.size)
        assertEquals(0x5A, data[0].toInt() and 0xFF)
    }

    @Test
    fun metadataPartition_resolvesFileEntriesAndData() {
        val volume = open(useMetadataPartition = true)
        val bdmv = requireNotNull(volume.find(volume.root(), "BDMV")) { "BDMV missing" }
        val stream = requireNotNull(volume.find(bdmv, "STREAM")) { "STREAM missing" }
        val files = volume.list(stream)
        assertEquals(listOf("00000.m2ts", "00001.m2ts"), files.map { it.name })
        val data = volume.readEntry(files[1])
        assertEquals(8192, data.size)
        assertEquals(0x6B, data[1].toInt() and 0xFF)
    }

    @Test
    fun locator_picksLargestAndJoinsContiguousExtents() {
        val volume = open(useMetadataPartition = true)
        val target = IsoLocator(volume).locate()
        assertEquals("00001.m2ts", target.name)
        assertEquals(332L * SECTOR_SIZE, target.m2tsOffset)
        assertEquals(8192L, target.m2tsLength)
    }

    @Test
    fun locator_acceptsFragmentedFileAsRuns() {
        val volume = UdfVolume.open(
            UdfImageBuilder(useMetadataPartition = true, fragmented = true).reader(),
            IMAGE_SIZE,
        )
        val target = IsoLocator(volume).locate()
        assertEquals("00001.m2ts", target.name)
        assertEquals(2, target.runs.size)
        assertEquals(target.m2tsLength, target.runs.sumOf { it.length })
        assertEquals(target.m2tsLength / 2, target.runs[0].length)
        assertEquals(4L * SECTOR_SIZE, target.runs[1].offset - target.runs[0].offset)
    }

    @Test
    fun locator_rejectsImageWithoutBdmv() {
        val volume = UdfVolume.open(
            UdfImageBuilder(useMetadataPartition = false, withBdmv = false).reader(),
            IMAGE_SIZE,
        )
        assertThrows(IsoFormatException::class.java) { IsoLocator(volume).locate() }
    }

    @Test
    fun open_acceptsIso9660HybridRecognition() {
        val logs = mutableListOf<String>()
        val volume = UdfVolume.open(
            UdfImageBuilder(useMetadataPartition = true, hybridRecognition = true).reader(),
            IMAGE_SIZE,
        ) { logs.add(it) }
        val bdmv = requireNotNull(volume.find(volume.root(), "BDMV")) { "BDMV missing" }
        val stream = requireNotNull(volume.find(bdmv, "STREAM")) { "STREAM missing" }
        val files = volume.list(stream)

        assertTrue("logs=$logs", logs.contains("vrs:NSR02"))
        assertEquals(listOf("00000.m2ts", "00001.m2ts"), files.map { it.name })
        assertEquals(1921L, files[0].length)
    }

    @Test
    fun open_rejectsNonUdfImage() {
        val reader = RangeBlockReader { _, length -> ByteArray(length) }
        assertThrows(IsoFormatException::class.java) { UdfVolume.open(reader, IMAGE_SIZE) }
    }

    @Test
    fun open_rejectsShortRead() {
        val reader = RangeBlockReader { _, _ -> ByteArray(1) }
        assertThrows(IsoFormatException::class.java) { UdfVolume.open(reader, IMAGE_SIZE) }
    }

    @Test
    fun list_reusesPrefetchedWindow() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val reads = AtomicInteger()
        val volume = UdfVolume.open(counting(image, reads), IMAGE_SIZE)
        val stream = requireNotNull(
            volume.find(requireNotNull(volume.find(volume.root(), "BDMV")), "STREAM"),
        )
        reads.set(0)
        val files = volume.list(stream)

        assertEquals(listOf("00000.m2ts", "00001.m2ts"), files.map { it.name })
        assertEquals(1921L, files[0].length)
        assertEquals(8192L, files[1].length)
        assertTrue("reads=${reads.get()}", reads.get() <= 2)
    }

    @Test
    fun metadataPrefetch_servesDirectoryTraversalWithoutExtraReads() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val reads = AtomicInteger()
        val volume = UdfVolume.open(counting(image, reads), IMAGE_SIZE)
        val bdmv = requireNotNull(volume.find(volume.root(), "BDMV"))
        reads.set(0)
        val stream = requireNotNull(volume.find(bdmv, "STREAM"))
        val files = volume.list(stream)
        val traversalReads = reads.get()
        val payload = volume.readEntry(files[0])
        val payloadReads = reads.get() - traversalReads

        assertEquals(listOf("00000.m2ts", "00001.m2ts"), files.map { it.name })
        assertEquals(1921, payload.size)
        assertEquals("traversal=${traversalReads}", 0, traversalReads)
        assertTrue("payload=${payloadReads}", payloadReads <= 1)
    }

    @Test
    fun list_keepsFidsThatCrossBlockBoundaries() {
        val volume = UdfVolume.open(
            UdfImageBuilder(useMetadataPartition = false, crossBlockFids = true).reader(),
            IMAGE_SIZE,
        )
        val bdmv = requireNotNull(volume.find(volume.root(), "BDMV")) { "BDMV missing" }
        val stream = requireNotNull(volume.find(bdmv, "STREAM")) { "STREAM missing" }
        val files = volume.list(stream)

        assertEquals(CROSSING_FID_CHILDREN, files.size)
        assertEquals("00000.m2ts", files.first().name)
        assertEquals("00049.m2ts", files.last().name)
        assertEquals(1921L, files.first().length)
        assertEquals(8192L, files[1].length)
    }

    private fun counting(image: ByteArray, reads: AtomicInteger): RangeBlockReader =
        RangeBlockReader { start, length ->
            reads.incrementAndGet()
            image.copyOfRange(start.toInt(), (start + length).toInt())
        }

    private fun open(useMetadataPartition: Boolean): UdfVolume =
        UdfVolume.open(
            UdfImageBuilder(useMetadataPartition = useMetadataPartition).reader(),
            IMAGE_SIZE,
        )

    private companion object {
        const val IMAGE_SIZE = 400L * SECTOR_SIZE
    }
}
