package com.github.avbox.osc.player.engine.iso

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoAudioFallbackTest {

    @After
    fun clearState() {
        IsoAudioFallback.clear()
    }

    @Test
    fun suppressThenSuppressedMatchesSameKey() {
        IsoAudioFallback.suppress(URL, PID)

        assertTrue(IsoAudioFallback.suppressed(URL, PID))
        assertFalse(IsoAudioFallback.suppressed(URL, PID + 1))
        assertFalse(IsoAudioFallback.suppressed(OTHER_URL, PID))
    }

    @Test
    fun newKeyReplacesPreviousSuppression() {
        IsoAudioFallback.suppress(URL, PID)
        IsoAudioFallback.suppress(OTHER_URL, PID + 1)

        assertFalse(IsoAudioFallback.suppressed(URL, PID))
        assertTrue(IsoAudioFallback.suppressed(OTHER_URL, PID + 1))
    }

    @Test
    fun blankKeyOrInvalidPidIsIgnored() {
        IsoAudioFallback.suppress(null, PID)
        IsoAudioFallback.suppress("", PID)
        IsoAudioFallback.suppress(URL, 0)
        IsoAudioFallback.suppress(URL, -1)
        IsoAudioFallback.suppress(URL, 0x2000)

        assertFalse(IsoAudioFallback.suppressed(URL, PID))
        assertFalse(IsoAudioFallback.suppressed(null, PID))
    }

    @Test
    fun clearDropsSuppression() {
        IsoAudioFallback.suppress(URL, PID)
        IsoAudioFallback.clear()

        assertFalse(IsoAudioFallback.suppressed(URL, PID))
    }

    @Test
    fun pidOfFormatIdParsesTrailingPid() {
        assertEquals(4359, IsoAudioFallback.pidOfFormatId("1/4359"))
        assertEquals(4359, IsoAudioFallback.pidOfFormatId("4359"))
    }

    @Test
    fun pidOfFormatIdRejectsUnusableIds() {
        assertNull(IsoAudioFallback.pidOfFormatId(null))
        assertNull(IsoAudioFallback.pidOfFormatId(""))
        assertNull(IsoAudioFallback.pidOfFormatId("abc"))
        assertNull(IsoAudioFallback.pidOfFormatId("1/0"))
        assertNull(IsoAudioFallback.pidOfFormatId("1/9000"))
    }

    private companion object {
        const val URL = "http://127.0.0.1:5266?url=abc"
        const val OTHER_URL = "http://127.0.0.1:5266?url=def"
        const val PID = 4359
    }
}
