package com.github.avbox.osc.io

import android.os.Environment
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.net.OkHttp
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LocalAddress
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.StringUtils
import com.github.avbox.osc.util.UserAgentPool
import com.google.gson.Gson
import com.google.gson.JsonObject

import org.json.JSONObject

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

object FileUtils {

    @JvmStatic
    fun writeSimple(data: ByteArray, dst: File): Boolean {
        try {
            if (dst.exists())
                dst.delete()
            val bos = BufferedOutputStream(FileOutputStream(dst))
            bos.write(data)
            bos.close()
            return true
        } catch (e: IOException) {
            AppLog.e("FileUtils", e)
        }
        return false
    }

    @JvmStatic
    fun readSimple(src: File): ByteArray? {
        try {
            val bis = BufferedInputStream(FileInputStream(src))
            val len = bis.available()
            val data = ByteArray(len)
            bis.read(data)
            bis.close()
            return data
        } catch (e: IOException) {
            AppLog.e("FileUtils", e)
        }
        return null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(source: File, dest: File) {
        var ins: InputStream? = null
        var os: OutputStream? = null
        try {
            ins = FileInputStream(source)
            os = FileOutputStream(dest)
            val buffer = ByteArray(1024)
            var length: Int
            while (ins.read(buffer).also { length = it } > 0) {
                os.write(buffer, 0, length)
            }
        } finally {
            ins?.close()
            os?.close()
        }
    }

    @JvmStatic
    fun recursiveDelete(file: File) {
        if (!file.exists())
            return
        if (file.isDirectory) {
            for (f in file.listFiles().orEmpty()) {
                recursiveDelete(f)
            }
        }
        file.delete()
    }

    @JvmStatic
    fun readFileToString(path: String, charsetName: String): String {
        var jsonString = ""

        var ins: BufferedReader? = null
        try {
            ins = BufferedReader(InputStreamReader(FileInputStream(File(path)), charsetName))
            var thisLine: String? = null
            while (ins.readLine().also { thisLine = it } != null) {
                jsonString += thisLine
            }
            ins.close()
        } catch (e: IOException) {
            AppLog.e("FileUtils", e)
        } finally {
            if (ins != null) {
                try {
                    ins.close()
                } catch (el: IOException) {
                    AppLog.d("FileUtils", "close reader failed")
                }
            }
        }
        return jsonString
    }

    @JvmStatic
    fun rootPath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    @JvmStatic
    fun getLocal(path: String): File {
        return File(path.replace("file:/", rootPath()))
    }

    @JvmStatic
    fun getCacheDir(): File {
        return requireNotNull(AppContextHolder.context()) { "app context not initialized" }.cacheDir
    }

    @JvmStatic
    fun cachePath(): String {
        return getCacheDir().absolutePath
    }

    @JvmStatic
    fun getFilePath(): String {
        return requireNotNull(AppContextHolder.context()) { "app context not initialized" }.filesDir.absolutePath
    }

    @JvmStatic
    fun cleanDirectory(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles()
        if (files == null || files.isEmpty()) return
        for (one in files) {
            try {
                deleteFile(one)
            } catch (e: Exception) {
                AppLog.e("FileUtils", e)
            }
        }
    }

    @JvmStatic
    fun isWeekAgo(file: File): Boolean {
        val oneWeekMillis = 3L * 24 * 60 * 60 * 1000
        val timeDiff = System.currentTimeMillis() - file.lastModified()
        return timeDiff > oneWeekMillis
    }

    @JvmStatic
    fun deleteFile(file: File) {
        if (!file.exists()) return
        if (file.isFile) {
            deleteSingle(file)
            return
        }
        if (file.isDirectory) {
            val files = file.listFiles()
            if (files == null || files.isEmpty()) {
                deleteSingle(file)
                return
            }
            for (one in files) {
                deleteFile(one)
            }
        }
        return
    }

    private fun deleteFileTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            val files = file.listFiles()
            if (files != null) {
                for (one in files) {
                    deleteFileTree(one)
                }
            }
        }
        deleteSingle(file)
    }

    private fun deleteSingle(file: File) {
        if (!file.canWrite()) file.setWritable(true)
        if (!file.delete()) {
            AppLog.i("clearCache: cannot delete " + file.absolutePath)
        }
    }

    @JvmStatic
    fun repairBogusNativeLibs(): Int {
        try {
            return repairBogusNativeLibs(File(getFilePath()))
        } catch (e: Throwable) {
            AppLog.i("native-lib-repair failed: " + e.message)
            return 0
        }
    }

    @JvmStatic
    fun repairBogusNativeLibs(root: File): Int {
        try {
            return repairBogusNativeLibs(root, 0)
        } catch (e: Throwable) {
            AppLog.i("native-lib-repair failed: " + e.message)
            return 0
        }
    }

    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    private const val REPAIR_MAX_DEPTH = 3

    private fun repairBogusNativeLibs(dir: File?, depth: Int): Int {
        if (dir == null || depth > REPAIR_MAX_DEPTH) return 0
        val files = dir.listFiles() ?: return 0
        var repaired = 0
        for (file in files) {
            if (file.isDirectory) {
                repaired += repairBogusNativeLibs(file, depth + 1)
                continue
            }
            if (!looksLikeNativeLib(file.name)) continue
            if (hasElfMagic(file)) continue
            val size = file.length()
            deleteSingle(file)
            if (!file.exists()) {
                repaired++
                AppLog.i("native-lib-repair removed bogus " + file.absolutePath + " size=" + size)
            }
        }
        return repaired
    }

    private fun looksLikeNativeLib(name: String?): Boolean {
        if (name == null || name.isEmpty()) return false
        val lower = name.lowercase()
        return lower.endsWith(".so") || lower.startsWith(".lib")
    }

    private fun hasElfMagic(file: File): Boolean {
        try {
            FileInputStream(file).use { ins ->
                val magic = ByteArray(ELF_MAGIC.size)
                if (ins.read(magic) != ELF_MAGIC.size) return false
                for (i in ELF_MAGIC.indices) {
                    if (magic[i] != ELF_MAGIC[i]) return false
                }
                return true
            }
        } catch (e: Throwable) {
            return false
        }
    }

    @JvmStatic
    fun cleanPlayerCache() {
        val thunderCachePath = cachePath() + "/thunder/"
        val thunderCacheDir = File(thunderCachePath)
        try {
            if (thunderCacheDir.exists()) cleanDirectory(thunderCacheDir)
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
    }

    private const val EXTERNAL_CACHE_KEEP_DIR = "config"

    @JvmStatic
    fun cacheSize(): Long {
        var size = directorySize(getCacheDir(), null)
        val externalCacheDir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.externalCacheDir
        if (externalCacheDir != null && externalCacheDir.absolutePath != cachePath()) {
            size += directorySize(externalCacheDir, EXTERNAL_CACHE_KEEP_DIR)
        }
        return size
    }

    private fun directorySize(dir: File?, skipChildName: String?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()
        val files = dir.listFiles() ?: return 0
        var size = 0L
        for (one in files) {
            if (skipChildName != null && skipChildName == one.name) continue
            size += if (one.isDirectory) directorySize(one, null) else one.length()
        }
        return size
    }

    @JvmStatic
    fun formatCacheSize(bytes: Long): String {
        if (bytes <= 0) return "0KB"
        if (bytes < 1024L * 1024L) return Math.max(1L, bytes / 1024).toString() + "KB"
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024)
        return String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024)
    }

    private const val EXO_CACHE_DIR_NAME = "exo-video-cache"

    private const val PENDING_EXO_CLEAR_FLAG = "clear_exo_cache.pending"

    @JvmStatic
    fun clearCache() {
        val cacheDir = getCacheDir()
        val innerFiles = cacheDir.listFiles()
        if (innerFiles != null) {
            for (one in innerFiles) {
                try {
                    deleteFileTree(one)
                } catch (e: Exception) {
                    AppLog.e("FileUtils", e)
                }
            }
        }
        val externalCacheDir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.externalCacheDir
        if (externalCacheDir == null) return
        val files = externalCacheDir.listFiles() ?: return
        for (one in files) {
            if (EXTERNAL_CACHE_KEEP_DIR == one.name) continue
            try {
                deleteFileTree(one)
            } catch (e: Exception) {
                AppLog.e("FileUtils", e)
            }
        }
    }

    @JvmStatic
    fun purgeExoCacheIfPending() {
        val flag = pendingExoClearFlag()
        if (!flag.exists()) return
        val exoDir = File(externalCachePath(), EXO_CACHE_DIR_NAME)
        AppLog.i("echo-exo-cache-purge-start: " + exoDir.absolutePath)
        try {
            deleteFileTree(exoDir)
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
        val remaining = if (exoDir.exists()) exoDir.listFiles() else null
        if (remaining == null || remaining.isEmpty()) {
            if (exoDir.exists()) exoDir.delete()
            flag.delete()
            AppLog.i("echo-exo-cache-purge-done")
        } else {
            for (r in remaining) {
                AppLog.i("echo-exo-cache-remain: type=" + (if (r.isDirectory) "DIR" else "FILE")
                        + " writable=" + r.canWrite()
                        + " size=" + r.length()
                        + " path=" + r.absolutePath)
            }
            AppLog.i("echo-exo-cache-purge-incomplete: " + remaining.size)
        }
    }

    private fun pendingExoClearFlag(): File {
        return File(getFilePath(), PENDING_EXO_CLEAR_FLAG)
    }

    @JvmStatic
    fun clearSpiderCacheFiles() {
        cleanDirectory(File(getFilePath() + "/csp/"))
        cleanDirectory(File(cachePath() + "/jar/"))
        cleanDirectory(File(cachePath() + "/py/"))
        cleanDirectory(File(cachePath() + "/catvod_jsapi/"))
        clearJsModuleCache()
    }

    private fun clearJsModuleCache() {
        val externalCacheDir = File(externalCachePath())
        val files = externalCacheDir.listFiles() ?: return
        for (file in files) {
            if (file != null && file.name.startsWith("qjscache_")) {
                deleteFile(file)
            }
        }
    }

    @JvmStatic
    fun read(path: String): String {
        try {
            val br = BufferedReader(InputStreamReader(FileInputStream(getLocal(path))))
            val sb = StringBuilder()
            var text: String? = null
            while (br.readLine().also { text = it } != null) sb.append(text).append("\n")
            br.close()
            return sb.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun getFileName(filePath: String): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath
        val p = fileName.lastIndexOf(File.separatorChar)
        if (p != -1) {
            fileName = fileName.substring(p + 1)
        }
        return fileName
    }

    @JvmStatic
    fun fileNameWithoutExt(filePath: String): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath
        var p = fileName.lastIndexOf(File.separatorChar)
        if (p != -1) {
            fileName = fileName.substring(p + 1)
        }
        p = fileName.indexOf('.')
        if (p != -1) {
            fileName = fileName.substring(0, p)
        }
        return fileName
    }

    @JvmStatic
    fun hasExtension(path: String): Boolean {
        val lastDotIndex = path.lastIndexOf(".")
        val lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"))
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length - 1
    }

    @JvmStatic
    fun saveCache(cache: File, json: String) {
        try {
            val cacheDir = cache.parentFile
            if (!cacheDir.exists())
                cacheDir.mkdirs()
            if (cache.exists())
                cache.delete()
            val fos = FileOutputStream(cache)
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.close()
        } catch (th: Throwable) {
            AppLog.e("FileUtils", th)
        }
    }

    private val URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)

    @JvmStatic
    fun loadModule(name: String): String? {
        var rel: String? = null
        try {
            var name = name
            if (name.contains("gbk.js")) {
                name = "gbk.js"
            } else if (name.contains("模板.js")) { // i18n: keep(R4:模板.js 文件名约定)
                name = "模板.js" // i18n: keep(R4:模板.js 文件名约定)
            } else if (name.contains("cat.js")) {
                name = "cat.js"
            }
            AppLog.i("echo-loadModule " + name)
            val m = URL_JOIN.matcher(name)
            if (m.find()) {
                val key = requireNotNull(MD5.encode(name)) { "md5 of module name failed" }
                val cache = getCache(key)
                rel = cache
                if (StringUtils.isEmpty(cache)) {
                    val netStr = get(name)
                    if (!TextUtils.isEmpty(netStr)) {
                        setCache(604800, key, netStr)
                    }
                    rel = netStr
                }
            } else if (name.startsWith("assets://")) {
                rel = asOpen(name.substring(9))
            } else if (isAsFile(name, "js/lib")) {
                rel = asOpen("js/lib/" + name)
            } else if (name.startsWith("file://")) {
                rel = get(LocalAddress.get() + "file/" + name.replace("file:///", "")
                        .replace("file://", ""))
            } else if (name.startsWith("clan://localhost/")) {
                rel = get(LocalAddress.get() + "file/" + name.replace("clan://localhost/", ""))
            } else if (name.startsWith("clan://")) {
                val substring = name.substring(7)
                val indexOf = substring.indexOf('/')
                rel = get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1))
            }
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
        return rel
    }

    private val cachedDirFiles: MutableMap<String, Set<String>> = ConcurrentHashMap()

    @JvmStatic
    fun isAsFile(name: String, dir: String): Boolean {
        var files: Set<String>? = cachedDirFiles[dir]
        if (files == null) {
            AppLog.i("echo-读取AssetsList")
            try {
                val list = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.assets.list(dir)
                files = HashSet(Arrays.asList(*list.orEmpty()))
            } catch (e: IOException) {
                files = Collections.emptySet()
            }
            cachedDirFiles[dir] = files
        }
        return files.contains(name.trim { it <= ' ' })
    }

    @JvmStatic
    fun asOpen(name: String): String {
        try {
            val ins = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.assets.open(name)
            val data = ByteArray(ins.available())
            ins.read(data)
            return String(data, Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
        return ""
    }

    @JvmStatic
    fun getCache(name: String): String {
        try {
            var code = ""
            val file = open(name)
            if (file.exists()) {
                code = readSimple(file)?.let { String(it, Charset.defaultCharset()) } ?: ""
            }
            if (TextUtils.isEmpty(code)) {
                return ""
            }
            val asJsonObject = (Gson().fromJson(code, JsonObject::class.java)).asJsonObject
            if (asJsonObject.get("expires").asInt.toLong() <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name))
            }
            return asJsonObject.get("data").asString
        } catch (e4: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun setCache(time: Int, name: String, data: String) {
        try {
            val jSONObject = JSONObject()
            jSONObject.put("expires", (time + (System.currentTimeMillis() / 1000)).toInt())
            jSONObject.put("data", data)
            writeSimple(jSONObject.toString().toByteArray(Charset.defaultCharset()), open(name))
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
    }

    @JvmStatic
    fun setCacheByte(name: String, data: ByteArray) {
        try {
            writeSimple(byteMerger("//DRPY".toByteArray(Charset.defaultCharset()), Base64.encode(data, Base64.URL_SAFE)), open("B_" + name))
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
    }

    @JvmStatic
    fun byteMerger(bt1: ByteArray, bt2: ByteArray): ByteArray {
        val bt3 = ByteArray(bt1.size + bt2.size)
        System.arraycopy(bt1, 0, bt3, 0, bt1.size)
        System.arraycopy(bt2, 0, bt3, bt1.size, bt2.size)
        return bt3
    }

    @JvmStatic
    fun get(str: String): String {
        return get(str, null)
    }

    @JvmStatic
    fun get(str: String, headerMap: Map<String, String>?): String {
        val headers = headerMap ?: HashMap<String, String>().also {
            it["User-Agent"] = if (str.startsWith("https://gitcode.net/")) UserAgentPool.random() else "okhttp/3.15"
        }
        return OkHttp.string(str, headers)
    }

    @JvmStatic
    fun open(str: String): File {
        return File(externalCachePath() + "/qjscache_" + str + ".js")
    }

    @JvmStatic
    fun externalCachePath(): String {
        val externalCacheDir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.externalCacheDir
        if (externalCacheDir == null) {
            return cachePath()
        }
        return externalCacheDir.absolutePath
    }

    @JvmStatic
    fun externalFilesPath(): String {
        val externalFilesDir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.getExternalFilesDir(null)
        if (externalFilesDir == null) {
            return getFilePath()
        }
        return externalFilesDir.absolutePath
    }

    @JvmStatic
    fun ensureNoMediaMarker() {
        try {
            val dir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.getExternalFilesDir(null)
                ?: return
            if (createNoMediaMarkerIfAbsent(dir)) {
                AppLog.i("echo-storage nomedia path=" + dir.absolutePath)
            }
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
        }
    }

    @JvmStatic
    fun createNoMediaMarkerIfAbsent(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                AppLog.e("echo-storage nomedia mkdir-failed path=" + dir.absolutePath)
                return false
            }
            val marker = File(dir, NO_MEDIA_MARKER)
            if (marker.exists()) return false
            if (!marker.createNewFile() && !marker.exists()) {
                AppLog.e("echo-storage nomedia create-failed path=" + marker.absolutePath)
                return false
            }
            true
        } catch (e: Exception) {
            AppLog.e("FileUtils", e)
            false
        }
    }

    private const val NO_MEDIA_MARKER = ".nomedia"
}
