package com.github.avbox.osc.io

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConfigPathTest {

    private val root = "/storage/emulated/0"

    @Test
    fun pathUnderStorageRootBecomesClanUrl() {
        assertEquals(
            "clan://localhost/Download/tvbox.json",
            toClanApi("$root/Download/tvbox.json", root),
        )
        assertEquals(
            "clan://localhost/Android/data/com.github.avbox.osc/cache/config/tvbox.json",
            toClanApi("$root/Android/data/com.github.avbox.osc/cache/config/tvbox.json", root),
        )
    }

    @Test
    fun pathOutsideStorageRootMustFallBackToCopy() {
        assertNull(toClanApi("/storage/ABCD-1234/tvbox.json", root))
        assertNull(toClanApi("/data/user/0/com.github.avbox.osc/cache/config/tvbox.json", root))
        assertNull(toClanApi(null, root))
        assertNull(toClanApi("", root))
    }

    @Test
    fun externalStoragePrimaryDocId() {
        assertEquals("$root/Download/tvbox.json", externalStoragePath("primary:Download/tvbox.json", root))
        assertEquals("$root/摸鱼本地/config.json", externalStoragePath("primary:$root/摸鱼本地/config.json", root))
    }

    @Test
    fun externalStorageSecondaryVolumeDocId() {
        assertEquals("/storage/ABCD-1234/TVBox/x.json", externalStoragePath("ABCD-1234:TVBox/x.json", root))
        assertEquals("/storage/ABCD-1234/TVBox/x.json", externalStoragePath("ABCD-1234:/storage/ABCD-1234/TVBox/x.json", root))
    }

    @Test
    fun externalStorageBadDocId() {
        assertNull(externalStoragePath("primary:", root))
        assertNull(externalStoragePath("nodocid", root))
    }

    @Test
    fun treeDocPathAcceptsVolumeRoots() {
        assertEquals(root, treeDocPath("primary:", root))
        assertEquals("/storage/ABCD-1234", treeDocPath("ABCD-1234:", root))
        assertEquals("$root/Download", treeDocPath("primary:Download", root))
        assertEquals("/storage/ABCD-1234/TVBox", treeDocPath("ABCD-1234:TVBox", root))
    }

    @Test
    fun treeDocPathAcceptsNonVolumeDocIds() {
        assertEquals("/storage/emulated/0/Download", treeDocPath("raw:/storage/emulated/0/Download", root))
        assertEquals("$root/Download", treeDocPath("$root/Download", root))
        assertNull(treeDocPath("nodocid", root))
        assertNull(treeDocPath("raw:", root))
        assertNull(treeDocPath("images/media/1", root))
    }

    @Test
    fun ungrantableDirCoversRootsAndAndroid() {
        assertTrue(isUngrantableDir(root, root))
        assertTrue(isUngrantableDir("$root/", root))
        assertTrue(isUngrantableDir("$root/Download", root))
        assertTrue(isUngrantableDir("$root/Android/data", root))
        assertTrue(isUngrantableDir("$root/Android/data/com.github.avbox.osc/files", root))
        assertTrue(isUngrantableDir("$root/Android/obb", root))
        assertTrue(isUngrantableDir("/storage/ABCD-1234", root))
    }

    @Test
    fun ungrantableDirLeavesSubfoldersAlone() {
        assertFalse(isUngrantableDir("$root/Download/sub", root))
        assertFalse(isUngrantableDir("$root/Android", root))
        assertFalse(isUngrantableDir("/storage/ABCD-1234/TVBox", root))
        assertFalse(isUngrantableDir("$root/影视备份/摸鱼本地", root))
        assertFalse(isUngrantableDir(null, root))
    }

    @Test
    fun downloadDocIdForms() {
        assertTrue(isMediaStoreDownloadId("msf:1000000123"))
        assertFalse(isMediaStoreDownloadId("1000000123"))
        assertFalse(isMediaStoreDownloadId("raw:/storage/emulated/0/Download/x.json"))
        assertFalse(isMediaStoreDownloadId("document:456"))
    }

    @Test
    fun downloadNumericIdForms() {
        assertEquals(1000000123L, downloadNumericId("msf:1000000123") ?: -1L)
        assertEquals(1000000123L, downloadNumericId("1000000123") ?: -1L)
        assertNull(downloadNumericId("raw:/storage/emulated/0/Download/x.json"))
        assertNull(downloadNumericId("msf:abc"))
    }

    @Test
    fun mediaDocIdParsed() {
        assertEquals("document" to 456L, mediaDocId("document:456"))
        assertEquals("image" to 1L, mediaDocId("image:1"))
        assertNull(mediaDocId("document:abc"))
        assertNull(mediaDocId("nodocid"))
    }

    @Test
    fun sameFileNameGuardsWrongId() {
        assertTrue(sameFileName("$root/Download/tvbox.json", "tvbox.json"))
        assertTrue(sameFileName("$root/Download/tvbox.json", "Download/tvbox.json"))
        assertFalse(sameFileName("$root/Download/other.json", "tvbox.json"))
        assertFalse(sameFileName(null, "tvbox.json"))
        assertFalse(sameFileName("$root/Download/tvbox.json", null))
    }

    @Test
    fun downloadGuessPathOnlyAcceptsPlainNames() {
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "tvbox.json"))
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "Download/tvbox.json"))
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "  tvbox.json  "))
        assertNull(downloadGuessPath(root, ""))
        assertNull(downloadGuessPath(root, "."))
        assertNull(downloadGuessPath(root, ".."))
    }

    @Test
    fun relativeRefsPickDotSlashTargets() {
        val text = """{"spider":"./jar/1.jar;md5;282aee405654188f1bc1822bbf137410","logo":"./img/20.gif","ext":"./ext/2.json"}"""
        assertEquals(listOf("jar/1.jar", "img/20.gif", "ext/2.json"), relativeRefs(text))
    }

    @Test
    fun relativeRefsTrimSuffixAndDedupe() {
        assertEquals(
            listOf("a/x.json"),
            relativeRefs("""{"./a/x.json?raw=1","./a/x.json#top","./a/x.json"}"""),
        )
    }

    @Test
    fun relativeRefsRejectUnsafeTargets() {
        assertTrue(relativeRefs("""{"./"}""").isEmpty())
        assertTrue(relativeRefs("""{"./sub/"}""").isEmpty())
        assertTrue(relativeRefs("""{"./../up.json"}""").isEmpty())
        assertTrue(relativeRefs("""{"../up.json"}""").isEmpty())
    }

    @Test
    fun relativeRefsIgnoreNonRelativeValues() {
        assertTrue(relativeRefs("""{"http://a.com/./x"}""").isEmpty())
        assertTrue(relativeRefs("""{"clan://localhost/jar/1.jar"}""").isEmpty())
        assertTrue(relativeRefs("""{"file:///sdcard/jar/1.jar"}""").isEmpty())
    }

    @Test
    fun providerPathReadsExternalRootSegment() {
        assertEquals(
            "$root/影视备份/摸鱼本地/config.json",
            providerPath(listOf("extfiles", "影视备份", "摸鱼本地", "config.json"), root),
        )
        assertEquals("$root/a.json", providerPath(listOf("external_files", "a.json"), root))
        assertNull(providerPath(listOf("extfiles"), root))
        assertNull(providerPath(listOf("external", "images", "media", "1"), root))
        assertNull(providerPath(listOf("images", "a.json"), root))
    }

    @Test
    fun relativeUnderOnlyAcceptsDescendants() {
        assertEquals("jar/1.jar", relativeUnder("$root/影视备份/摸鱼本地", "$root/影视备份/摸鱼本地/jar/1.jar"))
        assertEquals("config.json", relativeUnder("$root/影视备份", "$root/影视备份/config.json"))
        assertNull(relativeUnder("$root/影视备份/摸鱼本地", "$root/影视备份/摸鱼本地"))
        assertNull(relativeUnder("$root/影视备份/摸鱼本地", "$root/影视备份/摸鱼本地2/x.json"))
        assertNull(relativeUnder("$root/影视备份", "$root/其他/x.json"))
    }

    @Test
    fun safeFileNameKeepsOnlyBasename() {
        assertEquals("config.json", safeFileName("config.json"))
        assertEquals("config.json", safeFileName("/sdcard/a/config.json"))
        assertEquals("config.json", safeFileName("a\\b\\config.json"))
        assertEquals("config.json", safeFileName("  config.json  "))
        assertEquals("local_config.json", safeFileName(null))
        assertEquals("local_config.json", safeFileName(""))
        assertEquals("local_config.json", safeFileName("."))
        assertEquals("local_config.json", safeFileName(".."))
        assertEquals("local_config.json", safeFileName("../../config.json/.."))
    }

    @Test
    fun localCopyUnitOnlyRemovesGeneratedCopies() {
        val tmp = Files.createTempDirectory("copy-unit").toFile()
        try {
            val storage = File(tmp, "storage").apply { mkdirs() }.absolutePath
            val copyRoot = File(File(storage, "Android/data/pkg/files"), "config").apply { mkdirs() }
            val graph = "0123456789abcdef0123456789abcdef"
            val dir = File(copyRoot, graph).apply { mkdirs() }
            File(dir, "spider_01234567.py").writeText("x")
            File(dir, "spider_01234567.json").writeText("{}")
            val base = "clan://localhost/Android/data/pkg/files/config"

            assertEquals(dir, localCopyUnit("$base/$graph/spider_01234567.json", storage, copyRoot.absolutePath))
            val single = File(copyRoot, "${graph}_local.json").apply { writeText("{}") }
            assertEquals(single, localCopyUnit("$base/${graph}_local.json;md5;deadbeef", storage, copyRoot.absolutePath))

            assertNull(localCopyUnit("clan://localhost/Download/tvbox.json", storage, copyRoot.absolutePath))
            assertNull(localCopyUnit("$base/manual/x.json", storage, copyRoot.absolutePath))
            assertNull(localCopyUnit("$base/notmd5_local.json", storage, copyRoot.absolutePath))
            assertNull(localCopyUnit("clan://192.168.1.5/x/y.py", storage, copyRoot.absolutePath))
            assertNull(localCopyUnit(null, storage, copyRoot.absolutePath))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
