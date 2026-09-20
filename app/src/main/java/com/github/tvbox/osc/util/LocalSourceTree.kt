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
 * 应用读不到源目录时(没开「所有文件访问」/ ROM 限制),由用户给一次目录授权并记住,本地服务
 * (见 `RemoteServer` 的 `/file/`)再用它把原目录的文件读出来 —— 源地址因此可以指向原目录(直引),
 * 不必把文件复制进应用目录。单文件授权拿不到同目录的兄弟文件,只有目录授权可以。
 */
object LocalSourceTree {

    /** 记住这次目录授权,返回该目录的真实路径;返回 null = 该 provider 映射不出本地路径(如网盘),不记录 */
    fun remember(context: Context, tree: Uri): String? {
        val path = treePath(context, tree) ?: return null
        try {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (th: Throwable) {
            // 少数 ROM 不给持久化授权:本次会话内仍可读,重启后失效;调用方按 [isPersisted] 决定要不要提示
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

    /** 授权是否已持久化(未持久化时本次会话可用、重启后失效) */
    fun isPersisted(context: Context, tree: Uri): Boolean = try {
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
    } catch (ignored: Throwable) {
        false
    }

    /** 该路径是否已被某个**已持久化**的授权目录覆盖(重复导入同一源时省掉再选一次目录) */
    fun covers(context: Context, path: String): Boolean = remembered(context).any { text ->
        val uri = Uri.parse(text)
        val tree = treePath(context, uri) ?: return@any false
        isPersisted(context, uri) && path.startsWith("$tree/")
    }

    /** 按"外置存储相对路径"从授权目录里打开文件(本地服务用);没有匹配的目录/文件返回 null */
    fun open(context: Context, relativePath: String): InputStream? {
        val target = Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
        // 不按 isPersisted 过滤:会话级授权(未持久化成)本次照样能读,读不动的那次自然跳过
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

    private fun findChildId(context: Context, tree: Uri, parentId: String, name: String): String? {
        var cursor: Cursor? = null
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            cursor = context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )
            while (cursor != null && cursor.moveToNext()) {
                if (name == cursor.getString(1)) return cursor.getString(0)
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

    /** 目录树 → 真实路径;只在 docId 形如 `primary:<相对路径>` / `XXXX-XXXX:<相对路径>` 时成立 */
    private fun treePath(context: Context, tree: Uri): String? = try {
        externalStoragePath(
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
