package com.github.tvbox.osc.util

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
import com.github.tvbox.osc.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地源导入的中间态:调用页 Activity 注册 `OpenDocument`(选 config.json)与 `OpenDocumentTree`
 * (第二段:源目录授权)两个契约,结果分别交给 [handleLocalConfigResult] / [handleLocalSourceTreeResult]。
 * 唯一入口 = 配置管理页「添加订阅」dialog 的「从本地选择」。
 */
object LocalConfigHost {
    /** 等待中的结果回调(同一时刻只会有一个导入流程) */
    var pending: ((api: String) -> Unit)? = null

    /** 复制路线已算出的地址;要补同目录引用时挂起,等目录授权回来再收尾 */
    var pendingApi: String? = null

    /** 复制路线:副本落点 + 还没搬过来的同目录引用 */
    var pendingDir: File? = null
    var pendingRefs: List<String> = emptyList()

    /** 原文件所在目录;只用于判断"这个落点系统永不允许授权" */
    var pendingSourceDir: String? = null
}

/** 收尾要碰 UI,申请权限还得起 Activity,只能在主线程 */
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

private fun onMain(work: () -> Unit) {
    mainHandler.post(work)
}

/** 读盘一律走它(上限 32MB/64MB,挂主线程会卡界面);单线程串行也保证"同一时刻只有一个导入流程" */
private val importWorker: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "local-config-import") }
}

/** 启动系统文件选择器;挂载见 `ConfigManageActivity.localConfigLauncher` */
fun startLocalConfig(
    launcher: ActivityResultLauncher<Array<String>>,
    onResult: (api: String) -> Unit,
) {
    LocalConfigHost.pending = onResult
    launcher.launch(arrayOf("*/*"))
}

/**
 * SAF 结果入口:选中的 Uri 转成 clan:// 地址。读盘在 [importWorker],结果回主线程才动 UI / 权限页 / 挂起态。
 * **导入不因权限中断**:读得到就直引原文件,读不到就复制进应用私有目录,地址一定会交回。
 *
 * @param onFinish 主线程回调:true = 复制后仍缺同目录引用,调用方先争一次存储权限(拿到多半直接改成直引)、拿不到再拉目录选择器补齐;地址此刻已可用,取消也照样完成导入
 */
fun handleLocalConfigResult(activity: Activity, uri: Uri, onFinish: (Boolean) -> Unit) {
    val callback = LocalConfigHost.pending ?: return
    LocalConfigHost.pending = null
    // 后台只拿 applicationContext:它活得比 Activity 久,别把 Activity 一起兜进去
    val context = activity.applicationContext ?: activity
    importWorker.execute {
        // 意外异常必须自己兜住:后台线程抛出去只会静默丢掉这次导入(主线程会崩,后台不会)
        val result = try {
            importLocalConfig(context, uri)
        } catch (th: Throwable) {
            th.printStackTrace()
            null
        }
        onMain {
            // 读盘期间页面可能已经走了:此时弹 Toast / 拉权限页 / 交付地址都作用不到用户身上
            if (activity.isFinishing || activity.isDestroyed) {
                LOG.i("echo-local-src drop, page gone uri=" + uri)
                return@onMain
            }
            if (result == null) {
                Toast.makeText(activity, activity.getString(R.string.toast_local_config_read_failed), Toast.LENGTH_SHORT).show()
                onFinish(false)
                return@onMain
            }
            LOG.i(
                "echo-local-src import api=" + result.api + " missing=" + result.missingRefs.size +
                    " direct=" + result.direct + " uri=" + uri,
            )
            // 直引绑的是原文件,读不读得到只看权限/目录授权,不在手就顺手要一次;不阻断 —— 拒绝只影响重启后是否还读得到(拉取失败时由 ApiConfig 报错)
            if (result.direct && !PermissionHelper.isStorageGranted(activity)) {
                PermissionHelper.requestStorage(activity) { _, _ -> }
            }
            if (result.missingRefs.isEmpty()) {
                callback(result.api)
                onFinish(false)
                return@onMain
            }
            LocalConfigHost.pending = callback
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

/**
 * 第二段结果:把没搬过来的同目录引用从授权目录搬进副本目录,再交回地址(SAF 读盘走 [importWorker])。
 * 取消 / 选错目录按"受限目录"给结论,不静默;地址此刻已可用,补不齐也不影响导入。
 */
fun handleLocalSourceTreeResult(activity: Activity, tree: Uri?) {
    val callback = LocalConfigHost.pending
    val api = LocalConfigHost.pendingApi
    val dir = LocalConfigHost.pendingDir
    val refs = LocalConfigHost.pendingRefs
    val sourceDir = LocalConfigHost.pendingSourceDir
    LocalConfigHost.pending = null
    LocalConfigHost.pendingApi = null
    LocalConfigHost.pendingDir = null
    LocalConfigHost.pendingRefs = emptyList()
    LocalConfigHost.pendingSourceDir = null
    if (callback == null) return
    if (tree == null || dir == null) {
        // 配置落在系统永不允许授权的目录(存储根 / Download 根 / Android/data)时,"再点一次"不会成,得给条出路
        val tip = if (isUngrantableDir(sourceDir, Environment.getExternalStorageDirectory().absolutePath)) {
            R.string.toast_local_tree_forbidden
        } else {
            R.string.toast_local_tree_denied
        }
        Toast.makeText(activity, activity.getString(tip), Toast.LENGTH_LONG).show()
        if (!api.isNullOrEmpty()) callback(api)
        return
    }
    val context = activity.applicationContext ?: activity
    importWorker.execute {
        // 记住这次授权:本地服务(/file/)也能靠它读这个目录
        LocalSourceTree.remember(context, tree)
        // 异常按"全没搬过来"收尾:缺文件必须让用户看到,不能静默
        val missing = try {
            copyRefsFromTree(context, tree, dir, refs)
        } catch (th: Throwable) {
            th.printStackTrace()
            refs
        }
        onMain {
            if (activity.isFinishing || activity.isDestroyed) return@onMain
            LOG.i("echo-local-src tree missing=" + missing.size + " of=" + refs.size)
            if (missing.isNotEmpty()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_local_refs_missing, missing.size),
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (!api.isNullOrEmpty()) callback(api)
        }
    }
}

/** 导入结果:可用地址、是否直引原文件、副本落点 + 还缺的同目录引用、原文件所在目录(只用于受限目录判定) */
private class LocalConfigImport(
    val api: String,
    val direct: Boolean,
    val dir: File?,
    val missingRefs: List<String>,
    val sourceDir: String?,
)

/**
 * 配置 Uri → clan:// 地址:读得到原文件就直引(原目录改动立刻生效、不占空间),读不到才复制到外置私有 `files/config/`。
 * 判据只认"此刻真读得到":权限查询在部分 ROM 上与真实可读性不一致,拿它当前提会把读得到的文件也拦下来。
 */
private fun importLocalConfig(context: Context, uri: Uri): LocalConfigImport? {
    val displayName = safeFileName(getDisplayName(context, uri))
    // 选中的是单个 py 爬虫:自动包成单站点配置,用户不必手写 json
    if (displayName.endsWith(".py", ignoreCase = true)) {
        return importLocalPySpider(context, uri, displayName)
    }
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    val path = getPathFromUri(context, uri)
    val source = readablePath(path)
    LOG.i(
        "echo-local-src path granted=" + PermissionHelper.isStorageGranted(context) +
            " src=" + source + " uri=" + uri,
    )
    if (source != null) {
        toClanApi(source, storageRoot)?.let { return LocalConfigImport(it, true, null, emptyList(), null) }
    }
    val data = readBytes(context, uri, MAX_CONFIG_SIZE) ?: return null
    val refs = relativeRefs(String(data, Charsets.UTF_8))
    val configDir = File(FileUtils.getExternalFilesPath(), "config")
    val dir = if (refs.isEmpty()) configDir else File(configDir, MD5.encode(uri.toString()))
    val file = File(dir, if (refs.isEmpty()) copyFileName(context, uri) else getDisplayName(context, uri))
    if (!writeBytes(file, data)) return null
    // 原路径可能读不到(所以走了复制),但父目录要留着:File API 能读时兄弟文件就在这里
    val sourceDir = path?.let { File(it).parentFile }
    val missing = if (refs.isEmpty() || sourceDir == null) refs else copyRefs(sourceDir, dir, refs)
    val api = toClanApi(file.absolutePath, storageRoot) ?: return null
    return LocalConfigImport(api, false, if (missing.isEmpty()) null else dir, missing, sourceDir?.absolutePath)
}

/**
 * 选中的是单个 py 爬虫:复制到 files/config/<md5(uri)>/ 并生成一份单站点配置(api 走 `./` 相对引用,
 * 加载阶段会被改写成可访问的本机服务地址)。副本名用 ASCII —— 中文文件名进 URL 有编码风险。
 */
private fun importLocalPySpider(context: Context, uri: Uri, pyName: String): LocalConfigImport? {
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    val data = readBytes(context, uri, MAX_CONFIG_SIZE) ?: return null
    val digest = MD5.encode(uri.toString())
    val dir = File(File(FileUtils.getExternalFilesPath(), "config"), digest)
    val pyFile = File(dir, "spider_${digest.take(8)}.py")
    if (!writeBytes(pyFile, data)) return null
    val config = PySourcePack.packLocal(
        pyFileName = pyFile.name,
        siteName = pyName.substringBeforeLast('.'),
        key = "py_${digest.take(8)}",
    )
    val configFile = File(dir, "spider_${digest.take(8)}.json")
    if (!writeBytes(configFile, config.toByteArray(Charsets.UTF_8))) return null
    val api = toClanApi(configFile.absolutePath, storageRoot) ?: return null
    LOG.i("echo-local-src py-pack name=" + pyName + " py=" + pyFile.absolutePath + " api=" + api)
    return LocalConfigImport(api, false, null, emptyList(), null)
}

/** 直引前校验存在且可读:MediaStore 的 DATA 列可能指向已删除/已移动的文件,直引会让整个源拉取失败 */
private fun readablePath(path: String?): String? {
    if (path.isNullOrEmpty()) return null
    val file = File(path)
    return if (file.isFile && file.canRead()) path else null
}

/** 真实路径 → clan:// 地址;为空或不在本地服务根目录下时返回 null(= 应走复制) */
internal fun toClanApi(path: String?, storageRoot: String): String? {
    if (path.isNullOrEmpty() || !path.startsWith(storageRoot)) return null
    return "clan://localhost/" + path.substring(storageRoot.length).replaceFirst("^/+".toRegex(), "")
}

/**
 * 订阅地址指向的"应用自己生成的本地副本"(整目录或单文件);不是副本返回 null。
 * 只认 clan://localhost/ 且真实路径必须落在 files/config/ 内 —— 用户原文件、原目录一律不碰。
 */
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

/** 删除订阅时清掉它的本地副本;返回是否真删了东西 */
fun removeLocalCopy(apiUrl: String?): Boolean {
    val unit = localCopyUnit(
        apiUrl,
        Environment.getExternalStorageDirectory().absolutePath,
        File(FileUtils.getExternalFilesPath(), "config").absolutePath,
    ) ?: return false
    val removed = if (unit.isDirectory) unit.deleteRecursively() else unit.delete()
    LOG.i("echo-local-src remove copy=" + unit.absolutePath + " ok=" + removed)
    return removed
}

/** 副本目录名 / 单文件前缀 = md5(导入时的 uri),32 位小写十六进制;不符合该约定的一律不删 */
private fun isMd5Name(name: String): Boolean =
    name.length == 32 && name.all { it in "0123456789abcdef" }

/**
 * SAF Uri → 真实文件路径;解析不出返回 null(= 应复制)。
 *
 * ⚠️ 不能先问 `DocumentsContract.isDocumentUri`:它要经 PackageManager 确认该 authority 是文档 provider,
 * 在包可见性/部分 ROM 上会对合法文档 Uri 判 false,于是掉进 `getDataColumn`(文档 provider 没有 DATA 列)
 * ⇒ 明明能直引也被当成"解析不出",错误地走复制分支。
 */
private fun getPathFromUri(context: Context, uri: Uri): String? {
    return try {
        when {
            "file".equals(uri.scheme, ignoreCase = true) -> uri.path
            "content".equals(uri.scheme, ignoreCase = true) ->
                getDocumentPath(context, uri)
                    ?: getDataColumn(context, uri)
                    ?: providerPath(uri.pathSegments, Environment.getExternalStorageDirectory().absolutePath)

            else -> null
        }
    } catch (ignored: Throwable) {
        null
    }
}

/**
 * 第三方文件管理器的 FileProvider 形态:`content://<pkg>.fileprovider/extfiles/<外置存储相对路径>`
 * (vivo 文件管理器就是这种)既没有 docId 也没有 DATA 列,但路径本身就在 Uri 里 ⇒ 按外置存储根拼。
 * 只认已知的"外置存储根"段名,是否真存在仍由调用方的 `readablePath` 把关。
 */
internal fun providerPath(segments: List<String>, storageRoot: String): String? {
    val index = segments.indexOfFirst { EXTERNAL_ROOT_SEGMENTS.contains(it) }
    if (index < 0 || index == segments.size - 1) return null
    return "$storageRoot/" + segments.drop(index + 1).joinToString("/")
}

/** FileProvider 里指代外置存储根的段名;故意不收 `external` —— `content://media/external/…` 这种会误判 */
private val EXTERNAL_ROOT_SEGMENTS = setOf("extfiles", "external_files", "external_storage")

/**
 * 文档 Uri → 路径:按 provider 逐一解析(`primary:`/`raw:`、`msf:` 三段回退、`document:`),解析不出的仍复制;
 * 下面几个纯字符串函数由 `LocalConfigPathTest` 覆盖。
 */
private fun getDocumentPath(context: Context, uri: Uri): String? {
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

/** 第三方文件管理器等自定义 provider 的 authority 不认识,但 docId 形态与 AOSP 文档 provider 一致 ⇒ 按前缀兜底 */
private fun unknownDocIdPath(context: Context, uri: Uri, docId: String): String? = when {
    docId.startsWith("raw:") -> docId.substring(4)
    docId.startsWith("msf:") -> downloadPath(context, uri, docId)
    docId.startsWith("primary:", ignoreCase = true) ->
        externalStoragePath(docId, Environment.getExternalStorageDirectory().absolutePath)

    docId.startsWith("document:") || docId.startsWith("image:") ||
        docId.startsWith("video:") || docId.startsWith("audio:") -> mediaPath(context, docId)

    else -> null
}

/** externalstorage:`primary:` 挂外置存储根,`XXXX-XXXX:`(SD 卡)挂 `/storage/<卷名>` */
internal fun externalStoragePath(docId: String, primaryRoot: String): String? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2 || split[1].isEmpty()) return null
    if ("primary".equals(split[0], ignoreCase = true)) return "$primaryRoot/${split[1]}"
    return "/storage/${docId.replace(':', '/')}"
}

/** downloads 的 docId 是否 MediaStore 形态(`msf:<id>`;老条目是 `raw:<绝对路径>` 或纯数字 id) */
internal fun isMediaStoreDownloadId(docId: String): Boolean = docId.startsWith("msf:")

/** downloads 的数值 id(已去 `msf:` 前缀);非数字(含 `raw:` 形态)返回 null */
internal fun downloadNumericId(docId: String): Long? =
    (if (isMediaStoreDownloadId(docId)) docId.substring(4) else docId).toLongOrNull()

/** media 的 docId(`image:123`/`document:456`)→ 类型与数值 id;解析不了返回 null */
internal fun mediaDocId(docId: String): Pair<String, Long>? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2) return null
    val id = split[1].toLongOrNull() ?: return null
    return split[0] to id
}

/**
 * downloads → 真实路径;`msf:` 需 API 29+。三段尝试:`MediaStore.Downloads` → `MediaStore.Files`
 * (前两段常因该行 `is_download=0` —— 文件是拷进 Download 目录而非下载器下载的 —— 查不到)→
 * 按显示名拼 `<外置存储根>/Download/<名>`。
 *
 * 后两段都设闸:第二段**有结果但名字不符即放弃**(id 指向别的文件时不能再叠加猜测,猜同名路径
 * 可能引到另一个同名文件);第三段只取 basename 且须过 [readablePath]。宁可 null 走复制。
 */
private fun downloadPath(context: Context, uri: Uri, docId: String): String? {
    if (docId.startsWith("raw:")) return docId.substring(4)
    val id = downloadNumericId(docId) ?: return null
    if (!isMediaStoreDownloadId(docId)) {
        return getDataColumn(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val displayName = getDisplayName(context, uri)
    val downloads = ContentUris.withAppendedId(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    getDataColumn(context, downloads)?.let { return it }
    val files = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
    val path = getDataColumn(context, files)
    if (path != null) return if (sameFileName(path, displayName)) path else null
    val guess = downloadGuessPath(Environment.getExternalStorageDirectory().absolutePath, displayName)
    return readablePath(guess)
}

/** 「下载」兜底猜测路径 = `<外置存储根>/Download/<显示名>`;名字为空 / `.` / `..` 时 null(纯函数,单测覆盖) */
internal fun downloadGuessPath(root: String, displayName: String): String? {
    val name = displayName.substringAfterLast('/').trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    return "$root/Download/$name"
}

/** 两个路径的文件名是否一致(id 是否指向选中文件的校验);任一为空即 false */
internal fun sameFileName(path: String?, displayName: String?): Boolean {
    if (path.isNullOrEmpty() || displayName.isNullOrEmpty()) return false
    return path.substringAfterLast('/') == displayName.substringAfterLast('/')
}

/** media → 真实路径;`document:`(「最近」里的 json/txt/m3u)走 MediaStore.Files */
private fun mediaPath(context: Context, docId: String): String? {
    val (type, id) = mediaDocId(docId) ?: return null
    val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external"
    val target = when (type) {
        "image" -> MediaStore.Images.Media.getContentUri(volume)
        "video" -> MediaStore.Video.Media.getContentUri(volume)
        "audio" -> MediaStore.Audio.Media.getContentUri(volume)
        else -> MediaStore.Files.getContentUri(volume)
    }
    return getDataColumn(context, ContentUris.withAppendedId(target, id))
}

/** 查 MediaStore 的 DATA 列;查询失败一律按"解析不出"处理,由调用方复制兜底 */
private fun getDataColumn(context: Context, uri: Uri): String? {
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
            LOG.d("LocalConfigHelper", "close failed")
        }
    }
}

/** 副本文件名 = `md5(uri)_原名`:同一文件重复导入覆盖自己,不同来源的同名文件互不影响 */
private fun copyFileName(context: Context, uri: Uri): String =
    MD5.encode(uri.toString()) + "_" + getDisplayName(context, uri)

/** 配置本体上限:超了按"选错文件"处理,不整份读进内存 */
private const val MAX_CONFIG_SIZE = 32L * 1024 * 1024

/** 单个被引用文件上限(复制在 ActivityResult 回调 = 主线程里做,配置误引用大文件不能把界面卡死) */
private const val MAX_REF_FILE_SIZE = 32L * 1024 * 1024

/** 被引用文件的总上限 */
private const val MAX_REF_TOTAL_SIZE = 64L * 1024 * 1024

/** 配置文本里 `"./x"` 形式的同目录引用(去掉 `;md5;`/`?`/`#` 尾巴、去重;空值、目录、含 `..` 的一律跳过) */
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

/** `"./` 之后、引号或转义符之前的原样路径 */
private val RELATIVE_REF = Regex("\"\\./([^\"\\\\]*)")

/**
 * 按原相对结构把被引用文件复制到 [destDir];返回没能搬过来的那些(源缺失/读不到/超限/写失败)。
 * `refs` 已剔除 `..`,故落点必然在 [destDir] 内,不会有越界写入。
 */
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

/**
 * 第二段:从目录树里按相对路径把被引用文件搬进 [destDir]。
 *
 * 走 SAF 文档流而不是 File API —— 源目录用 File API 读不到时(未授权 / ROM 限制)这条仍然可用,
 * 所以它是"复制分支搬兄弟文件"的最后兜底。同样返回没搬过来的那些。
 */
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

/** 文档流 → [target];返回写入的字节数,失败/超限返回 null(半个文件比没有更坏,失败即删掉) */
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
        th.printStackTrace()
        null
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
        }
        try {
            input?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
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
        th.printStackTrace()
        null
    } finally {
        try {
            input?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
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
        th.printStackTrace()
        false
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
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
        th.printStackTrace()
        false
    } finally {
        try {
            output?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
        }
        try {
            input?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
        }
    }
}

/**
 * 只取 basename;空 / `.` / `..` 一律回落默认名 —— 显示名来自 provider,原样当文件名用会写到目标目录之外。
 */
internal fun safeFileName(name: String?): String {
    val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    return if (base.isEmpty() || base == "." || base == "..") "local_config.json" else base
}

/** 显示名优先 DISPLAY_NAME,答不上退到 `Uri.lastPathSegment`:退成常量会让"是不是 py 爬虫"判错 */
private fun getDisplayName(context: Context, uri: Uri): String =
    safeFileName(queryDisplayName(context, uri) ?: uri.lastPathSegment)

/** 查 DISPLAY_NAME;查不到返回 null(由调用方退到 Uri 尾段) */
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
        LOG.d("LocalConfigHelper", "query display name failed, fallback to uri segment")
        null
    } finally {
        try {
            cursor?.close()
        } catch (ignored: Throwable) {
            LOG.d("LocalConfigHelper", "close failed")
        }
    }
}
