package com.github.avbox.osc.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSourceNameTest {

    @Test
    fun pythonAndJsSourcesAreSupported() {
        assertTrue(isLocalSpiderName("91JAV.py"))
        assertTrue(isLocalSpiderName("spider.JS"))
        assertTrue(isLocalSpiderName("摸鱼本地.py"))
    }

    @Test
    fun sourceNameDropsLastExtensionOnly() {
        assertEquals("91JAV", localSourceName("91JAV.py"))
        assertEquals("a.b", localSourceName("a.b.js"))
        assertEquals("noext", localSourceName("noext"))
    }

    @Test
    fun sourceNameKeepsDotFile() {
        assertEquals(".hidden", localSourceName(".hidden"))
    }

    @Test
    fun otherFilesAreRejected() {
        assertFalse(isLocalSpiderName("tvbox源.txt"))
        assertFalse(isLocalSpiderName("config.json"))
        assertFalse(isLocalSpiderName("91JAV.py.md5"))
        assertFalse(isLocalSpiderName("spider.jar"))
        assertFalse(isLocalSpiderName(""))
    }
}
