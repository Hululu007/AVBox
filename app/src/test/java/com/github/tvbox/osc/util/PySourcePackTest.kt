package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * py 包装的纯函数单测:包出来的 JSON 要能被 `ConfigParser.parseSites` 收下 ——
 * 少字段站点会被静默跳过(首页多一个点不开的图标),api 写错要到 Python 下载阶段才暴露。
 */
class PySourcePackTest {

    @Test
    fun packUrlWrapsPyContent() {
        val json = PySourcePack.packUrl("http://x.com/spider.py", "class Spider:\n    pass")
        assertEquals(
            "{\"sites\":[{\"key\":\"py_" + MD5.encode("http://x.com/spider.py")!!.take(8) +
                "\",\"name\":\"spider\",\"type\":3,\"api\":\"http://x.com/spider.py\"," +
                "\"searchable\":1,\"quickSearch\":1,\"filterable\":1}]}",
            json,
        )
    }

    @Test
    fun packUrlKeepsJsonConfigUntouched() {
        assertNull(PySourcePack.packUrl("http://x.com/spider.py", "{\"sites\":[]}"))
        assertNull(PySourcePack.packUrl("http://x.com/config.json", "class Spider: pass"))
        assertNull(PySourcePack.packUrl("http://x.com/spider.py", "   "))
        assertNull(PySourcePack.packUrl(null, "class Spider: pass"))
        // 带 BOM 的 JSON 配置同样不能当 py 包(否则源直接变"加载失败的站点")
        assertNull(PySourcePack.packUrl("http://x.com/spider.py", "\uFEFF{\"sites\":[]}"))
    }

    @Test
    fun packUrlAcceptsEncodedNameAndQuery() {
        val json = PySourcePack.packUrl(
            "http://x.com/%E4%B8%80%E8%B5%B7%E7%9C%8B%E5%BD%B1%E9%99%A2.py?extend=1",
            "import requests",
        )!!
        assertTrue(json.contains("\"name\":\"一起看影院\""))
        // api 原样带 query:extend 由 PythonSpider 自己从 URL 读,包装层不得吞掉
        assertTrue(json.contains("\"api\":\"http://x.com/%E4%B8%80%E8%B5%B7%E7%9C%8B%E5%BD%B1%E9%99%A2.py?extend=1\""))
    }

    @Test
    fun packUrlKeepsLocalhostClanForLaterRewrite() {
        val json = PySourcePack.packUrl("clan://localhost/tvbox/y.py", "import re")!!
        assertTrue(json.contains("\"api\":\"clan://localhost/tvbox/y.py\""))
    }

    @Test
    fun packUrlRewritesLanClanToHttp() {
        // 局域网 clan 地址躲不过 clanContentFix(它只认 clan://localhost/),必须在这里转 http
        val json = PySourcePack.packUrl("clan://192.168.1.5/tvbox/y.py", "import re")!!
        assertTrue(json.contains("\"api\":\"http://192.168.1.5/file/tvbox/y.py\""))
    }

    @Test
    fun packUrlNameFallsBackAndKeepsPlus() {
        // 地址以 / 结尾(末段退化成 host)时拿 host 当名,比兜底名有信息量
        val host = PySourcePack.packUrl("http://x.com/?from=y.py", "import re")!!
        assertTrue(host.contains("\"name\":\"x.com\""))
        // 末段恰好就是 .py ⇒ 取不出名字,必须兜底
        val fallback = PySourcePack.packUrl("http://x.com/.py", "import re")!!
        assertTrue(fallback.contains("\"name\":\"Python源\""))
        // 路径段里的 + 是字面加号,不能被 URLDecoder 解成空格
        val plus = PySourcePack.packUrl("http://x.com/a+b.py", "import re")!!
        assertTrue(plus.contains("\"name\":\"a+b\""))
    }

    @Test
    fun packLocalUsesRelativeApi() {
        val json = PySourcePack.packLocal("spider_ab12cd34.py", "一起看影院", "py_ab12cd34")
        assertEquals(
            "{\"sites\":[{\"key\":\"py_ab12cd34\",\"name\":\"一起看影院\",\"type\":3," +
                "\"api\":\"./spider_ab12cd34.py\",\"searchable\":1,\"quickSearch\":1,\"filterable\":1}]}",
            json,
        )
    }

    @Test
    fun nameAndApiAreEscaped() {
        val json = PySourcePack.packLocal("s.py", "he\"llo\\x", "k")!!
        assertTrue(json.contains("\"name\":\"he\\\"llo\\\\x\""))
        // 名字为空(如文件名恰是 `.py`)时要有兜底,否则首页出现无名站点
        val blank = PySourcePack.packLocal("s.py", "", "k")!!
        assertTrue(blank.contains("\"name\":\"Python源\""))
    }
}
