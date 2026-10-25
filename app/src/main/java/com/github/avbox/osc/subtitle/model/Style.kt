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

package com.github.avbox.osc.subtitle.model

class Style {

    constructor(styleName: String?) {
        this.iD = styleName
    }

    constructor(styleName: String?, style: Style) {
        this.iD = styleName
        this.font = style.font
        this.fontSize = style.fontSize
        this.color = style.color
        this.backgroundColor = style.backgroundColor
        this.textAlign = style.textAlign
        this.italic = style.italic
        this.underline = style.underline
        this.bold = style.bold
    }

    @JvmField
    var iD: String? = null

    @JvmField
    var font: String? = null

    @JvmField
    var fontSize: String? = null

    @JvmField
    var color: String? = null

    @JvmField
    var backgroundColor: String? = null

    @JvmField
    var textAlign: String? = ""

    @JvmField
    var italic: Boolean = false

    @JvmField
    var bold: Boolean = false

    @JvmField
    var underline: Boolean = false

    companion object {

        private var styleCounter: Int = 0

        @JvmStatic
        fun rgbValue(format: String, value: String): String? {
            var color: String? = null
            if (format.equals("name", ignoreCase = true)) {
                if (value == "transparent")
                    color = "00000000"
                else if (value == "black")
                    color = "000000ff"
                else if (value == "silver")
                    color = "c0c0c0ff"
                else if (value == "gray")
                    color = "808080ff"
                else if (value == "white")
                    color = "ffffffff"
                else if (value == "maroon")
                    color = "800000ff"
                else if (value == "red")
                    color = "ff0000ff"
                else if (value == "purple")
                    color = "800080ff"
                else if (value == "fuchsia")
                    color = "ff00ffff"
                else if (value == "magenta")
                    color = "ff00ffff "
                else if (value == "green")
                    color = "008000ff"
                else if (value == "lime")
                    color = "00ff00ff"
                else if (value == "olive")
                    color = "808000ff"
                else if (value == "yellow")
                    color = "ffff00ff"
                else if (value == "navy")
                    color = "000080ff"
                else if (value == "blue")
                    color = "0000ffff"
                else if (value == "teal")
                    color = "008080ff"
                else if (value == "aqua")
                    color = "00ffffff"
                else if (value == "cyan")
                    color = "00ffffff "
            } else if (format.equals("&HBBGGRR", ignoreCase = true)) {
                val sb = StringBuilder()
                sb.append(value.substring(6))
                sb.append(value.substring(4, 5))
                sb.append(value.substring(2, 3))
                sb.append("ff")
                color = sb.toString()
            } else if (format.equals("&HAABBGGRR", ignoreCase = true)) {
                val sb = StringBuilder()
                sb.append(value.substring(8))
                sb.append(value.substring(6, 7))
                sb.append(value.substring(4, 5))
                sb.append(value.substring(2, 3))
                color = sb.toString()
            } else if (format.equals("decimalCodedBBGGRR", ignoreCase = true)) {
                var hex: String = Integer.toHexString(value.toInt())
                while (hex.length < 6)
                    hex = "0" + hex
                color = hex.substring(4) + hex.substring(2, 4) +
                        hex.substring(0, 2) + "ff"
            } else if (format.equals("decimalCodedAABBGGRR", ignoreCase = true)) {
                var hex: String = java.lang.Long.toHexString(value.toLong())
                while (hex.length < 8)
                    hex = "0" + hex
                color = hex.substring(6) + hex.substring(4, 6) +
                        hex.substring(2, 4) + hex.substring(0, 2)
            }
            return color
        }

        @JvmStatic
        fun defaultID(): String {
            return "default" + styleCounter++
        }
    }
}
