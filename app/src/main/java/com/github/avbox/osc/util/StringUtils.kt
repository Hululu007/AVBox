package com.github.avbox.osc.util

class StringUtils {

    companion object {

        @JvmStatic
        fun isEmpty(str: CharSequence?): Boolean {
            return str == null || str.length == 0
        }

        @JvmStatic
        fun isNotEmpty(str: CharSequence?): Boolean {
            return !isEmpty(str)
        }

        @JvmStatic
        fun isNull(obj: Any?): Boolean {
            return obj == null
        }

        @JvmStatic
        fun isNotNull(obj: Any?): Boolean {
            return !isNull(obj)
        }

        @JvmStatic
        fun isEmpty(obj: Any?): Boolean {
            if (obj == null) return true
            else if (obj is CharSequence) return obj.length == 0
            else if (obj is Collection<*>) return obj.isEmpty()
            else if (obj is Map<*, *>) return obj.isEmpty()
            else if (obj.javaClass.isArray) return java.lang.reflect.Array.getLength(obj) == 0

            return false
        }

        @JvmStatic
        fun isNotEmpty(obj: Any?): Boolean {
            return !isEmpty(obj)
        }

        private val U2028 = String(byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0xA8.toByte()))
        private val U2029 = String(byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0xA9.toByte()))

        @JvmStatic
        fun escapeJavaScriptString(line: String): String {
            val sb = StringBuilder()
            for (i in line.indices) {
                val c = line[i]
                when (c) {
                    '"', '\'', '\\' -> {
                        sb.append('\\')
                        sb.append(c)
                    }

                    '\n' -> sb.append("\\n")

                    '\r' -> sb.append("\\r")

                    else -> sb.append(c)
                }
            }

            return sb.toString()
                .replace(U2028, "\u2028")
                .replace(U2029, "\u2029")
        }

        @JvmStatic
        fun getBaseUrl(url: String?): String? {
            if (isEmpty(url)) {
                return url
            }
            val value = url ?: return url
            val baseUrls = value.replace("http://", "").replace("https://", "")
            val baseUrl2 = RegexUtils.getPattern("/").split(baseUrls)[0]
            val baseUrl: String
            if (url.startsWith("https")) {
                baseUrl = "https://$baseUrl2"
            } else {
                baseUrl = "http://$baseUrl2"
            }
            return baseUrl
        }

        @JvmStatic
        fun arrayToString(list: Array<String?>?, fromIndex: Int, cha: String?): String? {
            return arrayToString(list, fromIndex, if (list == null) 0 else list.size, cha)
        }

        @JvmStatic
        fun arrayToString(list: Array<String?>?, fromIndex: Int, endIndex: Int, cha: String?): String? {
            val builder = StringBuilder()
            if (list == null || list.size <= fromIndex) {
                return ""
            } else if (list.size <= 1) {
                return list[0]
            } else {
                builder.append(list[fromIndex])
            }
            var i = 1 + fromIndex
            while (i < list.size && i < endIndex) {
                builder.append(cha).append(list[i])
                i++
            }
            return builder.toString()
        }

        @JvmStatic
        fun listToString(list: List<String?>?, cha: String?): String? {
            val builder = StringBuilder()
            if (list == null || list.size <= 0) {
                return ""
            } else if (list.size <= 1) {
                return list[0]
            } else {
                builder.append(list[0])
            }
            for (i in 1 until list.size) {
                builder.append(cha).append(list[i])
            }
            return builder.toString()
        }

        @JvmStatic
        fun listToString(list: List<String?>?, fromIndex: Int, cha: String?): String? {
            val builder = StringBuilder()
            if (list == null || list.size <= fromIndex) {
                return ""
            } else if (list.size <= 1) {
                return list[0]
            } else {
                builder.append(list[fromIndex])
            }
            for (i in fromIndex + 1 until list.size) {
                builder.append(cha).append(list[i])
            }
            return builder.toString()
        }

        @JvmStatic
        fun listToString(list: List<String?>?): String? {
            return listToString(list, "&&")
        }

        @JvmStatic
        fun isBlank(text: String?): Boolean {
            return trim(text).length == 0
        }

        @JvmStatic
        fun trimBlanks(str: String?): String? {
            if (str == null || str.length == 0) {
                return str
            }
            var len = str.length
            var st = 0

            while (st < len && (str[st] == '\n' || str[st] == '\r' || str[st] == '\u000C' || str[st] == '\t')) {
                st++
            }
            while (st < len && (str[len - 1] == '\n' || str[len - 1] == '\r' || str[len - 1] == '\u000C' || str[len - 1] == '\t')) {
                len--
            }
            return if (st > 0 || len < str.length) str.substring(st, len) else str
        }

        @JvmStatic
        fun trim(string: String?): String {
            if (string == null || string.length == 0 || " " == string) return ""
            val len = string.length
            var start = 0
            var end = len - 1
            while (start < end && (string[start] <= ' ' || string[start] == '　')) {
                ++start
            }
            while (start < end && (string[end] <= ' ' || string[end] == '　')) {
                --end
            }
            if (end < len) ++end
            return if (start > 0 || end < len) string.substring(start, end) else string
        }

        @JvmStatic
        fun isJsonType(text: String?): Boolean {
            var result = false
            if (isNotEmpty(text)) {
                val trimmed = trim(text)
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    result = true
                } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    result = true
                }
            }
            return result
        }

        @JvmStatic
        fun isJsonObject(text: String?): Boolean {
            var result = false
            if (isNotEmpty(text)) {
                val trimmed = trim(text)
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    result = true
                }
            }
            return result
        }

        @JvmStatic
        fun isJsonArray(text: String?): Boolean {
            var result = false
            if (isNotEmpty(text)) {
                val trimmed = trim(text)
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    result = true
                }
            }
            return result
        }

        private val BRACKET_PATTERN = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")

        private val NOISE_PATTERN = Regex("[\\s\\p{Z}\\p{P}\\p{S}]")

        @JvmStatic
        fun normalizeTitle(text: String?): String {
            if (text == null) return ""
            return text
                .replace(BRACKET_PATTERN, "")
                .replace(NOISE_PATTERN, "")
                .lowercase(java.util.Locale.ROOT)
        }
    }
}
