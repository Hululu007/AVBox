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

import com.github.avbox.osc.subtitle.format.FormatASS
import com.github.avbox.osc.subtitle.format.FormatSCC
import com.github.avbox.osc.subtitle.format.FormatSRT
import com.github.avbox.osc.subtitle.format.FormatSTL
import com.github.avbox.osc.subtitle.format.FormatTTML

import java.util.Hashtable
import java.util.TreeMap

class TimedTextObject {

    @JvmField
    var title: String? = ""

    @JvmField
    var description: String? = ""

    @JvmField
    var copyrigth: String? = ""

    @JvmField
    var author: String? = ""

    @JvmField
    var fileName: String? = ""

    @JvmField
    var language: String? = ""

    @JvmField
    var styling: Hashtable<String, Style>? = null

    @JvmField
    var layout: Hashtable<String, Region>? = null

    @JvmField
    var captions: TreeMap<Int, Subtitle>? = null

    @JvmField
    var warnings: String? = null

    @JvmField
    var useASSInsteadOfSSA: Boolean = true

    @JvmField
    var offset: Int = 0

    @JvmField
    var built: Boolean = false

    constructor() {
        styling = Hashtable<String, Style>()
        layout = Hashtable<String, Region>()
        captions = TreeMap<Int, Subtitle>()

        warnings = "List of non fatal errors produced during parsing:\n\n"
    }

    fun toSRT(): Array<String>? {
        return FormatSRT().toFile(this)
    }

    fun toASS(): Array<String>? {
        return FormatASS().toFile(this)
    }

    fun toSTL(): ByteArray? {
        return FormatSTL().toFile(this)
    }

    fun toSCC(): Array<String>? {
        return FormatSCC().toFile(this)
    }

    fun toTTML(): Array<String>? {
        return FormatTTML().toFile(this)
    }

    fun cleanUnusedStyles() {
        val usedStyles = Hashtable<String, Style>()
        val itrC = captions?.values?.iterator() ?: return
        while (itrC.hasNext()) {
            val current = itrC.next()
            val style = current.style
            if (style != null) {
                val iD = style.iD ?: continue
                if (!usedStyles.containsKey(iD)) usedStyles.put(iD, style)
            }
        }
        this.styling = usedStyles
    }
}
