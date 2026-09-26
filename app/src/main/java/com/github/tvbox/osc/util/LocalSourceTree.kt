package com.github.tvbox.osc.util

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * 本地源目录授权(SAF `OpenDocumentTree`,持久化)的驻留与解析。
 *
 * 用途只有一处:复制路线补不齐同目录引用(`./x.jar` 这类 File API 读不到原目录)时,靠它把兄弟文件搬进副本目录;
 * 授权同时被记住,本地服务(`RemoteServer` 的 `/file/`)也能靠它读该目录。单文件授权拿不到兄弟文件。
 */
object LocalSourceTree {

    /** 记住这次目录授权,返回该目录的真实路径;返回 null = 该 provider 映射不出本地路径(如网盘),不记录 */
    fun remember(context: Context, tree: Uri): String? {
        val path = treePath(context, tree) ?: return null
        try {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (th: Throwable) {
            // 少数 ROM 不给持久化授权:本次会话内仍可读,重启后失效(补同目录引用这一次不依赖它)
            th.printStackTrace()
        }
        val trees = ArrayList(remembered(context))
        if (!trees.contains(tree.toString())) {
            trees.add(tree.toString())
            KV.put(HawkConfig.LOCAL_SOURCE_TREES, trees)
        }
        return path
    }

    fun remembered(context: Context): List<String> =
        KV.get(HawkConfig.LOCAL_SOURCE_TREES, arrayListOf<String>())

    /** 本地服务此刻能否靠已记住的授权目录读到 [path](会话级授权也算,与 [open] 同口径) */
    fun serves(context: Context, path: String): Boolean = remembered(context).any { text ->
        val tree = treePath(context, Uri.parse(text)) ?: return@any false
        relativeUnder(tree, path) != null
    }

    /** 按"外置存储相对路径"从授权目录里打开文件(本地服务用);没有匹配的目录/文件返回 null */
    fun open(context: Context, relativePath: String): InputStream? {
        val target = Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
        // 不按"是否持久化"过滤:会话级授权本次照样能读,读不动的那次自然跳过
        for (text in remembered(context)) {
            val tree = Uri.parse(text)
            val path = treePath(context, tree) ?: continue
            val relative = relativeUnder(path, target) ?: continue
            val document = findDocument(context, tree, relative) ?: continue
            try {
                context.contentResolver.openInputStream(document)?.let { return it }
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }
        return null
    }

    /** 目录树里按相对路径逐级找文档:`jar/1.jar` = 先找 `jar` 目录、再在其下找 `1.jar` */
    fun findDocument(context: Context, tree: Uri, relative: String): Uri? {
        var documentId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (ignored: Throwable) {
            return null
        }
        for (name in relative.split('/')) {
            if (name.isEmpty()) continue
            documentId = findChildId(context, tree, documentId, name) ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    }

    /**
     * 找目录里显示名为 [name] 的子文档 docId。
     *
     * 列下标必须现查:provider 可能按自己的列序返回,按下标取会拿错列 ⇒ 名字永远对不上 ⇒ 文件明明在却找不到。
     */
    private fun findChildId(context: Context, tree: Uri, parentId: String, name: String): String? {
        var cursor: Cursor? = null
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val queried = context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            ) ?: return null
            cursor = queried
            val idIndex = queried.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = queried.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIndex < 0 || nameIndex < 0) return null
            while (queried.moveToNext()) {
                if (name == queried.getString(nameIndex)) return queried.getString(idIndex)
            }
            null
        } catch (ignored: Throwable) {
            null
        } finally {
            try {
                cursor?.close()
            } catch (ignored: Throwable) {
                LOG.d("LocalSourceTree", "close cursor failed")
            }
        }
    }

    /** 目录树 → 真实路径;走 [treeDocPath],比文件形态宽 —— 收窄会让用户选对文件夹也被判成"没授权" */
    private fun treePath(context: Context, tree: Uri): String? = try {
        treeDocPath(
            DocumentsContract.getTreeDocumentId(tree),
            Environment.getExternalStorageDirectory().absolutePath,
        )
    } catch (ignored: Throwable) {
        null
    }

}

/** [path] 在 [root] 之下时返回相对部分(不含前缀 `/`);等于 root 或不在其下返回 null */
internal fun relativeUnder(root: String, path: String): String? {
    if (!path.startsWith("$root/")) return null
    return path.substring(root.length + 1)
}

/** 目录树 docId → 真实路径:文件形态解析不出的卷根(`primary:` / `XXXX-XXXX:`)、`raw:`、绝对路径都认;认不出返回 null */
internal fun treeDocPath(docId: String, primaryRoot: String): String? = when {
    docId.startsWith("raw:") -> docId.substring(4).ifEmpty { null }
    docId.startsWith("/") -> docId
    else -> externalStoragePath(docId, primaryRoot) ?: volumeRootPath(docId, primaryRoot)
}

/** 卷根(冒号后为空):`primary:` → 存储根,`XXXX-XXXX:` → `/storage/<卷名>` */
private fun volumeRootPath(docId: String, primaryRoot: String): String? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2 || split[1].isNotEmpty()) return null
    return if ("primary".equals(split[0], ignoreCase = true)) primaryRoot else "/storage/${split[0]}"
}

/**
 * 该**目录**是否系统永不允许授权(Android 11+ 对 targetSdk≥30 禁用这些目录的 SAF 授权):
 * 存储根、`Download` 根、`Android/data|obb` 及其子目录、副卷卷根(如 SD 卡)。子目录不受限。
 *
 * 用途:失败时给出"把配置挪进自建文件夹"的出路 —— 用户反复"再点一次"永远也不会成。
 */
internal fun isUngrantableDir(path: String?, primaryRoot: String): Boolean {
    val dir = path?.trimEnd('/') ?: return false
    if (dir == primaryRoot || dir == "$primaryRoot/Download") return true
    if (dir.startsWith("$primaryRoot/Android/data") || dir.startsWith("$primaryRoot/Android/obb")) return true
    return dir.startsWith("/storage/") && dir.count { it == '/' } == 2
}
