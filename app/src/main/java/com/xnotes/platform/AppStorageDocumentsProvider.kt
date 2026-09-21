package com.xnotes.platform

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.xnotes.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes an app-owned directory as a SAF tree, so the in-app explorer can browse "Internal
 * storage" with the very same DocumentsContract calls it uses for a user-granted folder. Document
 * ids are paths relative to the root ("root", "root/sub", "root/sub/note.xnote"), so they stay
 * valid even if the backing directory ever moves. The framework requires a DocumentsProvider to
 * be exported and MANAGE_DOCUMENTS-protected; with no DOCUMENTS_PROVIDER intent filter it stays
 * out of the system file picker, so only xnotes reaches it. Its files go on app uninstall.
 */
class AppStorageDocumentsProvider : DocumentsProvider() {

    private val baseDir: File get() = rootDir(context!!)

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, documentId, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        fileFor(parentDocumentId).listFiles()?.forEach { child -> includeFile(cursor, docIdFor(child), child) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    // Ids are paths, so the chain from [parentDocumentId] down to the child is just its successive prefixes.
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val top = parentDocumentId ?: ROOT_DOC_ID
        val ids = com.xnotes.core.util.DocKeys.chain(top, childDocumentId)
            ?: throw FileNotFoundException("$childDocumentId is not under $top")
        return DocumentsContract.Path(if (parentDocumentId == null) ROOT_DOC_ID else null, ids)
    }

    override fun getDocumentType(documentId: String): String = mimeOf(fileFor(documentId))

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.exists()) parent.mkdirs()
        val target = freeChild(parent, displayName)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir()
        else runCatching { target.createNewFile() }.getOrDefault(false)
        if (!ok) throw FileNotFoundException("Couldn't create $displayName under $parentDocumentId")
        return docIdFor(target)
    }

    override fun deleteDocument(documentId: String) {
        if (!fileFor(documentId).deleteRecursively()) throw FileNotFoundException("Couldn't delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val target = File(file.parentFile, displayName)
        if (target.exists()) throw FileNotFoundException("$displayName already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("Couldn't rename $documentId")
        return docIdFor(target)
    }

    // Native copy keeps the source's name and recurses into subfolders; it throws on a name clash so
    // the caller (Editor.copyDocumentInto) can fall through to making a renamed duplicate instead.
    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val source = fileFor(sourceDocumentId)
        val dest = File(fileFor(targetParentDocumentId), source.name)
        if (dest.exists()) throw FileNotFoundException("${source.name} already exists")
        copyTree(source, dest)
        return docIdFor(dest)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val source = fileFor(sourceDocumentId)
        val dest = File(fileFor(targetParentDocumentId), source.name)
        if (dest.exists()) throw FileNotFoundException("${source.name} already exists")
        if (!source.renameTo(dest)) throw FileNotFoundException("Couldn't move $sourceDocumentId")
        return docIdFor(dest)
    }

    private fun fileFor(documentId: String): File =
        if (documentId == ROOT_DOC_ID) baseDir else File(baseDir, documentId.removePrefix("$ROOT_DOC_ID/"))

    private fun docIdFor(file: File): String {
        if (file == baseDir) return ROOT_DOC_ID
        return "$ROOT_DOC_ID/" + file.absolutePath.removePrefix(baseDir.absolutePath + File.separator)
    }

    private fun includeFile(cursor: MatrixCursor, documentId: String, file: File) {
        val isDir = file.isDirectory
        var flags = if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (documentId != ROOT_DOC_ID) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_DOC_ID) context?.getString(R.string.app_storage).orEmpty() else file.name)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file))
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(file: File): String =
        if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"

    // A free name in [parent]: the given one, else "<stem> (N)<.ext>" (SAF's de-dupe convention).
    private fun freeChild(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) candidate = File(parent, "$stem ($n)$ext").also { n++ }
        return candidate
    }

    private fun copyTree(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dest, it.name)) }
        } else {
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }

    companion object {
        private const val ROOT_DOC_ID = "root"
        private const val DIR_NAME = "Notes"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_ICON,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        private fun authority(context: Context): String = context.packageName + ".documents"

        /** The app's private notes directory (app-external, removed on uninstall); created on demand. */
        fun rootDir(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
        }

        /** The SAF tree uri for that directory, usable as an explorer browse root. */
        fun treeUri(context: Context): Uri {
            rootDir(context)
            return DocumentsContract.buildTreeDocumentUri(authority(context), ROOT_DOC_ID)
        }
    }
}
