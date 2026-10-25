package com.github.avbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayList

class StringUtilsTest {

    @Test
    fun isEmptyKeepsBothOverloads() {
        assertTrue(StringUtils.isEmpty(null as String?))
        assertTrue(StringUtils.isEmpty(""))
        assertFalse(StringUtils.isEmpty(" "))
        assertTrue(StringUtils.isEmpty(null as Any?))
        assertTrue(StringUtils.isEmpty(ArrayList<String>()))
        assertFalse(StringUtils.isEmpty(arrayListOf("x")))
        assertTrue(StringUtils.isEmpty(arrayOf<String>()))
        assertTrue(StringUtils.isNotEmpty("x"))
        assertTrue(StringUtils.isNull(null))
        assertFalse(StringUtils.isNull("x"))
        assertTrue(StringUtils.isNotNull("x"))
    }

    @Test
    fun trimStripsAsciiAndIdeographicSpaces() {
        assertEquals("a", StringUtils.trim("  a  "))
        assertEquals("a", StringUtils.trim("\u3000a\u3000"))
        assertEquals("", StringUtils.trim(" "))
        assertEquals("", StringUtils.trim(null))
        assertEquals("a b", StringUtils.trim("a b"))
        assertEquals("a", StringUtils.trimBlanks("\n\t\u000Ca\t\u000C\r"))
        assertNull(StringUtils.trimBlanks(null))
        assertTrue(StringUtils.isBlank(""))
        assertTrue(StringUtils.isBlank(null))
        assertFalse(StringUtils.isBlank("a"))
    }

    @Test
    fun baseUrlKeepsSchemeAndDropsPath() {
        assertEquals("https://a.example", StringUtils.getBaseUrl("https://a.example/b/c"))
        assertEquals("http://a.example", StringUtils.getBaseUrl("http://a.example"))
        assertEquals("https://a.example", StringUtils.getBaseUrl("https://a.example/"))
        assertEquals("", StringUtils.getBaseUrl(""))
        assertNull(StringUtils.getBaseUrl(null))
    }

    @Test
    fun joinHelpersKeepTheirNullExits() {
        assertEquals("a&&b", StringUtils.listToString(arrayListOf("a", "b")))
        assertEquals("a-b", StringUtils.listToString(arrayListOf("a", "b"), "-"))
        assertEquals("b-c", StringUtils.listToString(arrayListOf("a", "b", "c"), 1, "-"))
        assertEquals("", StringUtils.listToString(null))
        assertNull(StringUtils.listToString(arrayListOf<String?>(null)))

        assertEquals("a-b-c", StringUtils.arrayToString(arrayOf("a", "b", "c"), 0, "-"))
        assertEquals("a-b", StringUtils.arrayToString(arrayOf("a", "b", "c"), 0, 2, "-"))
        assertEquals("", StringUtils.arrayToString(null, 0, "-"))
        assertNull(StringUtils.arrayToString(arrayOf<String?>(null), 0, "-"))
    }

    @Test
    fun jsonSniffingNeedsBothBrackets() {
        assertTrue(StringUtils.isJsonType("{\"a\":1}"))
        assertTrue(StringUtils.isJsonType(" [1] "))
        assertFalse(StringUtils.isJsonType("{1]"))
        assertFalse(StringUtils.isJsonType(null))
        assertTrue(StringUtils.isJsonObject("{\"a\":1}"))
        assertFalse(StringUtils.isJsonObject("[1]"))
        assertTrue(StringUtils.isJsonArray("[1]"))
        assertFalse(StringUtils.isJsonArray("{\"a\":1}"))
    }

    @Test
    fun escapeJavaScriptStringEscapesQuotesAndNewlines() {
        assertEquals("a\\\"b", StringUtils.escapeJavaScriptString("a\"b"))
        assertEquals("a\\'b", StringUtils.escapeJavaScriptString("a'b"))
        assertEquals("a\\\\b", StringUtils.escapeJavaScriptString("a\\b"))
        assertEquals("a\\nb", StringUtils.escapeJavaScriptString("a\nb"))
        assertEquals("a\\rb", StringUtils.escapeJavaScriptString("a\rb"))
    }
}
