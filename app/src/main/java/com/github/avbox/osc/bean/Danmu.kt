package com.github.avbox.osc.bean

import android.text.TextUtils
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Collections
import java.util.regex.Pattern

class Danmu {
    private val data: MutableList<Data> = ArrayList()

    fun getData(): List<Data> = if (data.isEmpty()) Collections.emptyList<Data>() else data

    class Data(paramValue: String?, textValue: String?) {
        private val param: String? = paramValue
        private val text: String? = textValue

        fun getParam(): String = param ?: ""

        fun getText(): String = text ?: ""
    }

    companion object {
        private val D_TAG_PATTERN = Pattern.compile(
            "<d\\s+[^>]*\\bp\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</d>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        @JvmStatic
        fun fromXml(xml: String?): Danmu {
            val danmu = Danmu()
            if (TextUtils.isEmpty(xml)) return danmu
            val fixedXml = escapeIllegalEntities(xml ?: return danmu)
            if (parseByXmlPull(danmu, fixedXml)) return danmu
            danmu.data.clear()
            parseByTag(danmu, xml)
            return danmu
        }

        private fun parseByXmlPull(danmu: Danmu, xml: String): Boolean {
            try {
                val parser = Xml.newPullParser()
                parser.setInput(StringReader(xml))
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && "d" == parser.name) {
                        val param = parser.getAttributeValue(null, "p")
                        val text = parser.nextText()
                        if (!TextUtils.isEmpty(param) && !TextUtils.isEmpty(text)) {
                            danmu.data.add(Data(param, text))
                        }
                    }
                    eventType = parser.next()
                }
                return !danmu.data.isEmpty()
            } catch (th: Throwable) {
                return false
            }
        }

        private fun parseByTag(danmu: Danmu, xml: String) {
            val matcher = D_TAG_PATTERN.matcher(xml)
            while (matcher.find()) {
                val param = decodeXmlString(matcher.group(2))
                val text = matcher.group(3)
                if (!TextUtils.isEmpty(param) && !TextUtils.isEmpty(text)) {
                    danmu.data.add(Data(param, text))
                }
            }
        }

        private fun escapeIllegalEntities(xml: String): String {
            val builder = StringBuilder(xml.length)
            val length = xml.length
            for (i in 0 until length) {
                val ch = xml[i]
                if (ch == '&' && !isLegalEntity(xml, i + 1)) {
                    builder.append("&amp;")
                } else {
                    builder.append(ch)
                }
            }
            return builder.toString()
        }

        private fun isLegalEntity(text: String, start: Int): Boolean {
            val end = text.indexOf(';', start)
            if (end < 0 || end - start > 10) return false
            val entity = text.substring(start, end)
            if ("amp" == entity || "lt" == entity || "gt" == entity ||
                "quot" == entity || "apos" == entity
            ) {
                return true
            }
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return isHexNumber(entity, 2)
            }
            return entity.startsWith("#") && isDecimalNumber(entity, 1)
        }

        private fun isDecimalNumber(text: String, start: Int): Boolean {
            if (text.length <= start) return false
            for (i in start until text.length) {
                if (!Character.isDigit(text[i])) return false
            }
            return true
        }

        private fun isHexNumber(text: String, start: Int): Boolean {
            if (text.length <= start) return false
            for (i in start until text.length) {
                val ch = text[i]
                if (!Character.isDigit(ch) &&
                    (ch < 'a' || ch > 'f') &&
                    (ch < 'A' || ch > 'F')
                ) {
                    return false
                }
            }
            return true
        }

        private fun decodeXmlString(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&gt;", ">")
                .replace("&lt;", "<")
        }
    }
}
