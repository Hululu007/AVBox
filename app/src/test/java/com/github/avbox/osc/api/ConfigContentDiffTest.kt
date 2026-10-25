package com.github.avbox.osc.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigContentDiffTest {

    @Test
    fun isSame_identicalText() {
        val text = "{\"sites\":[{\"key\":\"a\",\"api\":\"b\"}]}"
        assertTrue(ConfigContentDiff.isSame(text, text))
    }

    @Test
    fun isSame_ignoresLineEndingsAndIndent() {
        val minified = "{\"sites\":[{\"key\":\"a\",\"api\":\"b\"}]}"
        val pretty = "{\r\n  \"sites\": [\r\n    {\r\n      \"key\": \"a\",\r\n      \"api\": \"b\"\r\n    }\r\n  ]\r\n}"
        assertTrue(ConfigContentDiff.isSame(minified, pretty))
    }

    @Test
    fun isSame_ignoresTrailingNewlineFromCacheRead() {
        val body = "{\"sites\":[]}"
        assertTrue(ConfigContentDiff.isSame(body, body + "\n"))
    }

    @Test
    fun isSame_detectsApiChange() {
        val before = "{\"sites\":[{\"key\":\"红果短剧\",\"api\":\"https://a/HGDJ.json\"}]}"
        val after = "{\"sites\":[{\"key\":\"红果短剧\",\"api\":\"https://b/红果短剧.py\"}]}"
        assertFalse(ConfigContentDiff.isSame(before, after))
    }

    @Test
    fun isSame_detectsSiteRemoval() {
        val before = "{\"sites\":[{\"key\":\"a\"},{\"key\":\"b\"}]}"
        val after = "{\"sites\":[{\"key\":\"a\"}]}"
        assertFalse(ConfigContentDiff.isSame(before, after))
    }

    @Test
    fun isSame_emptyAgainstContent() {
        assertFalse(ConfigContentDiff.isSame("", "{\"sites\":[]}"))
        assertTrue(ConfigContentDiff.isSame("", " \n "))
    }
}
