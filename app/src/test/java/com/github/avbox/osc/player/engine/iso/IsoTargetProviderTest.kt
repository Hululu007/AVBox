package com.github.avbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IsoTargetProviderTest {

    @Before
    fun clearCache() {
        IsoVolumeCache.clear()
        IsoPlaylistState.reset()
    }

    @Test
    fun repeatedResolve_opensVolumeOnce() {
        val factory = CountingFactory(IMAGE)
        val provider = IsoTargetProvider(factory, IsoResolver(), 0L, {})
        val first = provider.resolve(URI)
        val opened = factory.created
        val second = provider.resolve(URI)

        assertSame(first, second)
        assertEquals("opened=$opened", opened, factory.created)
    }

    @Test
    fun explicitTarget_neverTouchesNetwork() {
        val factory = CountingFactory(IMAGE)
        val explicit = IsoPlayTarget(1024L, 4096L, "00001.m2ts")
        val provider = IsoTargetProvider(factory, IsoResolver(), 0L, {}, explicit)

        assertSame(explicit, provider.resolve(URI))
        assertSame(explicit, provider.resolve(URI))
        assertEquals(0, factory.created)
    }

    @Test
    fun resolve_publishesSelectionToPlaylistState() {
        val provider = IsoTargetProvider(CountingFactory(IMAGE), IsoResolver(), 0L, {})
        provider.resolve(URI)

        assertEquals(1, IsoPlaylistState.segments())
        assertEquals(8192L, IsoPlaylistState.totalBytes())
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
        val IMAGE: ByteArray = UdfImageBuilder(useMetadataPartition = true).image()
        val URI: Uri = Uri.parse("https://iso.test/movie.iso")
    }
}
