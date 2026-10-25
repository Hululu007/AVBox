package com.github.avbox.osc.io

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.github.avbox.osc.R
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.PermissionHelper
import com.github.avbox.osc.util.PySourcePack
import com.github.avbox.osc.util.SpiderStorageNotice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object LocalConfigHost {
    var pending: ((name: String, api: String) -> Unit)? = null

    var pendingName: String? = null
    var pendingApi: String? = null

    var pendingDir: File? = null
    var pendingRefs: List<String> = emptyList()

    var pendingSourceDir: String? = null
}

private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

private fun onMain(work: () -> Unit) {
    mainHandler.post(work)
}

private val importWorker: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "local-config-import") }
}

fun startLocalConfig(
    launcher: ActivityResultLauncher<Array<String>>,
    onResult: (name: String, api: String) -> Unit,
) {
    SpiderStorageNotice.suppress()
    LocalConfigHost.pending = onResult
    launcher.launch(arrayOf("*/*"))
}

fun cancelLocalConfig() {
    LocalConfigHost.pending = null
    LocalConfigHost.pendingName = null
    LocalConfigHost.pendingApi = null
    LocalConfigHost.pendingDir = null
    LocalConfigHost.pendingRefs = emptyList()
    LocalConfigHost.pendingSourceDir = null
}

fun handleLocalConfigResult(activity: Activity, uri: Uri, onFinish: (Boolean) -> Unit) {
    val callback = LocalConfigHost.pending ?: return
    LocalConfigHost.pending = null
    val context = activity.applicationContext ?: activity
    importWorker.execute {
        val result = try {
            importLocalConfig(context, uri)
        } catch (th: Throwable) {
            AppLog.e("LocalConfigHelper", th)
            null
        }
        onMain {
            if (activity.isFinishing || activity.isDestroyed) {
                AppLog.i("echo-local-src drop, page gone uri=" + uri)
                return@onMain
            }
            if (result == null) {
                Toast.makeText(activity, activity.getString(R.string.toast_local_config_read_failed), Toast.LENGTH_SHORT).show()
                onFinish(false)
                return@onMain
            }
            AppLog.i(
                "echo-local-src import api=" + result.api + " missing=" + result.missingRefs.size +
                    " direct=" + result.direct + " uri=" + uri,
            )
            if (result.direct && !PermissionHelper.isStorageGranted(activity)) {
                PermissionHelper.requestStorage(activity) { _, _ -> }
            }
            if (result.missingRefs.isEmpty()) {
                callback(result.name, result.api)
                onFinish(false)
                return@onMain
            }
            LocalConfigHost.pending = callback
            LocalConfigHost.pendingName = result.name
            LocalConfigHost.pendingApi = result.api
            LocalConfigHost.pendingDir = result.dir
            LocalConfigHost.pendingRefs = result.missingRefs
            LocalConfigHost.pendingSourceDir = result.sourceDir
            val tip = if (PermissionHelper.isStorageGranted(activity)) {
                activity.getString(R.string.toast_local_missing_files_hint, result.missingRefs.size)
            } else {
                activity.getString(R.string.toast_local_missing_files_all_files, result.missingRefs.size)
            }
            Toast.makeText(activity, tip, Toast.LENGTH_LONG).show()
            onFinish(true)
        }
    }
}

internal fun isLocalSpiderName(name: String): Boolean =
    name.endsWith(".py", ignoreCase = true) || name.endsWith(".js", ignoreCase = true)

internal fun localSourceName(fileName: String): String =
    fileName.substringBeforeLast('.').ifEmpty { fileName }

fun importLocalSource(
    activity: Activity,
    uri: Uri,
    onImported: (name: String, api: String) -> Unit,
    onFailed: (unsupported: Boolean) -> Unit,
) {
    val context = activity.applicationContext ?: activity
    importWorker.execute {
        val displayName = safeFileName(getDisplayName(context, uri))
        val supported = isLocalSpiderName(displayName)
        val result = if (supported) {
            try {
                importLocalConfig(context, uri)
            } catch (th: Throwable) {
                AppLog.e("LocalConfigHelper", th)
                null
            }
        } else {
            null
        }
        onMain {
            if (activity.isFinishing || activity.isDestroyed) {
                AppLog.i("echo-local-src intent drop, page gone uri=" + uri)
                return@onMain
            }
            if (result == null) {
                AppLog.i("echo-local-src intent failed name=" + displayName + " unsupported=" + !supported)
                onFailed(!supported)
                return@onMain
            }
            AppLog.i("echo-local-src intent import name=" + displayName + " api=" + result.api)
            onImported(result.name, result.api)
        }
    }
}

fun handleLocalSourceTreeResult(activity: Activity, tree: Uri?) {
    val callback = LocalConfigHost.pending
    val name = LocalConfigHost.pendingName
    val api = LocalConfigHost.pendingApi
    val dir = LocalConfigHost.pendingDir
    val refs = LocalConfigHost.pendingRefs
    val sourceDir = LocalConfigHost.pendingSourceDir
    LocalConfigHost.pending = null
    LocalConfigHost.pendingName = null
    LocalConfigHost.pendingApi = null
    LocalConfigHost.pendingDir = null
    LocalConfigHost.pendingRefs = emptyList()
    LocalConfigHost.pendingSourceDir = null
    if (callback == null) return
    if (tree == null || dir == null) {
        val tip = if (isUngrantableDir(sourceDir, Environment.getExternalStorageDirectory().absolutePath)) {
            R.string.toast_local_tree_forbidden
        } else {
            R.string.toast_local_tree_denied
        }
        Toast.makeText(activity, activity.getString(tip), Toast.LENGTH_LONG).show()
        if (!api.isNullOrEmpty()) callback(name.orEmpty(), api)
        return
    }
    val context = activity.applicationContext ?: activity
    importWorker.execute {
        LocalSourceTree.remember(context, tree)
        val missing = try {
            copyRefsFromTree(context, tree, dir, refs)
        } catch (th: Throwable) {
            AppLog.e("LocalConfigHelper", th)
            refs
        }
        onMain {
            if (activity.isFinishing || activity.isDestroyed) return@onMain
            AppLog.i("echo-local-src tree missing=" + missing.size + " of=" + refs.size)
            if (missing.isNotEmpty()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_local_refs_missing, missing.size),
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (!api.isNullOrEmpty()) callback(name.orEmpty(), api)
        }
    }
}

private class LocalConfigImport(
    val name: String,
    val api: String,
    val direct: Boolean,
    val dir: File?,
    val missingRefs: List<String>,
    val sourceDir: String?,
)

private fun importLocalConfig(context: Context, uri: Uri): LocalConfigImport? {
    val displayName = safeFileName(getDisplayName(context, uri))
    val sourceName = localSourceName(displayName)
    if (displayName.endsWith(".py", ignoreCase = true)) {
        return importLocalPySpider(context, uri, displayName)
    }
    if (displayName.endsWith(".js", ignoreCase = true)) {
        return importLocalJsSpider(context, uri, displayName)
    }
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    val path = pathFromUri(context, uri)
    val source = readablePath(path)
    AppLog.i(
        "echo-local-src path granted=" + PermissionHelper.isStorageGranted(context) +
            " parsed=" + path + " src=" + source + " uri=" + uri,
    )
    if (source != null) {
        toClanApi(source, storageRoot)?.let { return LocalConfigImport(sourceName, it, true, null, emptyList(), null) }
    }
    val data = readBytes(context, uri, MAX_CONFIG_SIZE) ?: return null
    val refs = relativeRefs(String(data, Charsets.UTF_8))
    val configDir = File(FileUtils.externalFilesPath(), "config")
    val dir = if (refs.isEmpty()) configDir else File(configDir, requireNotNull(MD5.encode(uri.toString())) { "md5 of uri failed" })
    val file = File(dir, if (refs.isEmpty()) copyFileName(context, uri) else getDisplayName(context, uri))
    if (!writeBytes(file, data)) return null
    val sourceDir = path?.let { File(it).parentFile }
    val missing = if (refs.isEmpty() || sourceDir == null) refs else copyRefs(sourceDir, dir, refs)
    val api = toClanApi(file.absolutePath, storageRoot) ?: return null
    return LocalConfigImport(sourceName, api, false, if (missing.isEmpty()) null else dir, missing, sourceDir?.absolutePath)
}

private fun importLocalPySpider(context: Context, uri: Uri, pyName: String): LocalConfigImport? {
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    val sourceName = localSourceName(pyName)
    val data = readBytes(context, uri, MAX_CONFIG_SIZE) ?: return null
    val digest = requireNotNull(MD5.encode(uri.toString())) { "md5 of uri failed" }
    val dir = File(File(FileUtils.externalFilesPath(), "config"), digest)
    val pyFile = File(dir, "spider_${digest.take(8)}.py")
    if (!writeBytes(pyFile, data)) return null
    val config = PySourcePack.packLocal(
        pyFileName = pyFile.name,
        siteName = sourceName,
        key = "py_${digest.take(8)}",
    )
    val configFile = File(dir, "spider_${digest.take(8)}.json")
    if (!writeBytes(configFile, config.toByteArray(Charsets.UTF_8))) return null
    val api = toClanApi(configFile.absolutePath, storageRoot) ?: return null
    AppLog.i("echo-local-src py-pack name=" + pyName + " py=" + pyFile.absolutePath + " api=" + api)
    return LocalConfigImport(sourceName, api, false, null, emptyList(), null)
}

private fun importLocalJsSpider(context: Context, uri: Uri, jsName: String): LocalConfigImport? {
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    val sourceName = localSourceName(jsName)
    val data = readBytes(context, uri, MAX_CONFIG_SIZE) ?: return null
    val digest = requireNotNull(MD5.encode(uri.toString())) { "md5 of js uri failed" }
    val dir = File(File(FileUtils.externalFilesPath(), "config"), digest)
    val jsFile = File(dir, "spider_${digest.take(8)}.js")
    if (!writeBytes(jsFile, data)) return null
    val jsApi = toClanApi(jsFile.absolutePath, storageRoot) ?: return null
    val config = PySourcePack.packLocalJs(
        jsApi = jsApi,
        siteName = sourceName,
        key = "js_${digest.take(8)}",
    )
    val configFile = File(dir, "spider_${digest.take(8)}.json")
    if (!writeBytes(configFile, config.toByteArray(Charsets.UTF_8))) return null
    val api = toClanApi(configFile.absolutePath, storageRoot) ?: return null
    AppLog.i("echo-local-src js-pack name=" + jsName + " js=" + jsFile.absolutePath + " api=" + api)
    return LocalConfigImport(sourceName, api, false, null, emptyList(), null)
}

private fun readablePath(path: String?): String? {
    if (path.isNullOrEmpty()) return null
    val file = File(path)
    return if (file.isFile && file.canRead()) path else null
}

internal fun toClanApi(path: String?, storageRoot: String): String? {
    if (path.isNullOrEmpty() || !path.startsWith(storageRoot)) return null
    return "clan://localhost/" + path.substring(storageRoot.length).replaceFirst("^/+".toRegex(), "")
}

internal fun localCopyUnit(apiUrl: String?, storageRoot: String, copyRoot: String): File? {
    val url = apiUrl?.substringBefore(";md5;")?.trim().orEmpty()
    if (!url.startsWith("clan://localhost/")) return null
    val rel = url.removePrefix("clan://localhost/").trimStart('/')
    if (rel.isEmpty()) return null
    val root = File(copyRoot).absoluteFile
    val target = File(File(storageRoot), rel.replace('/', File.separatorChar)).absoluteFile
    if (target == root || !target.startsWith(root)) return null
    val parent = target.parentFile ?: return null
    return when {
        parent == root && isMd5Name(target.name.substringBefore('_')) -> target
        parent.parentFile == root && isMd5Name(parent.name) -> parent
        else -> null
    }
}

fun removeLocalCopy(apiUrl: String?): Boolean {
    val unit = localCopyUnit(
        apiUrl,
        Environment.getExternalStorageDirectory().absolutePath,
        File(FileUtils.externalFilesPath(), "config").absolutePath,
    ) ?: return false
    val removed = if (unit.isDirectory) unit.deleteRecursively() else unit.delete()
    AppLog.i("echo-local-src remove copy=" + unit.absolutePath + " ok=" + removed)
    return removed
}

private fun isMd5Name(name: String): Boolean =
    name.length == 32 && name.all { it in "0123456789abcdef" }

private fun pathFromUri(context: Context, uri: Uri): String? {
    return try {
        when {
            "file".equals(uri.scheme, ignoreCase = true) -> uri.path
            "content".equals(uri.scheme, ignoreCase = true) ->
                documentPath(context, uri)
                    ?: dataColumn(context, uri)
                    ?: providerPath(uri.pathSegments, Environment.getExternalStorageDirectory().absolutePath)

            else -> null
        }
    } catch (ignored: Throwable) {
        null
    }
}

internal fun providerPath(segments: List<String>, storageRoot: String): String? {
    val index = segments.indexOfFirst { EXTERNAL_ROOT_SEGMENTS.contains(it) }
    if (index < 0 || index == segments.size - 1) return null
    return "$storageRoot/" + segments.drop(index + 1).joinToString("/")
}

private val EXTERNAL_ROOT_SEGMENTS = setOf("extfiles", "external_files", "external_storage")

private fun documentPath(context: Context, uri: Uri): String? {
    val docId = try {
        DocumentsContract.getDocumentId(uri)
    } catch (ignored: Throwable) {
        return null
    }
    return when (uri.authority) {
        "com.android.externalstorage.documents" ->
            externalStoragePath(docId, Environment.getExternalStorageDirectory().absolutePath)

        "com.android.providers.downloads.documents" -> downloadPath(context, uri, docId)
        "com.android.providers.media.documents" -> mediaPath(context, docId)
        else -> unknownDocIdPath(context, uri, docId)
    }
}

private fun unknownDocIdPath(context: Context, uri: Uri, docId: String): String? = when {
    docId.startsWith("raw:") -> docId.substring(4)
    docId.startsWith("msf:") -> downloadPath(context, uri, docId)
    docId.startsWith("primary:", ignoreCase = true) ->
        externalStoragePath(docId, Environment.getExternalStorageDirectory().absolutePath)

    docId.startsWith("document:") || docId.startsWith("image:") ||
        docId.startsWith("video:") || docId.startsWith("audio:") -> mediaPath(context, docId)

    else -> null
}

internal fun externalStoragePath(docId: String, primaryRoot: String): String? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2 || split[1].isEmpty()) return null
    if (split[1].startsWith("/")) return split[1]
    if ("primary".equals(split[0], ignoreCase = true)) return "$primaryRoot/${split[1]}"
    return "/storage/${docId.replace(':', '/')}"
}

internal fun isMediaStoreDownloadId(docId: String): Boolean = docId.startsWith("msf:")

internal fun downloadNumericId(docId: String): Long? =
    (if (isMediaStoreDownloadId(docId)) docId.substring(4) else docId).toLongOrNull()

internal fun mediaDocId(docId: String): Pair<String, Long>? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2) return null
    val id = split[1].toLongOrNull() ?: return null
    return split[0] to id
}

private fun downloadPath(context: Context, uri: Uri, docId: String): String? {
    if (docId.startsWith("raw:")) return docId.substring(4)
    val id = downloadNumericId(docId) ?: return null
    if (!isMediaStoreDownloadId(docId)) {
        return dataColumn(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val displayName = getDisplayName(context, uri)
    val downloads = ContentUris.withAppendedId(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    dataColumn(context, downloads)?.let { return it }
    val files = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    val path = dataColumn(context, files)
    if (path != null) return if (sameFileName(path, displayName)) path else null
    val guess = downloadGuessPath(Environment.getExternalStorageDirectory().absolutePath, displayName)
    return readablePath(guess)
}

internal fun downloadGuessPath(root: String, displayName: String): String? {
    val name = displayName.substringAfterLast('/').trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    return "$root/Download/$name"
}

internal fun sameFileName(path: String?, displayName: String?): Boolean {
    if (path.isNullOrEmpty() || displayName.isNullOrEmpty()) return false
    return path.substringAfterLast('/') == displayName.substringAfterLast('/')
}

private fun mediaPath(context: Context, docId: String): String? {
    val (type, id) = mediaDocId(docId) ?: return null
    val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external"
    val target = when (type) {
        "image" -> MediaStore.Images.Media.getContentUri(volume)
        "video" -> MediaStore.Video.Media.getContentUri(volume)
        "audio" -> MediaStore.Audio.Media.getContentUri(volume)
        else -> MediaStore.Files.getContentUri(volume)
    }
    return dataColumn(context, ContentUris.withAppendedId(target, id))
}

private fun dataColumn(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    } catch (ignored: Throwable) {
        null
    } finally {
        try {
            cursor?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
    }
}

private fun copyFileName(context: Context, uri: Uri): String =
    MD5.encode(uri.toString()) + "_" + getDisplayName(context, uri)

private const val MAX_CONFIG_SIZE = 32L * 1024 * 1024

private const val MAX_REF_FILE_SIZE = 32L * 1024 * 1024

private const val MAX_REF_TOTAL_SIZE = 64L * 1024 * 1024

internal fun relativeRefs(text: String): List<String> {
    val refs = LinkedHashSet<String>()
    for (match in RELATIVE_REF.findAll(text)) {
        val raw = match.groupValues[1]
            .substringBefore(';')
            .substringBefore('?')
            .substringBefore('#')
        if (raw.isEmpty() || raw.endsWith("/") || raw.contains("..")) continue
        refs.add(raw)
    }
    return refs.toList()
}

private val RELATIVE_REF = Regex("\"\\./([^\"\\\\]*)")

private fun copyRefs(sourceDir: File, destDir: File, refs: List<String>): List<String> {
    val missing = ArrayList<String>()
    var total = 0L
    for (ref in refs) {
        val source = File(sourceDir, ref)
        val size = source.length()
        if (!source.isFile || !source.canRead() || size > MAX_REF_FILE_SIZE || total + size > MAX_REF_TOTAL_SIZE) {
            missing.add(ref)
            continue
        }
        if (copyFile(source, File(destDir, ref))) total += size else missing.add(ref)
    }
    return missing
}

private fun copyRefsFromTree(context: Context, tree: Uri, destDir: File, refs: List<String>): List<String> {
    val missing = ArrayList<String>()
    var total = 0L
    for (ref in refs) {
        val source = LocalSourceTree.findDocument(context, tree, ref)
        val limit = minOf(MAX_REF_FILE_SIZE, MAX_REF_TOTAL_SIZE - total)
        if (source == null || limit <= 0) {
            missing.add(ref)
            continue
        }
        val copied = copyDocument(context, source, File(destDir, ref), limit)
        if (copied == null) missing.add(ref) else total += copied
    }
    return missing
}

private fun copyDocument(context: Context, source: Uri, target: File, limit: Long): Long? {
    var input: InputStream? = null
    var output: FileOutputStream? = null
    var total = 0L
    var done = false
    return try {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return null
        input = context.contentResolver.openInputStream(source) ?: return null
        output = FileOutputStream(target)
        val buffer = ByteArray(8192)
        var length = input.read(buffer)
        while (length != -1) {
            total += length
            if (total > limit) return null
            output.write(buffer, 0, length)
            length = input.read(buffer)
        }
        done = true
        total
    } catch (th: Throwable) {
        AppLog.e("LocalConfigHelper", th)
        null
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
        try {
            input?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
        if (!done && target.exists()) target.delete()
    }
}

private fun readBytes(context: Context, uri: Uri, limit: Long): ByteArray? {
    var input: InputStream? = null
    return try {
        input = context.contentResolver.openInputStream(uri) ?: return null
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var length = input.read(chunk)
        while (length != -1) {
            if (buffer.size() + length > limit) return null
            buffer.write(chunk, 0, length)
            length = input.read(chunk)
        }
        buffer.toByteArray()
    } catch (th: Throwable) {
        AppLog.e("LocalConfigHelper", th)
        null
    } finally {
        try {
            input?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
    }
}

private fun writeBytes(file: File, data: ByteArray): Boolean {
    var output: FileOutputStream? = null
    return try {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        output = FileOutputStream(file)
        output.write(data)
        true
    } catch (th: Throwable) {
        AppLog.e("LocalConfigHelper", th)
        false
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
    }
}

private fun copyFile(source: File, target: File): Boolean {
    var input: InputStream? = null
    var output: FileOutputStream? = null
    return try {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        input = FileInputStream(source)
        output = FileOutputStream(target)
        val buffer = ByteArray(8192)
        var length = input.read(buffer)
        while (length != -1) {
            output.write(buffer, 0, length)
            length = input.read(buffer)
        }
        true
    } catch (th: Throwable) {
        AppLog.e("LocalConfigHelper", th)
        false
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
        try {
            input?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
    }
}

internal fun safeFileName(name: String?): String {
    val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    return if (base.isEmpty() || base == "." || base == "..") "local_config.json" else base
}

private fun getDisplayName(context: Context, uri: Uri): String =
    safeFileName(queryDisplayName(context, uri) ?: uri.lastPathSegment)

private fun queryDisplayName(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index)?.takeIf { it.isNotEmpty() } else null
        } else {
            null
        }
    } catch (ignored: Throwable) {
        AppLog.d("LocalConfigHelper", "query display name failed, fallback to uri segment")
        null
    } finally {
        try {
            cursor?.close()
        } catch (ignored: Throwable) {
            AppLog.d("LocalConfigHelper", "close failed")
        }
    }
}
