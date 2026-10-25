/**
 * Class that represents the .ASS and .SSA subtitle file format
 *
 * <br><br>
 * Copyright (c) 2012 J. David Requejo <br>
 * j[dot]david[dot]requejo[at] Gmail
 * <br><br>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 * <br><br>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <br><br>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * @author J. David REQUEJO
 *
 */

package com.github.avbox.osc.subtitle.format

import com.github.avbox.osc.subtitle.model.Style
import com.github.avbox.osc.subtitle.model.Subtitle
import com.github.avbox.osc.subtitle.model.Time
import com.github.avbox.osc.subtitle.model.TimedTextObject
import com.github.avbox.osc.util.RegexUtils

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList

class FormatASS : TimedTextFileFormat {

    @Throws(IOException::class)
    override fun parseFile(fileName: String, `is`: InputStream): TimedTextObject {

        val tto = TimedTextObject()
        tto.fileName = fileName

        var caption = Subtitle()
        var style: Style

        var timer = 100f

        var isASS = false

        var styleFormat: Array<String>
        var dialogueFormat: Array<String>

        val `in` = InputStreamReader(`is`)
        val br = BufferedReader(`in`)

        var line: String?
        var lineCounter = 0
        val styling = requireNotNull(tto.styling)
        val captions = requireNotNull(tto.captions)

        fun truncatedResult(): TimedTextObject {
            tto.warnings += "unexpected end of file, maybe last caption is not complete.\n\n"
            tto.built = true
            return tto
        }
        try {
            line = br.readLine()
            lineCounter++
            while (line != null) {
                line = line.trim { it <= ' ' }
                if (line.startsWith("[")) {
                    if (line.equals("[Script info]", ignoreCase = true)) {
                        lineCounter++
                        val scriptInfoFirst = br.readLine()
                        if (scriptInfoFirst == null) {
                            return truncatedResult()
                        }
                        val scriptInfoText = scriptInfoFirst.trim { it <= ' ' }
                        line = scriptInfoText
                        var scriptInfo: String = scriptInfoText
                        while (!scriptInfo.startsWith("[")) {
                            if (scriptInfo.startsWith("Title:")) {
                                val titleArr = RegexUtils.getPattern(":").split(scriptInfo)
                                tto.title = if (titleArr.size > 1) titleArr[1].trim { it <= ' ' } else ""
                            } else if (scriptInfo.startsWith("Original Script:")) {
                                val authorArr = RegexUtils.getPattern(":").split(scriptInfo)
                                tto.author = if (authorArr.size > 1) authorArr[1].trim { it <= ' ' } else ""
                            } else if (scriptInfo.startsWith("Script Type:")) {
                                if (RegexUtils.getPattern(":").split(scriptInfo)[1].trim { it <= ' ' }.equals("v4.00+", ignoreCase = true)) isASS = true
                                else if (!RegexUtils.getPattern(":").split(scriptInfo)[1].trim { it <= ' ' }.equals("v4.00", ignoreCase = true))
                                    tto.warnings += "Script version is older than 4.00, it may produce parsing errors."
                            } else if (scriptInfo.startsWith("Timer:"))
                                timer = RegexUtils.getPattern(":").split(scriptInfo)[1].trim { it <= ' ' }.replace(',', '.').toFloat()
                            lineCounter++
                            scriptInfo = br.readLine()?.trim { it <= ' ' } ?: return truncatedResult()
                        }

                    } else if (line.equals("[v4 Styles]", ignoreCase = true)
                            || line.equals("[v4 Styles+]", ignoreCase = true)
                            || line.equals("[v4+ Styles]", ignoreCase = true)) {
                        if (line.contains("+") && isASS == false) {
                            isASS = true
                            tto.warnings += "ScriptType should be set to v4:00+ in the [Script Info] section.\n\n"
                        }
                        lineCounter++
                        val stylesLine = br.readLine()
                        if (stylesLine == null) {
                            return truncatedResult()
                        }
                        var formatLine: String = stylesLine
                        if (!formatLine.startsWith("Format:")) {
                            tto.warnings += "Format: (format definition) expected at line " + formatLine + " for the styles section\n\n"
                            while (!formatLine.startsWith("Format:")) {
                                lineCounter++
                                formatLine = br.readLine() ?: return truncatedResult()
                            }
                        }
                        line = formatLine
                        styleFormat = RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' })
                        lineCounter++
                        val styleFirst = br.readLine()
                        if (styleFirst == null) {
                            return truncatedResult()
                        }
                        var styleLine: String = styleFirst
                        while (!styleLine.startsWith("Style:")) {
                            tto.warnings += "Style: (format definition) expected at line " + styleLine + " for the styles section\n\n"
                            lineCounter++
                            styleLine = br.readLine() ?: return truncatedResult()
                        }
                        line = styleLine
                        style = parseStyleForASS(RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' }), styleFormat, lineCounter, isASS, tto.warnings)
                        styling.put(style.iD, style)

                    } else if (line.trim { it <= ' ' }.equals("[Events]", ignoreCase = true)) {
                        lineCounter++
                        val eventsLine = br.readLine()
                        tto.warnings += "Only dialogue events are considered, all other events are ignored.\n\n"
                        if (eventsLine == null) {
                            return truncatedResult()
                        }
                        var eventsFormatLine: String = eventsLine
                        if (!eventsFormatLine.startsWith("Format:")) {
                            tto.warnings += "Format: (format definition) expected at line " + eventsFormatLine + " for the events section\n\n"
                            while (!eventsFormatLine.startsWith("Format:")) {
                                lineCounter++
                                eventsFormatLine = br.readLine() ?: return truncatedResult()
                            }
                        }
                        line = eventsFormatLine
                        dialogueFormat = RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(line)[1].trim { it <= ' ' })
                        lineCounter++
                        val dialogueFirst = br.readLine()
                        if (dialogueFirst == null) {
                            return truncatedResult()
                        }
                        var dialogueLine: String = dialogueFirst
                        while (!dialogueLine.startsWith("[")) {
                            if (dialogueLine.startsWith("Dialogue:")) {
                                caption = parseDialogueForASS(RegexUtils.getPattern(",").split(RegexUtils.getPattern(":").split(dialogueLine, 2)[1].trim { it <= ' ' }, 10), dialogueFormat, timer, tto)
                                val start = caption.start ?: return truncatedResult()
                                var key = start.mseconds
                                while (captions.containsKey(key)) key++
                                captions.put(key, caption)
                            }
                            lineCounter++
                            dialogueLine = br.readLine() ?: return truncatedResult()
                        }

                    } else if (line.trim { it <= ' ' }.equals("[Fonts]", ignoreCase = true) || line.trim { it <= ' ' }.equals("[Graphics]", ignoreCase = true)) {
                        tto.warnings += "The section " + line.trim { it <= ' ' } + " is not supported for conversion, all information there will be lost.\n\n"
                    } else {
                        tto.warnings += "Unrecognized section: " + line.trim { it <= ' ' } + " all information there is ignored."
                    }
                }
                line = br.readLine()
                lineCounter++
            }
            tto.cleanUnusedStyles()

        } finally {
            `is`.close()
        }

        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): Array<String>? {

        if (!tto.built)
            return null

        var index = 0
        val styling = tto.styling ?: return null
        val captions = tto.captions ?: return null
        val file = ArrayList<String>(30 + styling.size + captions.size)

        file.add(index++, "[Script Info]")
        var title = "Title: "
        val ttoTitle = tto.title
        if (ttoTitle.isNullOrEmpty())
            title += tto.fileName
        else title += ttoTitle
        file.add(index++, title)
        var author = "Original Script: "
        val ttoAuthor = tto.author
        if (ttoAuthor.isNullOrEmpty())
            author += "Unknown"
        else author += ttoAuthor
        file.add(index++, author)
        val copyright = tto.copyrigth
        if (!copyright.isNullOrEmpty())
            file.add(index++, "; " + copyright)
        val description = tto.description
        if (!description.isNullOrEmpty())
            file.add(index++, "; " + description)
        file.add(index++, "; Converted by the Online Subtitle Converter developed by J. David Requejo")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Script Type: V4.00+")
        else file.add(index++, "Script Type: V4.00")
        file.add(index++, "Collisions: Normal")
        file.add(index++, "Timer: 100,0000")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "WrapStyle: 1")
        file.add(index++, "")

        if (tto.useASSInsteadOfSSA)
            file.add(index++, "[V4+ Styles]")
        else file.add(index++, "[V4 Styles]")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        else file.add(index++, "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding")
        val itrS = styling.values.iterator()
        while (itrS.hasNext()) {
            var styleLine = "Style: "
            val current = itrS.next()
            styleLine += current.iD + ","
            styleLine += current.font + ","
            styleLine += current.fontSize + ","
            styleLine += colorsForASS(tto.useASSInsteadOfSSA, current)
            styleLine += optionsForASS(tto.useASSInsteadOfSSA, current)
            styleLine += "1,2,2,"
            styleLine += alignForASS(tto.useASSInsteadOfSSA, current.textAlign)
            styleLine += ",0,0,0,"
            if (!tto.useASSInsteadOfSSA) styleLine += "0,"
            styleLine += "0"

            file.add(index++, styleLine)
        }
        file.add(index++, "")

        file.add(index++, "[Events]")
        if (tto.useASSInsteadOfSSA)
            file.add(index++, "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        else file.add(index++, "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        val itrC = captions.values.iterator()
        while (itrC.hasNext()) {
            var line = "Dialogue: 0,"
            val current = itrC.next()
            val start = requireNotNull(current.start)
            val end = requireNotNull(current.end)
            if (tto.offset != 0) {
                start.mseconds += tto.offset
                end.mseconds += tto.offset
            }
            line += start.getTime("h:mm:ss.cs") + ","
            line += end.getTime("h:mm:ss.cs") + ","
            if (tto.offset != 0) {
                start.mseconds -= tto.offset
                end.mseconds -= tto.offset
            }
            val currentStyle = current.style
            if (currentStyle != null)
                line += currentStyle.iD
            else
                line += "Default"
            line += ",,0000,0000,0000,,"

            line += current.content.orEmpty().replace(Regex("<br />"), "\uFFFDN").replace(Regex("\\<.*?\\>"), "").replace('\uFFFD', '\\')
            file.add(index++, line)
        }
        file.add(index++, "")

        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }

    private fun parseStyleForASS(line: Array<String>, styleFormat: Array<String>, index: Int, isASS: Boolean, warnings: String?): Style {

        var warnings = warnings
        val newStyle = Style(Style.defaultID())
        if (line.size != styleFormat.size) {
            warnings += "incorrectly formated line at " + index + "\n\n"
        } else {
            for (i in 0 until styleFormat.size) {
                if (styleFormat[i].trim { it <= ' ' }.equals("Name", ignoreCase = true)) {
                    newStyle.iD = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Fontname", ignoreCase = true)) {
                    newStyle.font = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Fontsize", ignoreCase = true)) {
                    newStyle.fontSize = line[i].trim { it <= ' ' }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("PrimaryColour", ignoreCase = true)) {
                    val color = line[i].trim { it <= ' ' }
                    if (isASS) {
                        if (color.startsWith("&H")) newStyle.color = Style.rgbValue("&HAABBGGRR", color)
                        else newStyle.color = Style.rgbValue("decimalCodedAABBGGRR", color)
                    } else {
                        if (color.startsWith("&H")) newStyle.color = Style.rgbValue("&HBBGGRR", color)
                        else newStyle.color = Style.rgbValue("decimalCodedBBGGRR", color)
                    }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("BackColour", ignoreCase = true)) {
                    val color = line[i].trim { it <= ' ' }
                    if (isASS) {
                        if (color.startsWith("&H")) newStyle.backgroundColor = Style.rgbValue("&HAABBGGRR", color)
                        else newStyle.backgroundColor = Style.rgbValue("decimalCodedAABBGGRR", color)
                    } else {
                        if (color.startsWith("&H")) newStyle.backgroundColor = Style.rgbValue("&HBBGGRR", color)
                        else newStyle.backgroundColor = Style.rgbValue("decimalCodedBBGGRR", color)
                    }
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Bold", ignoreCase = true)) {
                    newStyle.bold = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Italic", ignoreCase = true)) {
                    newStyle.italic = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Underline", ignoreCase = true)) {
                    newStyle.underline = java.lang.Boolean.parseBoolean(line[i].trim { it <= ' ' })
                } else if (styleFormat[i].trim { it <= ' ' }.equals("Alignment", ignoreCase = true)) {
                    val placement = line[i].trim { it <= ' ' }.toInt()
                    if (isASS) {
                        when (placement) {
                            1 -> newStyle.textAlign = "bottom-left"
                            2 -> newStyle.textAlign = "bottom-center"
                            3 -> newStyle.textAlign = "bottom-right"
                            4 -> newStyle.textAlign = "mid-left"
                            5 -> newStyle.textAlign = "mid-center"
                            6 -> newStyle.textAlign = "mid-right"
                            7 -> newStyle.textAlign = "top-left"
                            8 -> newStyle.textAlign = "top-center"
                            9 -> newStyle.textAlign = "top-right"
                            else -> warnings += "undefined alignment for style at line " + index + "\n\n"
                        }
                    } else {
                        when (placement) {
                            9 -> newStyle.textAlign = "bottom-left"
                            10 -> newStyle.textAlign = "bottom-center"
                            11 -> newStyle.textAlign = "bottom-right"
                            1 -> newStyle.textAlign = "mid-left"
                            2 -> newStyle.textAlign = "mid-center"
                            3 -> newStyle.textAlign = "mid-right"
                            5 -> newStyle.textAlign = "top-left"
                            6 -> newStyle.textAlign = "top-center"
                            7 -> newStyle.textAlign = "top-right"
                            else -> warnings += "undefined alignment for style at line " + index + "\n\n"
                        }
                    }
                }

            }
        }

        return newStyle
    }

    private fun parseDialogueForASS(line: Array<String>, dialogueFormat: Array<String>, timer: Float, tto: TimedTextObject): Subtitle {

        val newCaption = Subtitle()
        val styling = requireNotNull(tto.styling)

        val captionText = line[9]
        newCaption.content = captionText.replace(Regex("\\{.*?\\}"), "").replace("\n", "<br />").replace("\\N", "<br />")

        for (i in 0 until dialogueFormat.size) {
            if (dialogueFormat[i].trim { it <= ' ' }.equals("Style", ignoreCase = true)) {
                newCaption.lyricCurrent = "PLAYING_CENTER" == line[i].trim { it <= ' ' }
                val s = styling.get(line[i].trim { it <= ' ' })
                if (s != null)
                    newCaption.style = s
                else
                    tto.warnings += "undefined style: " + line[i].trim { it <= ' ' } + "\n\n"
            } else if (dialogueFormat[i].trim { it <= ' ' }.equals("Start", ignoreCase = true)) {
                newCaption.start = Time("h:mm:ss.cs", line[i].trim { it <= ' ' })
            } else if (dialogueFormat[i].trim { it <= ' ' }.equals("End", ignoreCase = true)) {
                newCaption.end = Time("h:mm:ss.cs", line[i].trim { it <= ' ' })
            }
        }

        if (timer != 100f) {
            val start = newCaption.start
            val end = newCaption.end
            if (start != null && end != null) {
                start.mseconds = (start.mseconds / (timer / 100)).toInt()
                end.mseconds = (end.mseconds / (timer / 100)).toInt()
            } else
                tto.warnings += "dialogue without start or end time, timer adjustment skipped\n\n"
        }
        return newCaption
    }

    private fun colorsForASS(useASSInsteadOfSSA: Boolean, style: Style): String {
        val color = requireNotNull(style.color) { "style color is missing" }
        val backgroundColor = requireNotNull(style.backgroundColor) { "style background color is missing" }
        val rgb = color.substring(4, 6) + color.substring(2, 4) + color.substring(0, 2)
        val bgr = backgroundColor.substring(4, 6) + backgroundColor.substring(2, 4) + backgroundColor.substring(0, 2)
        var colors: String
        if (useASSInsteadOfSSA)
            colors = ("00" + rgb).toInt(16).toString() + ",16777215,0," + ("80" + bgr).toLong(16) + ","
        else
            colors = rgb.toLong(16).toString() + ",16777215,0," + bgr.toLong(16) + ","
        return colors
    }

    private fun optionsForASS(useASSInsteadOfSSA: Boolean, style: Style): String {
        var options: String
        if (style.bold)
            options = "-1,"
        else
            options = "0,"
        if (style.italic)
            options += "-1,"
        else
            options += "0,"
        if (useASSInsteadOfSSA) {
            if (style.underline)
                options += "-1,"
            else
                options += "0,"
            options += "0,100,100,0,0,"
        }
        return options
    }

    private fun alignForASS(useASSInsteadOfSSA: Boolean, align: String?): Int {
        if (useASSInsteadOfSSA) {
            var placement = 2
            if ("bottom-left" == align)
                placement = 1
            else if ("bottom-center" == align)
                placement = 2
            else if ("bottom-right" == align)
                placement = 3
            else if ("mid-left" == align)
                placement = 4
            else if ("mid-center" == align)
                placement = 5
            else if ("mid-right" == align)
                placement = 6
            else if ("top-left" == align)
                placement = 7
            else if ("top-center" == align)
                placement = 8
            else if ("top-right" == align)
                placement = 9

            return placement
        } else {

            var placement = 10
            if ("bottom-left" == align)
                placement = 9
            else if ("bottom-center" == align)
                placement = 10
            else if ("bottom-right" == align)
                placement = 11
            else if ("mid-left" == align)
                placement = 1
            else if ("mid-center" == align)
                placement = 2
            else if ("mid-right" == align)
                placement = 3
            else if ("top-left" == align)
                placement = 5
            else if ("top-center" == align)
                placement = 6
            else if ("top-right" == align)
                placement = 7

            return placement
        }
    }

}
