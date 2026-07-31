package com.xnotes.platform

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.CanvasCodec
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The working session for an infinite canvas: the open document, including edits never written to
 * its file, so relaunching reopens where the user left off rather than on a blank canvas.
 *
 * A sibling of [SessionStore] rather than a mode of it, for the same reason the codecs are
 * siblings: the two document models share no shape, and the restore path has to know which editor
 * to hand its result to. The view is not stored here at all, because a canvas carries its own last
 * view inside the document.
 *
 * Best effort throughout: a missing or corrupt session restores nothing rather than failing to
 * start.
 */
class CanvasSessionStore(private val dir: File, private val codec: CanvasCodec, private val imageDir: File) {

    private val docFile = File(dir, "canvas.xcanvas")
    private val meta = JsonStore(File(dir, "canvas-session.json"))

    /** Whether a canvas session exists, so the host knows which editor to restore into. */
    fun exists(): Boolean = docFile.exists()

    fun save(document: InfiniteDocument, writeDocument: Boolean) {
        runCatching {
            dir.mkdirs()
            if (writeDocument || !docFile.exists()) {
                val tmp = File(dir, "canvas.xcanvas.tmp")
                FileOutputStream(tmp).use { codec.write(document, it) }
                if (!tmp.renameTo(docFile)) {
                    docFile.delete()
                    tmp.renameTo(docFile)
                }
            }
            val json = JSONObject()
            document.path?.let { json.put("path", it) }
            document.displayName?.let { json.put("display_name", it) }
            json.put("dirty", document.dirty)
            meta.write(json)
        }
    }

    /** The stored canvas, or null when there is none or it will not read. */
    fun load(): InfiniteDocument? = runCatching {
        if (!docFile.exists()) return null
        val document = FileInputStream(docFile).use { codec.read(it, imageDir) }
        val json = meta.read()
        document.path = json.optString("path", "").ifEmpty { null }
        document.displayName = json.optString("display_name", "").ifEmpty { null }
        document.dirty = json.optBoolean("dirty", false)
        document
    }.getOrNull()

    fun clear() {
        runCatching {
            docFile.delete()
            meta.write(JSONObject())
        }
    }
}
