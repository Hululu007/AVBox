package com.github.tvbox.osc.ui.activity

import android.text.TextUtils
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.util.LOG
import com.google.gson.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

internal object LiveEpgParser {

    internal fun getFirstPartBeforeSpace(str: String?): String? {
        if (str.isNullOrEmpty()) return str
        val spaceIndex = str.indexOf(' ')
        return if (spaceIndex == -1) str else str.substring(0, spaceIndex)
    }
    internal fun buildEpgUrl(address: String, epgTagName: String, date: Date, timeFormat: SimpleDateFormat): String {
        return when {
            address.contains("{name}") || address.contains("{date}") ->
                address.replace("{name}", encodeEpgParam(epgTagName)).replace("{date}", timeFormat.format(date))
            isXmlEpgAddress(address) -> address
            else ->
                address + (if (address.contains("?")) "&" else "?") +
                        "ch=" + encodeEpgParam(epgTagName) + "&date=" + timeFormat.format(date)
        }
    }
    internal fun encodeEpgParam(value: String?): String {
        return try {
            URLEncoder.encode(value ?: "", "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            value ?: ""
        }
    }
    internal fun buildEpgQueryNames(channelName: String, channelNameReal: String, epgTagName: String): ArrayList<String> {
        val queryNames = ArrayList<String>()
        addEpgQueryName(queryNames, epgTagName)
        addEpgQueryName(queryNames, channelNameReal)
        addEpgQueryName(queryNames, normalizeEpgChannelName(getFirstPartBeforeSpace(channelName) ?: ""))
        addEpgQueryName(queryNames, getFirstPartBeforeSpace(channelName))
        addEpgQueryName(queryNames, channelName)
        if (queryNames.isEmpty()) queryNames.add("")
        return queryNames
    }
    internal fun addEpgQueryName(queryNames: ArrayList<String>, name: String?) {
        if (name == null) return
        val trimName = name.trim { it <= ' ' }
        if (trimName.isEmpty() || queryNames.contains(trimName)) return
        queryNames.add(trimName)
    }
    internal fun isTemplateEpgAddress(address: String?): Boolean {
        return address != null && (address.contains("{name}") || address.contains("{date}"))
    }
    internal fun isXmlEpgAddress(address: String?): Boolean {
        if (address == null) return false
        var lowerAddress = address.lowercase(Locale.ROOT)
        val queryIndex = lowerAddress.indexOf("?")
        if (queryIndex >= 0) {
            lowerAddress = lowerAddress.substring(0, queryIndex)
        }
        return lowerAddress.endsWith(".xml")
    }
    internal fun isXmlEpgResponse(response: String?): Boolean {
        if (response == null) return false
        val trimResponse = response.trim { it <= ' ' }
        return trimResponse.startsWith("<?xml") || trimResponse.startsWith("<tv") || trimResponse.contains("<programme")
    }
    internal fun parseJsonEpg(response: String, date: Date): ArrayList<Epginfo> {
        val epgList = ArrayList<Epginfo>()
        val jsonObject = JSONObject(response)
        val channelNameStr = jsonObject.optString("channel_name", jsonObject.optString("channel", ""))
        if (isUnavailableEpgText(channelNameStr)) {
            return epgList
        }
        val epgArray = findJsonEpgArray(jsonObject) ?: return epgList
        for (i in 0 until epgArray.length()) {
            val item = epgArray.optJSONObject(i) ?: continue
            val title = cleanEpgTitle(item.optString("title", item.optString("name", "")))
            if (TextUtils.isEmpty(title) || isUnavailableEpgText(title)) continue
            val startText = item.optString("start", item.optString("start_time", item.optString("starttime", "")))
            val endText = item.optString("end", item.optString("end_time", item.optString("endtime", "")))
            val startDate = parseJsonEpgDate(date, startText)
            val endDate = parseJsonEpgDate(date, endText)
            if (startDate == null || endDate == null) continue
            var fixedEnd = endDate
            if (!fixedEnd.after(startDate)) {
                fixedEnd = Date(fixedEnd.time + TimeUnit.DAYS.toMillis(1))
            }
            epgList.add(createXmlEpgInfo(date, title, startDate, fixedEnd, epgList.size))
        }
        return epgList
    }
    internal fun findJsonEpgArray(jsonObject: JSONObject): JSONArray? {
        var epgArray = jsonObject.optJSONArray("epg_data")
        if (epgArray != null) return epgArray
        epgArray = jsonObject.optJSONArray("data")
        if (epgArray != null) return epgArray
        epgArray = jsonObject.optJSONArray("list")
        if (epgArray != null) return epgArray
        val dataObject = jsonObject.optJSONObject("data")
        if (dataObject != null) {
            epgArray = dataObject.optJSONArray("epg_data")
            if (epgArray != null) return epgArray
            epgArray = dataObject.optJSONArray("list")
        }
        return epgArray
    }
    internal fun parseJsonEpgDate(date: Date, timeText: String?): Date? {
        if (timeText.isNullOrEmpty() || timeText.trim { it <= ' ' }.isEmpty()) return null
        val trimText = timeText.trim { it <= ' ' }
        for (pattern in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try {
                val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
                return dateFormat.parse(trimText)
            } catch (ignored: ParseException) {
                LOG.d("LiveEpgParser", "date '$trimText' doesn't match $pattern")
            }
        }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        dayFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        val dayText = dayFormat.format(date)
        for (pattern in arrayOf("HH:mm:ss", "HH:mm")) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd $pattern", Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
                return dateFormat.parse("$dayText $trimText")
            } catch (ignored: ParseException) {
                LOG.d("LiveEpgParser", "'$dayText $trimText' doesn't match $pattern")
            }
        }
        return null
    }
    internal fun cleanEpgTitle(title: String?): String {
        if (title == null) return ""
        return title.replace(" --免费使用", "").replace("--免费使用", "").trim { it <= ' ' }
    }
    internal fun isUnavailableEpgText(text: String?): Boolean {
        return text != null && (text.contains("未提供") || text.contains("暂无"))
    }
    internal fun normalizeEpgChannelName(channelName: String?): String {
        if (channelName == null) return ""
        val trimName = channelName.trim { it <= ' ' }
        val compactName = trimName.replace("-", "").replace(" ", "")
        val cctvMatcher = Pattern.compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|$)").matcher(compactName)
        if (cctvMatcher.matches()) {
            return cctvMatcher.group(1)!!.uppercase(Locale.ROOT)
        }
        if (compactName.uppercase(Locale.ROOT).startsWith("CCTV")) {
            return compactName.uppercase(Locale.ROOT)
        }
        return trimName
    }
    internal fun parseXmlEpg(xml: String, channelName: String, date: Date): ArrayList<Epginfo> {
        val epgList = ArrayList<Epginfo>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isIgnoringComments = true
            factory.isCoalescing = true
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (ignored: Exception) {
                LOG.d("LiveEpgParser", "XML factory rejects XXE-hardening feature, parse continues")
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document: Document = builder.parse(InputSource(StringReader(xml)))
            document.documentElement.normalize()

            val targetName = normalizeEpgChannelName(channelName)
            val channelIds = ArrayList<String>()
            val channelNodes = document.getElementsByTagName("channel")
            for (i in 0 until channelNodes.length) {
                val channelNode = channelNodes.item(i)
                if (channelNode.nodeType != Node.ELEMENT_NODE) continue
                val channelElement = channelNode as Element
                val channelId = channelElement.getAttribute("id")
                if (targetName == normalizeEpgChannelName(channelId)) {
                    channelIds.add(channelId)
                    continue
                }
                val displayNameNodes = channelElement.getElementsByTagName("display-name")
                for (j in 0 until displayNameNodes.length) {
                    val displayName = displayNameNodes.item(j).textContent
                    if (targetName == normalizeEpgChannelName(displayName)) {
                        channelIds.add(channelId)
                        break
                    }
                }
            }

            val dayStart = getDayStart(date)
            val dayEnd = Date(dayStart.time + TimeUnit.DAYS.toMillis(1))
            val programmeNodes = document.getElementsByTagName("programme")
            for (i in 0 until programmeNodes.length) {
                val programmeNode = programmeNodes.item(i)
                if (programmeNode.nodeType != Node.ELEMENT_NODE) continue
                val programmeElement = programmeNode as Element
                val programmeChannel = programmeElement.getAttribute("channel")
                if (!channelIds.contains(programmeChannel) && targetName != normalizeEpgChannelName(programmeChannel)) {
                    continue
                }
                val startDate = parseXmlTvDate(programmeElement.getAttribute("start"))
                val endDate = parseXmlTvDate(programmeElement.getAttribute("stop"))
                if (startDate == null || endDate == null || !endDate.after(startDate)) continue
                if (!startDate.before(dayEnd) || !endDate.after(dayStart)) continue
                var title = ""
                val titleNodes = programmeElement.getElementsByTagName("title")
                if (titleNodes.length > 0) {
                    title = titleNodes.item(0).textContent
                }
                epgList.add(createXmlEpgInfo(date, title, startDate, endDate, epgList.size))
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
        return epgList
    }
    internal fun getDayStart(date: Date): Date {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        dayFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        return dayFormat.parse(dayFormat.format(date)) ?: date
    }
    internal fun parseXmlTvDate(dateText: String?): Date? {
        if (dateText.isNullOrEmpty() || dateText.trim { it <= ' ' }.isEmpty()) return null
        val trimDate = dateText.trim { it <= ' ' }
        try {
            return SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).parse(trimDate)
        } catch (ignored: ParseException) {
            LOG.d("LiveEpgParser", "xmltv date '$trimDate' doesn't match yyyyMMddHHmmss Z")
        }
        try {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
            return dateFormat.parse(trimDate)
        } catch (ignored: ParseException) {
            LOG.d("LiveEpgParser", "xmltv date '$trimDate' doesn't match yyyyMMddHHmmss")
        }
        return null
    }
    internal fun createXmlEpgInfo(epgDate: Date, title: String, startDate: Date, endDate: Date, index: Int): Epginfo {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val epgInfo = Epginfo(epgDate, title, epgDate, timeFormat.format(startDate), timeFormat.format(endDate), index)
        epgInfo.startdateTime = startDate
        epgInfo.enddateTime = endDate
        epgInfo.start = timeFormat.format(startDate)
        epgInfo.end = timeFormat.format(endDate)
        epgInfo.originStart = epgInfo.start
        epgInfo.originEnd = epgInfo.end
        epgInfo.datestart = epgInfo.start.replace(":", "").toInt()
        epgInfo.dateend = epgInfo.end.replace(":", "").toInt()
        return epgInfo
    }
    internal fun getCatchupValue(catchupObj: JsonObject?, key: String): String {
        if (catchupObj == null || !catchupObj.has(key) || catchupObj.get(key).isJsonNull) return ""
        return try {
            catchupObj.get(key).asString
        } catch (ignored: Throwable) {
            LOG.d("LiveEpgParser", "catchup key '$key' is not a string")
            ""
        }
    }
    internal fun hasCatchupSource(catchupObj: JsonObject?): Boolean {
        return getCatchupValue(catchupObj, "source").isNotEmpty()
    }
    internal fun formatCatchupUrl(url: String, catchupObj: JsonObject, epg: Epginfo): String {
        val source = formatCatchupSource(getCatchupValue(catchupObj, "source"), epg)
        if ("default".equals(getCatchupValue(catchupObj, "type"), ignoreCase = true)) return source
        return appendCatchupUrl(url, getCatchupValue(catchupObj, "replace"), source)
    }
    internal fun appendCatchupUrl(url: String, replace: String, source: String): String {
        var replayUrl = url
        var finalSource = source
        val parts = replace.split(",".toRegex(), 2).toTypedArray()
        if (parts.size == 2 && parts[0].isNotEmpty()) {
            try {
                replayUrl = replayUrl.replace(parts[0].toRegex(), parts[1])
            } catch (ignored: Throwable) {
                LOG.d("LiveEpgParser", "catchup replace pattern '${parts[0]}' invalid, url kept")
            }
        }
        val queryIndex = replayUrl.indexOf('?')
        if (queryIndex >= 0 && queryIndex < replayUrl.length - 1) finalSource = finalSource.replace("?", "&")
        return replayUrl + finalSource
    }
    internal fun formatCatchupSource(source: String, epg: Epginfo): String {
        val matcher = CATCHUP_TOKEN_PATTERN.matcher(source)
        val result = StringBuffer()
        while (matcher.find()) {
            val token = matcher.group(1)!!
            matcher.appendReplacement(result, Matcher.quoteReplacement(formatCatchupToken(token, epg)))
        }
        matcher.appendTail(result)
        return result.toString()
    }
    internal fun formatCatchupToken(token: String, epg: Epginfo): String {
        val matcher = CATCHUP_TAG_PATTERN.matcher(token)
        if (!matcher.find()) return ""
        val tag = matcher.group(1)!!
        if (tag.startsWith("utcend:")) return (epg.enddateTime!!.time / 1000).toString()
        if (tag.startsWith("utc:")) return (epg.startdateTime!!.time / 1000).toString()
        val bracketIndex = tag.indexOf(')')
        if (tag.startsWith("(b") && bracketIndex >= 0) return formatCatchupTime(epg.startdateTime!!, tag.substring(bracketIndex + 1))
        if (tag.startsWith("(e") && bracketIndex >= 0) return formatCatchupTime(epg.enddateTime!!, tag.substring(bracketIndex + 1))
        return ""
    }
    internal fun formatCatchupTime(time: Date, pattern: String): String {
        if ("timestamp" == pattern) return (time.time / 1000).toString()
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(time)
        } catch (ignored: IllegalArgumentException) {
            LOG.d("LiveEpgParser", "catchup time pattern '$pattern' invalid")
            ""
        }
    }
    internal fun getCatchupDurationSeconds(epg: Epginfo?): Int {
        if (epg == null || epg.startdateTime == null || epg.enddateTime == null) return 0
        val duration = max(0L, epg.enddateTime!!.time - epg.startdateTime!!.time) / 1000
        return if (duration > Int.MAX_VALUE) Int.MAX_VALUE else duration.toInt()
    }
    internal fun durationToString(duration: Int): String {
        val dur = max(duration, 0) / 1000
        val hour = dur / 3600
        val min = dur / 60 % 60
        val sec = dur % 60
        return if (hour > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hour, min, sec)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }
    }
        private val CATCHUP_TOKEN_PATTERN = Pattern.compile("(\\$?\\{[^}]*\\})")
        private val CATCHUP_TAG_PATTERN = Pattern.compile("\\{([^}]*)\\}")
}
