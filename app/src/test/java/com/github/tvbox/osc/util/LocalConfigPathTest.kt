package com.github.tvbox.osc.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地源导入的路径解析纯函数单测(2026-09-16)。
 *
 * 为什么值得测:判定错的后果是"配置能导入、但同目录 jar/js 404"(该直引时落到了复制分支),
 * 或者反过来"直引到本地服务读不到的位置"(SD 卡 —— `RemoteServer` 的 `/file/` 只服务外置存储根)
 * 导致整个源拉取失败。两者都只在真机选文件时才暴露,而这几个函数本身不碰 `android.*`,
 * 可以纯 JVM 覆盖(`localConfigToApi` 那层系统调用不进单测)。
 */
class LocalConfigPathTest {

    private val root = "/storage/emulated/0"

    // ---- 直引 / 复制的分水岭 ----

    @Test
    fun pathUnderStorageRootBecomesClanUrl() {
        assertEquals(
            "clan://localhost/Download/tvbox.json",
            toClanApi("$root/Download/tvbox.json", root),
        )
        // 复制分支的落点(外置缓存)同属外置存储根,必须也能转成地址
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

    // ---- externalstorage provider(选择器的「内部存储」目录树 / SD 卡) ----

    @Test
    fun externalStoragePrimaryDocId() {
        assertEquals("$root/Download/tvbox.json", externalStoragePath("primary:Download/tvbox.json", root))
    }

    @Test
    fun externalStorageSecondaryVolumeDocId() {
        assertEquals("/storage/ABCD-1234/TVBox/x.json", externalStoragePath("ABCD-1234:TVBox/x.json", root))
    }

    @Test
    fun externalStorageBadDocId() {
        assertNull(externalStoragePath("primary:", root))
        assertNull(externalStoragePath("nodocid", root))
    }

    // ---- 目录树 docId 比文件 docId 宽:卷根、`raw:`、绝对路径形态都必须映射出路径 ----

    @Test
    fun treeDocPathAcceptsVolumeRoots() {
        // 选中整个卷(冒号后为空)也要映射得出:本地服务靠它把 /file/ 请求落到授权目录上
        assertEquals(root, treeDocPath("primary:", root))
        assertEquals("/storage/ABCD-1234", treeDocPath("ABCD-1234:", root))
        // 有相对部分时与文件形态同解
        assertEquals("$root/Download", treeDocPath("primary:Download", root))
        assertEquals("/storage/ABCD-1234/TVBox", treeDocPath("ABCD-1234:TVBox", root))
    }

    @Test
    fun treeDocPathAcceptsNonVolumeDocIds() {
        assertEquals("/storage/emulated/0/Download", treeDocPath("raw:/storage/emulated/0/Download", root))
        assertEquals("$root/Download", treeDocPath("$root/Download", root))
        // 认不出的形态仍返回 null:映射不出本地路径的 provider(网盘)不该被记进授权列表
        assertNull(treeDocPath("nodocid", root))
        assertNull(treeDocPath("raw:", root))
        assertNull(treeDocPath("images/media/1", root))
    }

    // ---- 系统永不允许授权的目录:判错的后果是让用户反复去点一个点了也没用的选择器 ----

    @Test
    fun ungrantableDirCoversRootsAndAndroid() {
        assertTrue(isUngrantableDir(root, root))
        assertTrue(isUngrantableDir("$root/", root))
        assertTrue(isUngrantableDir("$root/Download", root))
        assertTrue(isUngrantableDir("$root/Android/data", root))
        assertTrue(isUngrantableDir("$root/Android/data/com.github.avbox.osc/files", root))
        assertTrue(isUngrantableDir("$root/Android/obb", root))
        // 副卷卷根(如 SD 卡)同样不能授权
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

    // ---- downloads provider(选择器的「下载」分类;Android 10+ 主流形态是 msf:) ----

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

    // ---- media provider(选择器的「最近」;json/txt 走 document: 形态,查 MediaStore.Files) ----

    @Test
    fun mediaDocIdParsed() {
        assertEquals("document" to 456L, mediaDocId("document:456"))
        assertEquals("image" to 1L, mediaDocId("image:1"))
        assertNull(mediaDocId("document:abc"))
        assertNull(mediaDocId("nodocid"))
    }

    // ---- `msf:` 第二段(MediaStore.Files)采纳前的同名校验:不过就复制,绝不能直引到别的文件 ----

    @Test
    fun sameFileNameGuardsWrongId() {
        assertTrue(sameFileName("$root/Download/tvbox.json", "tvbox.json"))
        assertTrue(sameFileName("$root/Download/tvbox.json", "Download/tvbox.json"))
        assertFalse(sameFileName("$root/Download/other.json", "tvbox.json"))
        assertFalse(sameFileName(null, "tvbox.json"))
        assertFalse(sameFileName("$root/Download/tvbox.json", null))
    }

    // ---- `msf:` 第三段(兜底):按显示名猜 Download 目录下的路径,非法名一律拒绝 ----

    @Test
    fun downloadGuessPathOnlyAcceptsPlainNames() {
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "tvbox.json"))
        // 带目录的显示名只取 basename,不会指到 Download 之外
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "Download/tvbox.json"))
        assertEquals("$root/Download/tvbox.json", downloadGuessPath(root, "  tvbox.json  "))
        assertNull(downloadGuessPath(root, ""))
        assertNull(downloadGuessPath(root, "."))
        assertNull(downloadGuessPath(root, ".."))
    }

    // ---- 复制分支:配置里 `./` 引用的同目录文件要跟着一起搬(否则重写后的 http 前缀必 404) ----

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
        // 空引用、目录引用、含 `..` 的:复制落点会越界或没有意义,一律不搬
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

    // ---- 第三方文件管理器的 FileProvider(vivo 文件管理器实测形态:没有 docId/DATA 列,路径在 Uri 里) ----

    @Test
    fun providerPathReadsExternalRootSegment() {
        assertEquals(
            "$root/影视备份/摸鱼本地/config.json",
            providerPath(listOf("extfiles", "影视备份", "摸鱼本地", "config.json"), root),
        )
        assertEquals("$root/a.json", providerPath(listOf("external_files", "a.json"), root))
        // 只有根段名(没跟相对路径)不认
        assertNull(providerPath(listOf("extfiles"), root))
        // `content://media/external/images/media/1` 这类不带外置存储根语义,不能当成路径
        assertNull(providerPath(listOf("external", "images", "media", "1"), root))
        assertNull(providerPath(listOf("images", "a.json"), root))
    }

    // ---- 目录授权(SAF tree)与本地服务请求路径的匹配:只有真在授权目录之下才认 ----

    @Test
    fun relativeUnderOnlyAcceptsDescendants() {
        assertEquals("jar/1.jar", relativeUnder("$root/影视备份/摸鱼本地", "$root/影视备份/摸鱼本地/jar/1.jar"))
        assertEquals("config.json", relativeUnder("$root/影视备份", "$root/影视备份/config.json"))
        // 目录本身 / 前缀相同的兄弟目录都不能当成"在其之下",否则会把别的目录的文件当成授权范围内的
        assertNull(relativeUnder("$root/影视备份/摸鱼本地", "$root/影视备份/摸鱼本地"))
        assertNull(relativeUnder("$root/影视备份/摸鱼本地", "$root/影视备份/摸鱼本地2/x.json"))
        assertNull(relativeUnder("$root/影视备份", "$root/其他/x.json"))
    }

    // ---- 显示名当文件名用之前必须收窄(provider 给的显示名可能是路径或 `..`) ----

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

    // ---- 删订阅时的副本清理:只删应用自己生成的 config/ 下产物 ----

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

            // 生成目录(config/<md5>/…)整棵删
            assertEquals(dir, localCopyUnit("$base/$graph/spider_01234567.json", storage, copyRoot.absolutePath))
            // 直接落在 config/ 下的单文件副本(md5_原名)删文件本身;`;md5;` 尾巴不影响反解
            val single = File(copyRoot, "${graph}_local.json").apply { writeText("{}") }
            assertEquals(single, localCopyUnit("$base/${graph}_local.json;md5;deadbeef", storage, copyRoot.absolutePath))

            // 用户原文件(config 之外)、非 md5 约定的 config 子项、局域网 clan / 普通地址:一律不碰
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
