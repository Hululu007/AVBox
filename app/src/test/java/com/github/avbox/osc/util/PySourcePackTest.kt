package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertNull(PySourcePack.packUrl("http://x.com/spider.py", "\uFEFF{\"sites\":[]}"))
    }

    @Test
    fun packUrlAcceptsEncodedNameAndQuery() {
        val json = PySourcePack.packUrl(
            "http://x.com/%E4%B8%80%E8%B5%B7%E7%9C%8B%E5%BD%B1%E9%99%A2.py?extend=1",
            "import requests",
        )!!
        assertTrue(json.contains("\"name\":\"一起看影院\""))
        assertTrue(json.contains("\"api\":\"http://x.com/%E4%B8%80%E8%B5%B7%E7%9C%8B%E5%BD%B1%E9%99%A2.py?extend=1\""))
    }

    @Test
    fun packUrlKeepsLocalhostClanForLaterRewrite() {
        val json = PySourcePack.packUrl("clan://localhost/tvbox/y.py", "import re")!!
        assertTrue(json.contains("\"api\":\"clan://localhost/tvbox/y.py\""))
    }

    @Test
    fun packUrlRewritesLanClanToHttp() {
        val json = PySourcePack.packUrl("clan://192.168.1.5/tvbox/y.py", "import re")!!
        assertTrue(json.contains("\"api\":\"http://192.168.1.5/file/tvbox/y.py\""))
    }

    @Test
    fun packUrlNameFallsBackAndKeepsPlus() {
        val host = PySourcePack.packUrl("http://x.com/?from=y.py", "import re")!!
        assertTrue(host.contains("\"name\":\"x.com\""))
        val fallback = PySourcePack.packUrl("http://x.com/.py", "import re")!!
        assertTrue(fallback.contains("\"name\":\"Python源\""))
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
    fun packLocalJsKeepsClanApiAndDisablesFilter() {
        val api = "clan://localhost/Android/data/com.github.avbox.osc/files/config/ab12cd34/spider_ab12cd34.js"
        val json = PySourcePack.packLocalJs(api, "枫叶", "js_ab12cd34")
        assertEquals(
            "{\"sites\":[{\"key\":\"js_ab12cd34\",\"name\":\"枫叶\",\"type\":3," +
                "\"api\":\"" + api + "\",\"searchable\":1,\"quickSearch\":1,\"filterable\":0}]}",
            json,
        )
    }

    @Test
    fun packLocalJsNameFallsBackToJsSource() {
        val json = PySourcePack.packLocalJs("clan://localhost/x/s.js", "", "k")
        assertTrue(json.contains("\"name\":\"JS源\""))
    }

    @Test
    fun nameAndApiAreEscaped() {
        val json = PySourcePack.packLocal("s.py", "he\"llo\\x", "k")
        assertTrue(json.contains("\"name\":\"he\\\"llo\\\\x\""))
        val blank = PySourcePack.packLocal("s.py", "", "k")
        assertTrue(blank.contains("\"name\":\"Python源\""))
    }
}
