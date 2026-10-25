package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgUtilParseTest {

    @Test
    fun commaSeparatedNamesShareOneEntry() {
        val map = EpgUtil.parse("""{"epgs":[{"name":"CCTV-1 综合,CCTV1","logo":"logo.png","epgid":"1"}]}""")
        assertEquals(setOf("CCTV-1 综合", "CCTV1"), map.keys)
        assertEquals("1", requireNotNull(map["CCTV1"]).get("epgid").asString)
    }

    @Test
    fun nameIsTrimmedBeforeSplit() {
        val map = EpgUtil.parse("""{"epgs":[{"name":"  CCTV-2  ","logo":"l","epgid":"2"}]}""")
        assertTrue(map.containsKey("CCTV-2"))
    }

    @Test
    fun splitSegmentsKeepInnerWhitespace() {
        val map = EpgUtil.parse("""{"epgs":[{"name":"CCTV-2 , CCTV2","logo":"l","epgid":"2"}]}""")
        assertTrue(map.containsKey("CCTV-2 "))
        assertTrue(map.containsKey(" CCTV2"))
    }

    @Test
    fun epgInfoIsNullWhileEpgNotLoaded() {
        assertNull(EpgUtil.getEpgInfo("CCTV-1"))
    }
}
