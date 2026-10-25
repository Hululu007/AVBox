package com.github.avbox.osc.bean

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class EpgInfo(epgDateIn: Date, titleIn: String, date: Date, startTime: String, endTime: String, pos: Int) {

    @JvmField
    var startdateTime: Date? = null
    @JvmField
    var enddateTime: Date? = null
    @JvmField
    var datestart: Int = 0
    @JvmField
    var dateend: Int = 0
    @JvmField
    var title: String? = null
    @JvmField
    var originStart: String? = null
    @JvmField
    var originEnd: String? = null
    @JvmField
    var start: String? = null
    @JvmField
    var end: String? = null
    @JvmField
    var index: Int = 0
    @JvmField
    var epgDate: Date? = null
    @JvmField
    var currentEpgDate: String? = null
    @JvmField
    var timeFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd")

    init {
        epgDate = epgDateIn
        currentEpgDate = timeFormat.format(epgDate)
        title = titleIn
        originStart = startTime
        originEnd = endTime
        index = pos
        val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd")
        simpleDateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        val userSimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
        userSimpleDateFormat.timeZone = TimeZone.getDefault()
        val startParsed = userSimpleDateFormat.parse(simpleDateFormat.format(date) + " " + startTime + ":00 GMT+8:00", ParsePosition(0))
        val endParsed = userSimpleDateFormat.parse(simpleDateFormat.format(date) + " " + endTime + ":00 GMT+8:00", ParsePosition(0))
        startdateTime = startParsed
        enddateTime = endParsed
        if (startParsed != null && endParsed != null && !endParsed.after(startParsed)) {
            val calendar = Calendar.getInstance()
            calendar.time = endParsed
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            enddateTime = calendar.time
        }
        val zoneFormat = SimpleDateFormat("HH:mm")
        val startText = zoneFormat.format(requireNotNull(startdateTime) { "epg start time parse failed" })
        val endText = zoneFormat.format(requireNotNull(enddateTime) { "epg end time parse failed" })
        start = startText
        end = endText
        datestart = startText.replace(":", "").toInt()
        dateend = endText.replace(":", "").toInt()
    }
}
