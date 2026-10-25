package com.github.avbox.osc.util

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.Reader

class UnicodeReader : Reader {
    private var internalIn: InputStreamReader? = null
    private var encoding: String? = null

    companion object {
        private const val BOM_SIZE = 4
    }

    @Throws(IOException::class, FileNotFoundException::class, SecurityException::class)
    constructor(file: String) : this(File(file))

    @Throws(IOException::class, FileNotFoundException::class, SecurityException::class)
    constructor(file: File) : this(FileInputStream(file))

    @Throws(IOException::class, FileNotFoundException::class, SecurityException::class)
    constructor(file: File, defaultEncoding: String?) : this(FileInputStream(file), defaultEncoding)

    @Throws(IOException::class)
    constructor(input: InputStream) : this(input, null)

    @Throws(IOException::class)
    constructor(input: InputStream, defaultEncoding: String?) : super() {
        init(input, defaultEncoding)
    }

    @Throws(IOException::class)
    override fun close() {
        internalIn?.close()
    }

    @Throws(IOException::class)
    protected fun init(input: InputStream, defaultEncoding: String?) {
        val tempIn = PushbackInputStream(input, 4)

        val bom = ByteArray(4)

        val n = tempIn.read(bom, 0, bom.size)
        var unread: Int
        if ((bom[0].toInt() == 0) && (bom[1].toInt() == 0) &&
                (bom[2].toInt() == -2) && (bom[3].toInt() == -1)) {
            encoding = "UTF-32BE"
            unread = n - 4
        } else {
            if (n == 4) {
                if ((bom[0].toInt() == -1) && (bom[1].toInt() == -2) &&
                        (bom[2].toInt() == 0) && (bom[3].toInt() == 0)) {
                    encoding = "UTF-32LE"
                    unread = n - 4
                }
            }
            if ((bom[0].toInt() == -17) && (bom[1].toInt() == -69) &&
                    (bom[2].toInt() == -65)) {
                encoding = "UTF-8"
                unread = n - 3
            } else {
                if ((bom[0].toInt() == -2) && (bom[1].toInt() == -1)) {
                    encoding = "UTF-16BE"
                    unread = n - 2
                } else {
                    if ((bom[0].toInt() == -1) && (bom[1].toInt() == -2)) {
                        encoding = "UTF-16LE"
                        unread = n - 2
                    } else {
                        encoding = defaultEncoding
                        unread = n
                    }
                }
            }
        }
        if (unread > 0)
            tempIn.unread(bom, n - unread, unread)
        else if (unread < -1) {
            tempIn.unread(bom, 0, 0)
        }

        val charset = encoding
        if (charset == null) {
            val reader = InputStreamReader(tempIn)
            internalIn = reader
            encoding = reader.getEncoding()
        } else {
            internalIn = InputStreamReader(tempIn, charset)
        }
    }

    @Throws(IOException::class)
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        return requireNotNull(internalIn) { "unicode reader not initialized" }.read(cbuf, off, len)
    }
}
