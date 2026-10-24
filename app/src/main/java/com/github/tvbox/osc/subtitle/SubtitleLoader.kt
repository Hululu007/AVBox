package com.github.tvbox.osc.subtitle

import android.net.Uri
import android.text.TextUtils
import android.util.Base64
import com.github.tvbox.osc.util.LOG

import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.subtitle.exception.FatalParsingException
import com.github.tvbox.osc.subtitle.format.FormatASS
import com.github.tvbox.osc.subtitle.format.FormatSRT
import com.github.tvbox.osc.subtitle.format.FormatSTL
import com.github.tvbox.osc.subtitle.format.FormatTTML
import com.github.tvbox.osc.subtitle.format.TimedTextFileFormat
import com.github.tvbox.osc.subtitle.model.TimedTextObject
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor
import com.github.tvbox.osc.util.RegexUtils
import com.github.tvbox.osc.util.UnicodeReader
import com.github.tvbox.osc.util.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import org.apache.commons.io.input.ReaderInputStream
import org.mozilla.universalchardet.UniversalDetector

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset

object SubtitleLoader {
    private const val TAG = "SubtitleLoader"

    private val ioScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @JvmStatic
    fun loadSubtitle(path: String?, callback: Callback?) {
        if (path.isNullOrEmpty()) {
            return
        }
        val p = path
        if (p.startsWith("http://") ||
            p.startsWith("https://")) {
            loadFromRemoteAsync(p, callback)
        } else if (p.startsWith("data:")) {
            loadFromDataAsync(p, callback)
        } else {
            loadFromLocalAsync(p, callback)
        }
    }

    private fun loadFromDataAsync(dataPath: String, callback: Callback?) {
        AppTaskExecutor.deskIO().execute {
            try {
                val result = loadFromData(dataPath)
                callback?.let { cb ->
                    AppTaskExecutor.mainThread().execute {
                        cb.onSuccess(result)
                    }
                }
            } catch (e: Exception) {
                LOG.e("SubtitleLoader", e)
                callback?.let { cb ->
                    AppTaskExecutor.mainThread().execute {
                        cb.onError(e)
                    }
                }
            }
        }
    }

    private fun loadFromRemoteAsync(remoteSubtitlePath: String,
                                    callback: Callback?) {
        ioScope.launch {
            try {
                val subtitleLoadSuccessResult = loadFromRemote(remoteSubtitlePath)
                callback?.let { cb ->
                    AppTaskExecutor.mainThread().execute {
                        cb.onSuccess(subtitleLoadSuccessResult)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.e("SubtitleLoader", e)
                callback?.let { cb ->
                    AppTaskExecutor.mainThread().execute {
                        cb.onError(e)
                    }
                }
            }
        }
    }

    private fun loadFromLocalAsync(localSubtitlePath: String,
                                   callback: Callback?) {
        AppTaskExecutor.deskIO().execute {
            try {
                val subtitleLoadSuccessResult = loadFromLocal(localSubtitlePath)
                callback?.let { cb ->
                    AppTaskExecutor.mainThread().execute {
                        cb.onSuccess(subtitleLoadSuccessResult)
                    }
                }
            } catch (e: Exception) {
                LOG.e("SubtitleLoader", e)
                callback?.let { cb ->
                    AppTaskExecutor.mainThread().execute {
                        cb.onError(e)
                    }
                }
            }
        }
    }

    @Throws(IOException::class, FatalParsingException::class)
    private fun loadFromData(dataPath: String): SubtitleLoadSuccessResult {
        val comma = dataPath.indexOf(',')
        if (comma < 0) throw IOException("Invalid subtitle data URI")
        val fragment = dataPath.indexOf('#', comma + 1)
        val metadata = dataPath.substring(5, comma).lowercase()
        val encoded = dataPath.substring(comma + 1, if (fragment < 0) dataPath.length else fragment)
        val bytes: ByteArray
        if (metadata.contains(";base64")) {
            bytes = Base64.decode(encoded, Base64.DEFAULT)
        } else {
            bytes = Uri.decode(encoded).toByteArray(Charsets.UTF_8)
        }
        var fileName = if (fragment < 0) "subtitle.ass" else Uri.decode(dataPath.substring(fragment + 1))
        if (!FileUtils.hasExtension(fileName)) {
            fileName += if (metadata.contains("vtt")) ".vtt" else ".ass"
        }
        val content = String(bytes, Charsets.UTF_8)
        val result = SubtitleLoadSuccessResult()
        result.timedTextObject = loadAndParse(ByteArrayInputStream(bytes), fileName)
        result.fileName = fileName
        result.content = content
        result.subtitlePath = dataPath
        return result
    }

    @Throws(IOException::class, FatalParsingException::class, Exception::class)
    private suspend fun loadFromRemote(remoteSubtitlePath: String): SubtitleLoadSuccessResult {
        LOG.d(TAG, "parseRemote: remoteSubtitlePath = $remoteSubtitlePath")
        var referer = ""
        if (remoteSubtitlePath.contains("alicloud") || remoteSubtitlePath.contains("aliyundrive")) {
            referer = "https://www.aliyundrive.com/"
        } else if (remoteSubtitlePath.contains("assrt.net")) {
            referer = "https://secure.assrt.net/"
        }
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
        val raw = Http.getRaw(RegexUtils.getPattern("#").split(remoteSubtitlePath)[0]) {
            headers("Referer", referer)
            headers("User-Agent", ua)
        }
        val bytes = raw.body
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        var encoding: String? = detector.detectedCharset
        if (TextUtils.isEmpty(encoding)) encoding = "UTF-8"
        val content = String(bytes, Charset.forName(encoding))
        val newInputStream = ByteArrayInputStream(content.toByteArray(Charset.defaultCharset()))
        var filename = ""
        val contentDispostion = raw.headers["content-disposition"] ?: ""
        val cd = RegexUtils.getPattern(";").split(contentDispostion)
        if (cd.size > 1) {
            var filenameInfo = cd[1]
            filenameInfo = filenameInfo.trim { it <= ' ' }
            if (filenameInfo.startsWith("filename=")) {
                filename = filenameInfo.replace("filename=", "")
                filename = filename.replace("\"", "")
            } else if (filenameInfo.startsWith("filename*=")) {
                filename = filenameInfo.substring(filenameInfo.lastIndexOf("''") + 2)
            }
            filename = filename.trim { it <= ' ' }
            filename = URLDecoder.decode(filename)
        }
        val resolvedPath: String = if (filename.length >= 1) filename else (Uri.parse(remoteSubtitlePath).path ?: "")
        var filePath = resolvedPath
        if (!filePath.contains(".") && remoteSubtitlePath.contains("#")) {
            filePath = RegexUtils.getPattern("#").split(remoteSubtitlePath)[1]
            filePath = URLDecoder.decode(filePath)
        }
        val subtitleLoadSuccessResult = SubtitleLoadSuccessResult()
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(newInputStream, filePath)
        subtitleLoadSuccessResult.fileName = filePath
        subtitleLoadSuccessResult.content = content
        subtitleLoadSuccessResult.subtitlePath = remoteSubtitlePath
        return subtitleLoadSuccessResult
    }

    @Throws(IOException::class, FatalParsingException::class)
    private fun loadFromLocal(localSubtitlePath: String): SubtitleLoadSuccessResult? {
        LOG.d(TAG, "parseLocal: localSubtitlePath = $localSubtitlePath")
        val file = File(localSubtitlePath)
        if (!file.exists()) {
            LOG.d(TAG, "parseLocal: localSubtitlePath = $localSubtitlePath file not exsits")
            return null
        }
        val bytes = FileUtils.readSimple(file)
        if (bytes == null || bytes.isEmpty()) {
            return null
        }
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        var encoding: String? = detector.detectedCharset
        if (TextUtils.isEmpty(encoding)) encoding = "UTF-8"
        val content = String(bytes, Charset.forName(encoding))
        val newInputStream = ByteArrayInputStream(content.toByteArray(Charset.defaultCharset()))
        val filePath = file.path
        val subtitleLoadSuccessResult = SubtitleLoadSuccessResult()
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(newInputStream, filePath)
        val fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
        subtitleLoadSuccessResult.fileName = fileName
        subtitleLoadSuccessResult.subtitlePath = localSubtitlePath
        return subtitleLoadSuccessResult
    }

    @Throws(IOException::class, FatalParsingException::class)
    private fun loadAndParse(`is`: InputStream, filePath: String): TimedTextObject? {
        val fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
        var ext = ""
        if (fileName.lastIndexOf(".") > 0) {
            ext = fileName.substring(fileName.lastIndexOf("."))
        }
        LOG.d(TAG, "parse: name = $fileName, ext = $ext")
        val reader = UnicodeReader(`is`)
        val newInputStream = ReaderInputStream(reader, Charset.defaultCharset())
        if (".srt".equals(ext, ignoreCase = true)) {
            return FormatSRT().parseFile(fileName, newInputStream)
        } else if (".ass".equals(ext, ignoreCase = true)) {
            return FormatASS().parseFile(fileName, newInputStream)
        } else if (".stl".equals(ext, ignoreCase = true)) {
            return FormatSTL().parseFile(fileName, newInputStream)
        } else if (".ttml".equals(ext, ignoreCase = true)) {
            return FormatTTML().parseFile(fileName, newInputStream)
        }
        val arr = arrayOf<TimedTextFileFormat>(FormatSRT(), FormatASS(), FormatSTL(), FormatTTML())
        for (oneFormat in arr) {
            try {
                val obj = oneFormat.parseFile(fileName, openBomAwareStream(`is`, filePath))
                return obj
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun openBomAwareStream(`is`: InputStream, filePath: String): InputStream {
        try {
            `is`.reset()
        } catch (e: IOException) {
            throw e
        }
        val reader = UnicodeReader(`is`)
        return ReaderInputStream(reader, Charset.defaultCharset())
    }

    interface Callback {
        fun onSuccess(result: SubtitleLoadSuccessResult?)

        fun onError(exception: Exception?)
    }
}
