package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * [FileUtils.repairBogusNativeLibs] 的纯 JVM 单测(不碰 App / Android 存储)。
 *
 * <p>存在理由(2026-09-21 真机自锁事故):某第三方仓的子源爬虫从云存储下载 libwexproxy.so 时,
 * 远端对象已被删除,CDN 返回 313 字节的 XML 报错,爬虫把**报错原文**当 .so 落盘再 System.load ⇒
 * {@code UnsatisfiedLinkError: has bad ELF magic}。更要命的是它会自锁:坏文件留在私有目录里,
 * 该爬虫每次冷启动都再 load 一次,用户进一次崩一次,连"换源"都做不了,只能清数据。
 *
 * <p>所以判据必须"只杀确定的垃圾":ELF 魔数不符才删,合法的 .so / .lib* 一个都不能碰 ——
 * 误删合法库会把用户能用的源弄坏,比崩溃更难排查。本测试就是钉死这条边界。
 */
public class FileUtilsNativeLibRepairTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final byte[] ELF = {0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00};

    /** 实测到的 CDN 报错正文开头 */
    private static final String XML_ERROR =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error>\n  <Code>NoSuchKey</Code>\n</Error>";

    private File write(File dir, String name, byte[] content) throws Exception {
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file;
    }

    private File write(File dir, String name, String content) throws Exception {
        return write(dir, name, content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 必须删:假原生库 ----------

    @Test
    public void removesXmlErrorSavedAsSo() throws Exception {
        File root = folder.newFolder("files");
        File tv = new File(root, "TV");
        assertTrue(tv.mkdirs());
        File bogus = write(tv, "libwexproxy.so", XML_ERROR);
        File bogusTemp = write(tv, ".libwexproxyMZVs13PWOs", XML_ERROR);

        assertEquals(2, FileUtils.repairBogusNativeLibs(root));
        assertFalse(bogus.exists());
        assertFalse(bogusTemp.exists());
    }

    /** 半截下载(不足 4 字节 / 空文件)同样不是合法库 */
    @Test
    public void removesTruncatedAndEmptySo() throws Exception {
        File root = folder.newFolder("files2");
        File shortFile = write(root, "a.so", new byte[]{0x7F, 'E'});
        File empty = write(root, "b.so", new byte[0]);

        assertEquals(2, FileUtils.repairBogusNativeLibs(root));
        assertFalse(shortFile.exists());
        assertFalse(empty.exists());
    }

    /** HTML 报错页(代理站返回网页)也是同一类垃圾 */
    @Test
    public void removesHtmlSavedAsLib() throws Exception {
        File root = folder.newFolder("files3");
        File html = write(root, ".libproxy", "<!DOCTYPE html><html>404</html>");

        assertEquals(1, FileUtils.repairBogusNativeLibs(root));
        assertFalse(html.exists());
    }

    // ---------- 绝不能碰:合法库与非库文件 ----------

    @Test
    public void keepsValidElfLibraries() throws Exception {
        File root = folder.newFolder("files4");
        File tv = new File(root, "TV");
        assertTrue(tv.mkdirs());
        File so = write(root, "libLoadNiMa.so", ELF);
        File temp = write(tv, ".libLoadNiMaz3tbtfmq2v", ELF);

        assertEquals(0, FileUtils.repairBogusNativeLibs(root));
        assertTrue(so.exists());
        assertTrue(temp.exists());
    }

    /**
     * 非 .so 的资源一律不碰 —— 爬虫目录里还有 .wexstring / .wexcofig.json / go_proxy_video
     * 这类配置与数据文件,按"不是 ELF"去删会把用户能用的源弄坏。
     */
    @Test
    public void keepsNonLibraryFiles() throws Exception {
        File root = folder.newFolder("files5");
        File cfg = write(root, ".wexcofig.json", "{}");
        File string = write(root, ".wexstring", XML_ERROR);
        File flag = write(root, "go_proxy_video", "{}");
        File db = write(root, "spider.db", XML_ERROR);
        // 名字里带 lib 但不是原生库命名(不以下划线/点开头的 .lib* 才管)
        File other = write(root, "libdata.txt", XML_ERROR);

        assertEquals(0, FileUtils.repairBogusNativeLibs(root));
        assertTrue(cfg.exists());
        assertTrue(string.exists());
        assertTrue(flag.exists());
        assertTrue(db.exists());
        assertTrue(other.exists());
    }

    // ---------- 边界 ----------

    @Test
    public void handlesMissingDirAndNestedLevels() throws Exception {
        assertEquals(0, FileUtils.repairBogusNativeLibs(new File(folder.getRoot(), "not-there")));

        File root = folder.newFolder("files6");
        File deep = new File(root, "a/b");
        assertTrue(deep.mkdirs());
        File bogus = write(deep, "x.so", XML_ERROR);
        assertEquals(1, FileUtils.repairBogusNativeLibs(root));
        assertFalse(bogus.exists());
    }

    /** 深度上限之外不再下探:避免在大目录上白跑 */
    @Test
    public void stopsAtDepthLimit() throws Exception {
        File root = folder.newFolder("files7");
        File tooDeep = new File(root, "a/b/c/d/e");
        assertTrue(tooDeep.mkdirs());
        File bogus = write(tooDeep, "x.so", XML_ERROR);

        assertEquals(0, FileUtils.repairBogusNativeLibs(root));
        assertTrue("超出深度上限的文件不应被删除", bogus.exists());
    }
}
