package com.github.avbox.osc.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileUtilsNoMediaMarkerTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun createsMarkerInExistingDirectory() {
        val dir = folder.newFolder("files")
        val marker = File(dir, ".nomedia")

        assertTrue(FileUtils.createNoMediaMarkerIfAbsent(dir))
        assertTrue(marker.isFile)
    }

    @Test
    fun createsDirectoryWhenMissing() {
        val dir = File(folder.root, "nested/files")
        assertFalse(dir.exists())

        assertTrue(FileUtils.createNoMediaMarkerIfAbsent(dir))
        assertTrue(File(dir, ".nomedia").isFile)
    }

    @Test
    fun keepsExistingMarkerWithoutOverwriting() {
        val dir = folder.newFolder("files-keep")
        val marker = File(dir, ".nomedia")
        assertTrue(marker.createNewFile())
        marker.writeText("keep")

        assertFalse(FileUtils.createNoMediaMarkerIfAbsent(dir))
        assertTrue(marker.isFile)
        assertEquals("keep", marker.readText())
    }
}
