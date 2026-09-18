package com.github.tvbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIndexFlagsTest {

    @Test
    fun parsesIndexMarkedSites() {
        val json = """
            {"sites":[
              {"key":"free","name":"免费分享","type":3,"api":"csp_A","indexs":1},
              {"key":"wanou","name":"玩偶4K","type":3,"api":"csp_B"},
              {"key":"cfg","name":"配置中心","type":1,"api":"http://x","indexs":1},
              {"key":"zero","name":"某站","type":1,"api":"http://y","indexs":0}
            ]}
        """.trimIndent()
        val keys = SourceIndexFlags.parseIndexKeys(json)
        assertTrue(keys.contains("free"))
        assertTrue(keys.contains("cfg"))
        assertFalse(keys.contains("wanou"))
        assertFalse(keys.contains("zero"))
    }

    @Test
    fun malformedConfigFallsBackToEmpty() {
        assertTrue(SourceIndexFlags.parseIndexKeys("not-json").isEmpty())
        assertTrue(SourceIndexFlags.parseIndexKeys("{}").isEmpty())
        assertTrue(SourceIndexFlags.parseIndexKeys("""{"sites":"oops"}""").isEmpty())
    }
}
