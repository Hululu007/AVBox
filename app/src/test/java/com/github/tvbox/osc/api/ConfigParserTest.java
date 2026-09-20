package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.bean.SourceBean;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * [ConfigParser] 的纯函数单测(纯 JVM,无需 Robolectric)。
 *
 * 选点理由:这些都是"错了不报错、只是源加载不出来/图片不显示"的分支 —— 接口正文前面多一行注释、
 * 地址少了 http://、clan:// 地址没换成本机服务地址、m3u 头部的 EPG 写在 tvg-url 而不是 x-tvg-url,
 * 真机上只能看到"配置加载失败"或者"EPG 空白",没有堆栈可查。
 */
public class ConfigParserTest {

    private static final Gson gson = new Gson();

    /** 本机服务基址;用抛异常的实现证明"只有 clan://localhost/ 才会去取" */
    private static final Supplier<String> LOCAL = () -> "http://192.168.1.9:9978/";
    private static final Supplier<String> NEVER = () -> {
        throw new AssertionError("非 clan://localhost/ 地址不应该去取本机服务基址");
    };

    private static JsonObject json(String text) {
        return gson.fromJson(text, JsonObject.class);
    }

    private static JsonArray jsonArray(String text) {
        return gson.fromJson(text, JsonArray.class);
    }

    // ---------- JSON 正文裁剪与形状判断 ----------

    @Test
    public void trimJsonObject_keepsOnlyObjectBody() {
        assertEquals("{\"a\":1}", ConfigParser.trimJsonObject("// 版权说明\n{\"a\":1}\n// 尾巴"));
        assertEquals("{\"a\":1}", ConfigParser.trimJsonObject("  {\"a\":1}  "));
        assertEquals("没有花括号", ConfigParser.trimJsonObject("没有花括号"));
        assertEquals("", ConfigParser.trimJsonObject(null));
    }

    @Test
    public void trimJsonObject_keepsRawTextWhenBracesReversed() {
        // "}" 在 "{" 之前:不能截,否则会把正文截成空串
        assertEquals("} {", ConfigParser.trimJsonObject("} {"));
    }

    @Test
    public void isLiveJsonContent_detectsBomAndText() {
        assertTrue(ConfigParser.isLiveJsonContent("{\"lives\":[]}"));
        assertTrue(ConfigParser.isLiveJsonContent("\ufeff {\"lives\":[]}"));
        assertFalse(ConfigParser.isLiveJsonContent("#EXTM3U\nCCTV1,http://a/1"));
        assertFalse(ConfigParser.isLiveJsonContent("   "));
        assertFalse(ConfigParser.isLiveJsonContent(null));
    }

    // ---------- m3u 头部 EPG 地址 ----------

    @Test
    public void extractQuotedAttr_readsValueAndToleratesMalformed() {
        String line = "#EXTM3U url-tvg=\" http://a/e.xml \" tvg-url=\"http://b/e.xml\"";
        assertEquals("http://a/e.xml", ConfigParser.extractQuotedAttr(line, "url-tvg"));
        assertEquals("http://b/e.xml", ConfigParser.extractQuotedAttr(line, "tvg-url"));
        assertEquals("", ConfigParser.extractQuotedAttr(line, "x-tvg-url"));
        assertEquals("", ConfigParser.extractQuotedAttr("#EXTM3U x-tvg-url=\"http://a/e.xml", "x-tvg-url"));
    }

    @Test
    public void extractQuotedAttr_tvgUrlAlsoMatchesInsideXTvgUrl() {
        // "tvg-url=" 是 "x-tvg-url=" 的子串:单独找 tvg-url 会命中前面的 x-tvg-url ——
        // 这正是 extractLiveTextEpg 必须先试 x-tvg-url、再试 tvg-url 的原因
        String line = "#EXTM3U x-tvg-url=\"http://a/e.xml\" tvg-url=\"http://b/e.xml\"";
        assertEquals("http://a/e.xml", ConfigParser.extractQuotedAttr(line, "tvg-url"));
    }

    @Test
    public void extractLiveTextEpg_triesThreeAttributeNamesInOrder() {
        assertEquals("http://a/e.xml",
                ConfigParser.extractLiveTextEpg("#EXTM3U x-tvg-url=\"http://a/e.xml\"\nCCTV1,http://a/1"));
        assertEquals("http://b/e.xml", ConfigParser.extractLiveTextEpg("#EXTM3U tvg-url=\"http://b/e.xml\""));
        assertEquals("http://c/e.xml", ConfigParser.extractLiveTextEpg("#EXTM3U url-tvg=\"http://c/e.xml\""));
        assertEquals("http://a/e.xml", ConfigParser.extractLiveTextEpg(
                "#EXTM3U x-tvg-url=\"http://a/e.xml\" tvg-url=\"http://b/e.xml\""));
    }

    @Test
    public void extractLiveTextEpg_handlesBomCrlfAndMissing() {
        assertEquals("http://a/e.xml", ConfigParser.extractLiveTextEpg("\ufeff#EXTM3U x-tvg-url=\"http://a/e.xml\""));
        assertEquals("http://a/e.xml",
                ConfigParser.extractLiveTextEpg("CCTV1,http://a/1\r\n#EXTM3U x-tvg-url=\"http://a/e.xml\"\r\n"));
        assertEquals("", ConfigParser.extractLiveTextEpg("#EXTM3U\nCCTV1,http://a/1"));
        assertEquals("", ConfigParser.extractLiveTextEpg(null));
    }

    // ---------- 站点列表 ----------

    @Test
    public void parseSites_buildsBeansAndSkipsIncomplete() {
        List<SourceBean> sites = ConfigParser.parseSites(json("{\"sites\":["
                + "{\"key\":\"csp_A\",\"name\":\"站点A\",\"type\":3,\"api\":\"http://a/api\",\"ext\":\"/path/ext\","
                + "\"icon\":\"http://a/i.png\",\"categories\":[\"电影\",\"剧集\"],\"searchable\":0},"
                + "{\"key\":\"no_api\",\"type\":1},"
                + "{\"type\":1,\"api\":\"http://c/api\"},"
                + "{\"key\":\"py_B\",\"type\":1,\"api\":\"http://b/api\"}]}"));

        assertEquals(2, sites.size());
        SourceBean a = sites.get(0);
        assertEquals("csp_A", a.getKey());
        assertEquals("站点A", a.getName());
        assertEquals(3, a.getType());
        assertEquals("http://a/api", a.getApi());
        assertEquals("/path/ext", a.getExt());
        assertEquals("http://a/i.png", a.getIcon());
        assertEquals(2, a.getCategories().size());
        assertFalse(a.isSearchable());
    }

    @Test
    public void parseSites_appliesDefaultsAndPyFilterable() {
        List<SourceBean> sites = ConfigParser.parseSites(json(
                "{\"sites\":[{\"key\":\"py_B\",\"type\":1,\"api\":\"http://b/api\"}]}"));

        assertEquals(1, sites.size());
        SourceBean b = sites.get(0);
        // 没写 name 的站点用 key 兜底,否则首页会出现空名字的图标
        assertEquals("py_B", b.getName());
        assertTrue(b.isSearchable());
        assertTrue(b.isQuickSearch());
        assertTrue(b.isChangeable());
        // py_ 前缀强制可筛选,不看配置
        assertEquals(1, b.getFilterable());
        assertEquals(-1, b.getPlayerType());
        assertEquals(0, b.getTimeout());
        assertEquals("", b.getJar());
        assertEquals("", b.getStyle());
        assertEquals("", b.getClickSelector());
    }

    @Test
    public void parseSites_keepsConfigOrder() {
        List<SourceBean> sites = ConfigParser.parseSites(json("{\"sites\":["
                + "{\"key\":\"b\",\"type\":1,\"api\":\"http://b\"},"
                + "{\"key\":\"a\",\"type\":1,\"api\":\"http://a\"}]}"));
        assertEquals("b", sites.get(0).getKey());
        assertEquals("a", sites.get(1).getKey());
    }

    // ---------- 线路合集 ----------

    @Test
    public void parseApiCollection_acceptsObjectsAndPrimitives() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "{\"urls\":[{\"name\":\"线路1\",\"url\":\"http://a/1\"},{\"url\":\"http://b/2\"},\"http://c/3\"]}");

        assertEquals(3, lines.size());
        assertEquals("线路1\thttp://a/1", lines.get(0));
        // 没写 name 的用 url 兜底当名字
        assertEquals("http://b/2\thttp://b/2", lines.get(1));
        assertEquals("http://c/3\thttp://c/3", lines.get(2));
    }

    @Test
    public void parseApiCollection_usesApiFieldWhenUrlMissing() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "{\"urls\":[{\"name\":\"x\",\"api\":\"http://d/4\"},{\"name\":\"empty\",\"url\":\"\"}]}");
        assertEquals(1, lines.size());
        assertEquals("x\thttp://d/4", lines.get(0));
    }

    @Test
    public void parseApiCollection_toleratesSurroundingText() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "说明文字\n{\"urls\":[\"http://c/3\"]}\n尾巴");
        assertEquals(1, lines.size());
        assertEquals("http://c/3\thttp://c/3", lines.get(0));
    }

    @Test
    public void parseApiCollection_rejectsNormalConfigAndGarbage() {
        // 带 sites 的是正常点播配置,不是合集
        assertTrue(ConfigParser.parseApiCollection("{\"sites\":[],\"urls\":[\"http://a/1\"]}").isEmpty());
        assertTrue(ConfigParser.parseApiCollection("{\"urls\":\"http://a/1\"}").isEmpty());
        assertTrue(ConfigParser.parseApiCollection("这不是 JSON").isEmpty());
        assertTrue(ConfigParser.parseApiCollection(null).isEmpty());
    }

    // ---------- 直播多源与 hosts ----------

    @Test
    public void parseLiveSettingItems_namesAndIndexes() {
        List<LiveSettingItem> items = ConfigParser.parseLiveSettingItems(
                jsonArray("[{\"name\":\"线路A\"},{},{\"name\":\"\"}]"));

        assertEquals(3, items.size());
        assertEquals(0, items.get(0).getItemIndex());
        assertEquals("线路A", items.get(0).getItemName());
        assertEquals(1, items.get(1).getItemIndex());
        assertEquals("线路2", items.get(1).getItemName());
        // 显式给了空名字就保留空名字,不再兜底
        assertEquals("", items.get(2).getItemName());
    }

    @Test
    public void parseHosts_splitsOnFirstEqualsOnly() {
        Map<String, String> hosts = ConfigParser.parseHosts(
                jsonArray("[\"a.com=1.2.3.4\",\"b.com=2.3.4.5=x\",\"bad\"]"));

        assertEquals(2, hosts.size());
        assertEquals("1.2.3.4", hosts.get("a.com"));
        assertEquals("2.3.4.5=x", hosts.get("b.com"));
    }

    // ---------- clan:// 地址改写 ----------

    @Test
    public void clanToAddress_localhostUsesLocalBase() {
        assertEquals("http://192.168.1.9:9978/file/abc.json",
                ConfigParser.clanToAddress("clan://localhost/abc.json", LOCAL));
    }

    @Test
    public void clanToAddress_remoteHostDoesNotTouchLocalBase() {
        assertEquals("http://tvbox.example.com/file/abc.json",
                ConfigParser.clanToAddress("clan://tvbox.example.com/abc.json", NEVER));
    }

    @Test
    public void clanContentFix_rewritesBothPrefixes() {
        String fixed = ConfigParser.clanContentFix("http://192.168.1.9:9978/file/abc.json",
                "{\"a\":\"clan://localhost/x.jpg\",\"b\":\"file:///sdcard/y.jpg\"}");
        assertEquals("{\"a\":\"http://192.168.1.9:9978/file/x.jpg\",\"b\":\"http://192.168.1.9:9978/file//sdcard/y.jpg\"}",
                fixed);
    }

    @Test
    public void fixContentPath_untouchedWithoutRelativePath() {
        String content = "{\"a\":\"http://a/x.jpg\"}";
        assertEquals(content, ConfigParser.fixContentPath("http://h/dir/config.json", content, NEVER));
    }

    @Test
    public void fixContentPath_resolvesRelativePathsAgainstConfigUrl() {
        assertEquals("{\"a\":\"http://h/dir/pic.jpg\"}",
                ConfigParser.fixContentPath("http://h/dir/config.json", "{\"a\":\"./pic.jpg\"}", NEVER));
        // 没有 scheme 的地址按 http:// 补
        assertEquals("{\"a\":\"http://h/pic.jpg\"}",
                ConfigParser.fixContentPath("h/dir/config.json", "{\"a\":\"../pic.jpg\"}", NEVER));
    }

    @Test
    public void fixContentPath_clanUrlGoesThroughLocalBase() {
        assertEquals("{\"a\":\"http://192.168.1.9:9978/file/pic.jpg\"}",
                ConfigParser.fixContentPath("clan://localhost/config.json", "{\"a\":\"./pic.jpg\"}", LOCAL));
    }

    // ---------- 配置地址与密钥 ----------

    @Test
    public void configUrl_keepsHttpAndAddsScheme() {
        ConfigParser.ConfigUrl http = ConfigParser.configUrl("http://a/config.json", NEVER);
        assertEquals("http://a/config.json", http.url);
        assertNull(http.key);

        assertEquals("http://a/config.json", ConfigParser.configUrl("a/config.json", NEVER).url);
    }

    @Test
    public void configUrl_splitsPkKey() {
        ConfigParser.ConfigUrl withKey = ConfigParser.configUrl("http://a/config.json;pk;1234", NEVER);
        assertEquals("http://a/config.json", withKey.url);
        assertEquals("1234", withKey.key);

        // 没写 scheme 时补 http://,密钥照旧取出
        ConfigParser.ConfigUrl noScheme = ConfigParser.configUrl("a/config.json;pk;k", NEVER);
        assertEquals("http://a/config.json", noScheme.url);
        assertEquals("k", noScheme.key);
    }

    @Test
    public void configUrl_clanAndFileLinks() {
        assertEquals("http://192.168.1.9:9978/file/config.json",
                ConfigParser.configUrl("clan://localhost/config.json", LOCAL).url);
        assertEquals("http://tvbox.example.com/file/c.json",
                ConfigParser.configUrl("clan://tvbox.example.com/c.json;pk;k", NEVER).url);
        // file:// 先被改写成 clan://localhost/,再换成本机服务地址(注意双斜杠是原样保留的)
        assertEquals("http://192.168.1.9:9978/file//sdcard/c.json",
                ConfigParser.configUrl("file:///sdcard/c.json", LOCAL).url);
    }
}
