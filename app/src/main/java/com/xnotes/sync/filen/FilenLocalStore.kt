package com.xnotes.sync.filen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Reads and writes the note files under the explorer browse root (a SAF tree uri, which
 * also covers the app's internal DocumentsProvider). Mirrors the DocumentsContract calls
 * the Editor already uses, but without depending on it, so background sync can run.
 */
object FilenLocalStore {
    private val NOTE_EXTENSIONS = listOf(".xnote", ".xcanvas")
    private const val SIDECAR_DIR = ".xnote"

    data class LocalEntry(val relativePath: String, val documentUri: String, val size: Long, val modified: Long)

    fun isNote(name: String) = NOTE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    fun listNotes(context: Context, treeUri: String): List<LocalEntry> {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val out = ArrayList<LocalEntry>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<String, String>>().apply { addLast(rootId to "") }
        while (stack.isNotEmpty()) {
            val (docId, prefix) = stack.removeLast()
            if (!seen.add(docId)) continue
            for (child in children(context, tree, docId)) {
                if (child.isDir) {
                    if (child.name == SIDECAR_DIR || child.name.startsWith(".")) continue
                    stack.addLast(child.docId to (if (prefix.isEmpty()) child.name else "$prefix/${child.name}"))
                } else if (isNote(child.name)) {
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    out.add(LocalEntry(rel, child.uri, child.size, child.modified))
                }
            }
        }
        return out
    }

    fun readBytes(context: Context, documentUri: String): ByteArray? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(documentUri))?.use { it.readBytes() } }.getOrNull()

    fun modifiedOf(context: Context, documentUri: String): Long = runCatching {
        context.contentResolver.query(
            Uri.parse(documentUri), arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** Create or overwrite a note at [relativePath] (folders created as needed). Returns the document uri, or null. */
    fun writeNote(context: Context, treeUri: String, relativePath: String, bytes: ByteArray): String? {
        val tree = Uri.parse(treeUri)
        val segments = relativePath.split("/")
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        for (i in 0 until segments.size - 1) {
            parentId = findChild(context, tree, parentId, segments[i], dir = true)
                ?: createDir(context, tree, parentId, segments[i]) ?: return null
        }
        val fileName = segments.last()
        val existing = findChild(context, tree, parentId, fileName, dir = false)
        val uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, existing)
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", fileName)
            }.getOrNull() ?: return null
        }
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            uri.toString()
        }.getOrNull()
    }

    private data class Child(val name: String, val docId: String, val uri: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun children(context: Context, tree: Uri, parentDocId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Child>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val id = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    val size = if (!c.isNull(3)) c.getLong(3) else 0L
                    val modified = if (!c.isNull(4)) c.getLong(4) else 0L
                    out.add(Child(name, id, uri, isDir, size, modified))
                }
            }
        }
        return out
    }

    private fun findChild(context: Context, tree: Uri, parentDocId: String, name: String, dir: Boolean): String? =
        children(context, tree, parentDocId).firstOrNull { it.isDir == dir && it.name.equals(name, ignoreCase = true) }?.docId

    private fun createDir(context: Context, tree: Uri, parentDocId: String, name: String): String? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        val uri = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
        }.getOrNull() ?: return null
        return DocumentsContract.getDocumentId(uri)
    }
}
