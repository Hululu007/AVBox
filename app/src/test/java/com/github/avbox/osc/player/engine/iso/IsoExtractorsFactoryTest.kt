package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoExtractorsFactoryTest {

    @Test
    fun createsSingleWrappedExtractor() {
        val extractors = IsoExtractorsFactory().createExtractors()
        assertEquals(1, extractors.size)
        assertTrue(extractors[0] is PrimaryVideoExtractor)
    }

    @Test
    fun eachCallCreatesFreshExtractor() {
        val factory = IsoExtractorsFactory()
        val first = factory.createExtractors()[0]
        val second = factory.createExtractors()[0]
        assertNotSame(first, second)
    }

    @Test
    fun customPayloadReaderFactory_isAccepted() {
        val extractors = IsoExtractorsFactory(BdTsPayloadReaderFactory()).createExtractors()
        assertEquals(1, extractors.size)
    }
}
