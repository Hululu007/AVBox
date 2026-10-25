package com.github.avbox.osc.io

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import java.io.InputStream

object LocalSourceTree {

    fun remember(context: Context, tree: Uri): String? {
        val path = treePath(context, tree) ?: return null
        try {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (th: Throwable) {
            AppLog.e("LocalSourceTree", th)
        }
        val trees = ArrayList(remembered(context))
        if (!trees.contains(tree.toString())) {
            trees.add(tree.toString())
            KV.put(KVKeys.LOCAL_SOURCE_TREES, trees)
        }
        return path
    }

    fun remembered(context: Context): List<String> =
        KV.get(KVKeys.LOCAL_SOURCE_TREES, arrayListOf<String>())

    fun serves(context: Context, path: String): Boolean = remembered(context).any { text ->
        val tree = treePath(context, Uri.parse(text)) ?: return@any false
        relativeUnder(tree, path) != null
    }

    fun open(context: Context, relativePath: String): InputStream? {
        val target = Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
        for (text in remembered(context)) {
            val tree = Uri.parse(text)
            val path = treePath(context, tree) ?: continue
            val relative = relativeUnder(path, target) ?: continue
            val document = findDocument(context, tree, relative) ?: continue
            try {
                context.contentResolver.openInputStream(document)?.let { return it }
            } catch (th: Throwable) {
                AppLog.e("LocalSourceTree", th)
            }
        }
        return null
    }

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
                AppLog.d("LocalSourceTree", "close cursor failed")
            }
        }
    }

    private fun treePath(context: Context, tree: Uri): String? = try {
        treeDocPath(
            DocumentsContract.getTreeDocumentId(tree),
            Environment.getExternalStorageDirectory().absolutePath,
        )
    } catch (ignored: Throwable) {
        null
    }

}

internal fun relativeUnder(root: String, path: String): String? {
    if (!path.startsWith("$root/")) return null
    return path.substring(root.length + 1)
}

internal fun treeDocPath(docId: String, primaryRoot: String): String? = when {
    docId.startsWith("raw:") -> docId.substring(4).ifEmpty { null }
    docId.startsWith("/") -> docId
    else -> externalStoragePath(docId, primaryRoot) ?: volumeRootPath(docId, primaryRoot)
}

private fun volumeRootPath(docId: String, primaryRoot: String): String? {
    val split = docId.split(":", limit = 2)
    if (split.size < 2 || split[1].isNotEmpty()) return null
    return if ("primary".equals(split[0], ignoreCase = true)) primaryRoot else "/storage/${split[0]}"
}

internal fun isUngrantableDir(path: String?, primaryRoot: String): Boolean {
    val dir = path?.trimEnd('/') ?: return false
    if (dir == primaryRoot || dir == "$primaryRoot/Download") return true
    if (dir.startsWith("$primaryRoot/Android/data") || dir.startsWith("$primaryRoot/Android/obb")) return true
    return dir.startsWith("/storage/") && dir.count { it == '/' } == 2
}
