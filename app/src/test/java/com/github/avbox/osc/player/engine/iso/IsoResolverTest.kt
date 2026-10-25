package com.github.avbox.osc.player.engine.iso

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class IsoResolverTest {

    @Before
    fun clearVolumeCache() {
        IsoVolumeCache.clear()
        IsoPlaylistState.reset()
    }

    @Test
    fun fingerprint_usesLengthAndPrefix() {
        val first = isoFingerprint(1024, byteArrayOf(1, 2, 3))
        assertEquals(first, isoFingerprint(1024, byteArrayOf(1, 2, 3)))
        assertNotEquals(first, isoFingerprint(1025, byteArrayOf(1, 2, 3)))
        assertNotEquals(first, isoFingerprint(1024, byteArrayOf(1, 2, 4)))
    }

    @Test
    fun resolve_reusesVolumeForSameFingerprint() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val probe = probe(image)
        val resolver = IsoResolver()
        val first = resolver.resolve(probe, reader(image, AtomicInteger()))
        val second = resolver.resolve(probe, RangeBlockReader { _, _ -> error("cache miss") })
        assertEquals(first.selection, second.selection)
    }

    @Test
    fun resolve_singleFlightForConcurrentCallers() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val probe = probe(image)
        val baselineReads = AtomicInteger()
        IsoResolver().resolve(probe, reader(image, baselineReads))
        IsoVolumeCache.clear()
        val reads = AtomicInteger()
        val resolver = IsoResolver()
        val start = CountDownLatch(1)
        val threads = (1..4).map {
            Thread {
                start.await()
                resolver.resolve(probe, reader(image, reads))
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(baselineReads.get(), reads.get())
    }

    @Test
    fun resolve_reportsBrokenImage() {
        val probe = IsoProbe(IMAGE_SIZE, ByteArray(4096))
        val resolver = IsoResolver()
        assertThrows(IsoFormatException::class.java) {
            resolver.resolve(probe, RangeBlockReader { _, length -> ByteArray(length) })
        }
    }

    @Test
    fun resolve_republishesStreamLanguagesOnCacheHit() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val probe = probe(image)
        val resolver = IsoResolver()
        val first = resolver.resolve(probe, reader(image, AtomicInteger()))
        val key = isoFingerprint(probe.totalLength, probe.firstBytes)
        IsoVolumeCache.put(
            key,
            first.copy(
                streams = listOf(IsoStream(4768, "eng"), IsoStream(4770, "zho")),
                videoPid = 4113,
            ),
        )
        IsoPlaylistState.reset()
        resolver.resolve(probe, RangeBlockReader { _, _ -> error("cache miss") })

        assertEquals("zho", IsoPlaylistState.languageForPid(4770))
        assertEquals("eng", IsoPlaylistState.languageForPid(4768))
        assertEquals(4113, IsoPlaylistState.videoPid())
    }

    private fun probe(image: ByteArray) =
        IsoProbe(image.size.toLong(), image.copyOfRange(0, PROBE_SIZE))

    private fun reader(image: ByteArray, reads: AtomicInteger) =
        RangeBlockReader { start, length ->
            if (!Thread.currentThread().name.startsWith(PREPARE_THREAD)) reads.incrementAndGet()
            image.copyOfRange(start.toInt(), (start + length).toInt())
        }

    private companion object {
        const val PROBE_SIZE = 4096
        const val IMAGE_SIZE = 400L * SECTOR_SIZE
        const val PREPARE_THREAD = "iso-prepare"
    }
}
