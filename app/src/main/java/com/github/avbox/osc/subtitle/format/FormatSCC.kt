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

import com.github.avbox.osc.subtitle.exception.FatalParsingException
import com.github.avbox.osc.subtitle.model.Style
import com.github.avbox.osc.subtitle.model.Subtitle
import com.github.avbox.osc.subtitle.model.Time
import com.github.avbox.osc.subtitle.model.TimedTextObject
import com.github.avbox.osc.util.RegexUtils

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

class FormatSCC : TimedTextFileFormat {

    @Throws(IOException::class, FatalParsingException::class)
    override fun parseFile(fileName: String, `is`: InputStream): TimedTextObject {

        val tto = TimedTextObject()
        var newCaption: Subtitle? = null

        var textBuffer = ""
        var isChannel1 = false
        var isBuffered = true

        var underlined = false
        var italics = false
        var color: String? = null

        val br = BufferedReader(InputStreamReader(`is`))

        tto.fileName = fileName
        tto.title = fileName

        var line: String? = null
        var lineCounter = 0

        fun truncatedResult(): TimedTextObject {
            tto.warnings += "unexpected end of file at line " + lineCounter +
                ", maybe last caption is not complete.\n\n"
            tto.built = true
            return tto
        }
        try {

            lineCounter++
            val header = br.readLine()
            if (header == null) {
                return truncatedResult()
            }
            if (!header.trim { it <= ' ' }.equals("Scenarist_SCC V1.0", ignoreCase = true)) {
                throw FatalParsingException(
                    "The fist line should define the file type: \"Scenarist_SCC V1.0\"")

            } else {

                createSCCStyles(tto)
                val styling = requireNotNull(tto.styling)
                val captions = requireNotNull(tto.captions)

                tto.warnings += "Only data from CC channel 1 will be extracted.\n\n"
                line = br.readLine()

                while (line != null) {
                    line = line.trim { it <= ' ' }
                    lineCounter++
                    if (!line.isEmpty()) {
                        var data = RegexUtils.getPattern("\t").split(line)
                        val currentTime = Time("h:m:s:f/fps", data[0] + "/29.97")
                        data = RegexUtils.getPattern(" ").split(data[1])
                        var j = 0
                        while (j < data.size) {
                            var word = data[j].toInt(16)

                            word = word and 0x7f7f

                            if ((word and 0x6000) != 0) {
                                if (isChannel1) {
                                    val c1 = ((word and 0xff00) ushr 8).toByte()
                                    val c2 = (word and 0x00ff).toByte()

                                    if (isBuffered) {
                                        textBuffer += decodeChar(c1)
                                        textBuffer += decodeChar(c2)
                                    } else {
                                        val caption = newCaption ?: return truncatedResult()
                                        caption.content += decodeChar(c1)
                                        caption.content += decodeChar(c2)
                                    }
                                }

                            } else if (word == 0x0000)
                                currentTime.mseconds = (currentTime.mseconds + 1000 / 29.97).toInt()
                            else {
                                if (j + 1 < data.size && data[j] == data[j + 1])
                                    j++

                                if ((word and 0x0800) == 0) {

                                    if ((word and 0x1670) == 0x1420) {
                                        if ((word and 0x0100) == 0) {
                                            isChannel1 = true
                                            word = word and 0x000f
                                            when (word) {
                                                0 -> {
                                                    isBuffered = true
                                                    textBuffer = ""
                                                }
                                                5, 6, 7 -> {
                                                    textBuffer = ""
                                                    if (newCaption != null) {
                                                        newCaption.end = currentTime
                                                        var style = ""
                                                        style += color
                                                        if (underlined)
                                                            style += "U"
                                                        if (italics)
                                                            style += "I"
                                                        val start = newCaption.start ?: return truncatedResult()
                                                        newCaption.style = styling[style]
                                                        captions[start.mseconds] = newCaption
                                                    }
                                                    newCaption = Subtitle()
                                                    newCaption.start = currentTime
                                                    isBuffered = false
                                                }
                                                9 -> {
                                                    isBuffered = false
                                                    newCaption = Subtitle()
                                                    newCaption.start = currentTime
                                                }
                                                12 -> {
                                                    if (newCaption != null) {
                                                        newCaption.end = currentTime
                                                        val start = newCaption.start
                                                        if (start != null) {
                                                            var key = start.mseconds
                                                            while (captions.containsKey(key))
                                                                key++
                                                            captions[start.mseconds] = newCaption
                                                            newCaption = Subtitle()
                                                        }
                                                    }
                                                }
                                                14 -> {
                                                    textBuffer = ""
                                                }
                                                15 -> {
                                                    newCaption = Subtitle()
                                                    newCaption.start = currentTime
                                                    newCaption.content += textBuffer
                                                }
                                                else -> {
                                                }
                                            }

                                        } else {
                                            isChannel1 = false
                                        }

                                    } else if (isChannel1) {
                                        if ((word and 0x1040) == 0x1040) {
                                            color = "white"
                                            underlined = false
                                            italics = false
                                            if (isBuffered && !textBuffer.isEmpty())
                                                textBuffer += "<br />"
                                            if (!isBuffered) {
                                                val caption = newCaption ?: return truncatedResult()
                                                if (!caption.content.isNullOrEmpty())
                                                    caption.content += "<br />"
                                            }
                                            if ((word and 0x0001) == 1)
                                                underlined = true
                                            if ((word and 0x0010) != 0x0010) {
                                                word = word and 0x000e
                                                word = (word shr 1).toShort().toInt()
                                                when (word) {
                                                    0 -> color = "white"
                                                    1 -> color = "green"
                                                    2 -> color = "blue"
                                                    3 -> color = "cyan"
                                                    4 -> color = "red"
                                                    5 -> color = "yellow"
                                                    6 -> color = "magenta"
                                                    7 -> italics = true
                                                    else -> {
                                                    }
                                                }
                                            } else {
                                                color = "white"
                                            }

                                        } else if ((word and 0x1770) == 0x1120) {
                                            if ((word and 0x001) == 1)
                                                underlined = true
                                            else
                                                underlined = false
                                            word = word and 0x000e
                                            word = (word shr 1).toShort().toInt()
                                            when (word) {
                                                0 -> {
                                                    color = "white"
                                                    italics = false
                                                }
                                                1 -> {
                                                    color = "green"
                                                    italics = false
                                                }
                                                2 -> {
                                                    color = "blue"
                                                    italics = false
                                                }
                                                3 -> {
                                                    color = "cyan"
                                                    italics = false
                                                }
                                                4 -> {
                                                    color = "red"
                                                    italics = false
                                                }
                                                5 -> {
                                                    color = "yellow"
                                                    italics = false
                                                }
                                                6 -> {
                                                    color = "magenta"
                                                    italics = false
                                                }
                                                7 -> italics = true
                                                else -> {
                                                }
                                            }
                                        } else if ((word and 0x177c) == 0x1720) {

                                        } else if ((word and 0x1770) == 0x1130) {
                                            word = word and 0x000f
                                            if (isBuffered)
                                                textBuffer += decodeSpecialChar(word)
                                            else {
                                                val caption = newCaption ?: return truncatedResult()
                                                caption.content += decodeSpecialChar(word)
                                            }
                                        } else if ((word and 0x1660) == 0x1220) {
                                            word = word and 0x011f
                                            if (isBuffered)
                                                decodeXtChar(textBuffer, word)
                                            else {
                                                val caption = newCaption ?: return truncatedResult()
                                                decodeXtChar(
                                                    caption.content,
                                                    word)
                                            }

                                        } else {
                                        }
                                    }
                                } else {
                                    isChannel1 = false
                                }

                            }
                            j++
                        }

                    }
                    line = br.readLine()

                }

                val lastCaption = newCaption ?: return truncatedResult()
                lastCaption.end = Time("h:m:s:f/fps", "99:59:59:29/29.97")
                val start = lastCaption.start
                if (start != null) {
                    var key = start.mseconds
                    while (captions.containsKey(key))
                        key++
                    captions[start.mseconds] = lastCaption
                }
                tto.cleanUnusedStyles()
            }

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
        val captions = tto.captions ?: return null
        val file = ArrayList<String>(
            20 + 2 * captions.size)

        file.add(index++, "Scenarist_SCC V1.0\n")

        var line = ""
        var oldC: Subtitle
        var newC = Subtitle()
        newC.content = ""
        newC.end = Time("h:mm:ss.cs", "0:00:00.00")

        val itrC = captions.values.iterator()
        while (itrC.hasNext()) {
            line = ""
            oldC = newC
            newC = itrC.next()
            val newStart = requireNotNull(newC.start)
            val oldEnd = requireNotNull(oldC.end)
            if (oldEnd.mseconds > newStart.mseconds) {
                newC.content = newC.content + ("<br />" + oldC.content)
                newStart.mseconds = (newStart.mseconds - 1000 / 29.97).toInt()
                line += newStart.getTime("hh:mm:ss:ff/29.97") +
                    "\t942c 942c "
                newStart.mseconds = (newStart.mseconds + 1000 / 29.97).toInt()
                line += "94ae 94ae 9420 9420 "

            } else if (oldEnd.mseconds < newStart.mseconds) {
                line += oldEnd.getTime("hh:mm:ss:ff/29.97") +
                    "\t942c 942c\n\n"
                newStart.mseconds = (newStart.mseconds - 1000 / 29.97).toInt()
                line += newStart.getTime("hh:mm:ss:ff/29.97") +
                    "\t94ae 94ae 9420 9420 "
                newStart.mseconds = (newStart.mseconds + 1000 / 29.97).toInt()
            } else {
                newStart.mseconds = (newStart.mseconds - 1000 / 29.97).toInt()
                line += newStart.getTime("hh:mm:ss:ff/29.97") +
                    "\t942c 942c 94ae 94ae 9420 9420 "
                newStart.mseconds = (newStart.mseconds + 1000 / 29.97).toInt()
            }

            line += codeText(newC)
            line += "8080 8080 942f 942f\n"

            file.add(index++, line)

        }

        file.add(index++, "")

        val toReturn = Array(file.size) { "" }
        for (i in toReturn.indices) {
            toReturn[i] = file[i]
        }
        return toReturn
    }

    private fun codeText(newC: Subtitle): String {
        var toReturn = ""

        val lines = RegexUtils.getPattern("<br />").split(newC.content.orEmpty())

        var i = 0
        var tab = 0
        if (lines[i].length > 32)
            lines[i] = lines[i].substring(0, 32)
        tab = (32 - lines[i].length) / 2

        toReturn += "1340 1340 "
        if (tab % 4 != 0)
            ;

        toReturn += codeChar(lines[i].toCharArray())

        if (lines.size > 1) {
            i++

            if (lines[i].length > 32)
                lines[i] = lines[i].substring(0, 32)
            tab = (32 - lines[i].length) / 2

            toReturn += "13e0 13e0 "
            if (tab % 4 != 0)
                ;

            toReturn += codeChar(lines[i].toCharArray())

            if (lines.size > 2) {
                i++

                if (lines[i].length > 32)
                    lines[i] = lines[i].substring(0, 32)
                tab = (32 - lines[i].length) / 2

                toReturn += "9440 9440 "
                if (tab % 4 != 0)
                    ;

                toReturn += codeChar(lines[i].toCharArray())

                if (lines.size > 3) {
                    i++

                    if (lines[i].length > 32)
                        lines[i] = lines[i].substring(0, 32)
                    tab = (32 - lines[i].length) / 2

                    toReturn += "94e0 94e0 "
                    if (tab % 4 != 0)
                        ;

                    toReturn += codeChar(lines[i].toCharArray())

                }
            }
        }

        return toReturn
    }

    private fun codeChar(chars: CharArray): String {
        val toReturn = StringBuilder()

        var i = 0
        while (i < chars.size) {
            when (chars[i]) {
                ' ' ->
                    toReturn.append("20")
                '!' ->
                    toReturn.append("a1")
                '"' ->
                    toReturn.append("a2")
                '#' ->
                    toReturn.append("23")
                '$' ->
                    toReturn.append("a4")
                '%' ->
                    toReturn.append("25")
                '&' ->
                    toReturn.append("26")
                '\'' ->
                    toReturn.append("a7")
                '(' ->
                    toReturn.append("a8")
                ')' ->
                    toReturn.append("29")
                '�' ->
                    toReturn.append("2a")
                '+' ->
                    toReturn.append("ab")
                ',' ->
                    toReturn.append("2c")
                '-' ->
                    toReturn.append("ad")
                '.' ->
                    toReturn.append("ae")
                '/' ->
                    toReturn.append("2f")
                '0' ->
                    toReturn.append("b0")
                '1' ->
                    toReturn.append("31")
                '2' ->
                    toReturn.append("32")
                '3' ->
                    toReturn.append("b3")
                '4' ->
                    toReturn.append("34")
                '5' ->
                    toReturn.append("b5")
                '6' ->
                    toReturn.append("b6")
                '7' ->
                    toReturn.append("37")
                '8' ->
                    toReturn.append("38")
                '9' ->
                    toReturn.append("b9")
                ':' ->
                    toReturn.append("ba")
                ';' ->
                    toReturn.append("3b")
                '<' ->
                    toReturn.append("bc")
                '=' ->
                    toReturn.append("3d")
                '>' ->
                    toReturn.append("3e")
                '?' ->
                    toReturn.append("bf")
                '@' ->
                    toReturn.append("40")
                'A' ->
                    toReturn.append("c1")
                'B' ->
                    toReturn.append("c2")
                'C' ->
                    toReturn.append("43")
                'D' ->
                    toReturn.append("c4")
                'E' ->
                    toReturn.append("45")
                'F' ->
                    toReturn.append("46")
                'G' ->
                    toReturn.append("c7")
                'H' ->
                    toReturn.append("c8")
                'I' ->
                    toReturn.append("49")
                'J' ->
                    toReturn.append("4a")
                'K' ->
                    toReturn.append("cb")
                'L' ->
                    toReturn.append("4c")
                'M' ->
                    toReturn.append("cd")
                'N' ->
                    toReturn.append("ce")
                'O' ->
                    toReturn.append("4f")
                'P' ->
                    toReturn.append("d0")
                'Q' ->
                    toReturn.append("51")
                'R' ->
                    toReturn.append("52")
                'S' ->
                    toReturn.append("d3")
                'T' ->
                    toReturn.append("54")
                'U' ->
                    toReturn.append("d5")
                'V' ->
                    toReturn.append("d6")
                'W' ->
                    toReturn.append("57")
                'X' ->
                    toReturn.append("58")
                'Y' ->
                    toReturn.append("d9")
                'Z' ->
                    toReturn.append("da")
                '[' ->
                    toReturn.append("5b")
                'a' ->
                    toReturn.append("61")
                'b' ->
                    toReturn.append("62")
                'c' ->
                    toReturn.append("e3")
                'd' ->
                    toReturn.append("64")
                'e' ->
                    toReturn.append("e5")
                'f' ->
                    toReturn.append("e6")
                'g' ->
                    toReturn.append("67")
                'h' ->
                    toReturn.append("68")
                'i' ->
                    toReturn.append("e9")
                'j' ->
                    toReturn.append("ea")
                'k' ->
                    toReturn.append("6b")
                'l' ->
                    toReturn.append("ec")
                'm' ->
                    toReturn.append("6d")
                'n' ->
                    toReturn.append("6e")
                'o' ->
                    toReturn.append("ef")
                'p' ->
                    toReturn.append("70")
                'q' ->
                    toReturn.append("f1")
                'r' ->
                    toReturn.append("f2")
                's' ->
                    toReturn.append("73")
                't' ->
                    toReturn.append("f4")
                'u' ->
                    toReturn.append("75")
                'v' ->
                    toReturn.append("76")
                'w' ->
                    toReturn.append("f7")
                'x' ->
                    toReturn.append("f8")
                'y' ->
                    toReturn.append("79")
                'z' ->
                    toReturn.append("7a")
                '|' ->
                    toReturn.append("7f")

                else ->
                    toReturn.append("7f")
            }
            if (i % 2 == 1)
                toReturn.append(" ")
            i++
        }
        if (i % 2 == 1)
            toReturn.append("80 ")

        return toReturn.toString()
    }

    private fun decodeChar(c: Byte): String {
        return when (c.toInt()) {
            42 ->
                "�"
            92 ->
                "é"
            94 ->
                "í"
            95 ->
                "ó"
            96 ->
                "ú"
            123 ->
                "ç"
            124 ->
                "�"
            125 ->
                "Ñ"
            126 ->
                "ñ"
            127 ->
                "|"
            0 ->
                ""
            else ->
                "" + c.toInt().toChar()
        }
    }

    private fun decodeSpecialChar(word: Int): String {
        return when (word) {
            15 ->
                "�"
            14 ->
                "�"
            13 ->
                "�"
            12 ->
                "�"
            11 ->
                "�"
            10 ->
                "�"
            9 ->
                "\u00A0"
            8 ->
                "�"
            7 ->
                "\u266A"
            6 ->
                "�"
            5 ->
                "�"
            4 ->
                "�"
            3 ->
                "�"
            2 ->
                "�"
            1 ->
                "�"
            0 ->
                "�"
            else ->
                ""
        }
    }

    private fun decodeXtChar(textBuffer: String?, word: Int) {
        when (word) {

        }
    }

    private fun createSCCStyles(tto: TimedTextObject) {
        var style: Style
        val styling = requireNotNull(tto.styling)

        style = Style("white")
        styling.put(style.iD, style)

        style = Style("whiteU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("whiteUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("whiteI", style)
        style.underline = false
        styling.put(style.iD, style)

        style = Style("green")
        style.color = Style.rgbValue("name", "green")
        styling.put(style.iD, style)

        style = Style("greenU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("greenUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("greenI", style)
        style.underline = false
        styling.put(style.iD, style)

        style = Style("blue")
        style.color = Style.rgbValue("name", "blue")
        styling.put(style.iD, style)

        style = Style("blueU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("blueUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("blueI", style)
        style.underline = false
        styling.put(style.iD, style)

        style = Style("cyan")
        style.color = Style.rgbValue("name", "cyan")
        styling.put(style.iD, style)

        style = Style("cyanU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("cyanUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("cyanI", style)
        style.underline = false
        styling.put(style.iD, style)

        style = Style("red")
        style.color = Style.rgbValue("name", "red")
        styling.put(style.iD, style)

        style = Style("redU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("redUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("redI", style)
        style.underline = false
        styling.put(style.iD, style)

        style = Style("yellow")
        style.color = Style.rgbValue("name", "yellow")
        styling.put(style.iD, style)

        style = Style("yellowU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("yellowUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("yellowI", style)
        style.underline = false
        styling.put(style.iD, style)

        style = Style("magenta")
        style.color = Style.rgbValue("name", "magenta")
        styling.put(style.iD, style)

        style = Style("magentaU", style)
        style.underline = true
        styling.put(style.iD, style)

        style = Style("magentaUI", style)
        style.italic = true
        styling.put(style.iD, style)

        style = Style("magentaI", style)
        style.underline = false
        styling.put(style.iD, style)

    }

}
