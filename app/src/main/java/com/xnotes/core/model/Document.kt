package com.xnotes.core.model

import com.xnotes.core.text.TextFlow
import com.xnotes.core.util.Paths

/** A user bookmark into a document (spec 02 §3). */
data class Bookmark(var page: Int, var label: String)

/**
 * An ordered collection of pages plus on-disk identity (spec 02 §3). New notes
 * open with one blank page at the user's default page size/orientation.
 */
class Document(
    val pages: MutableList<Page> = mutableListOf(),
    var dpi: Int = PageSize.DEFAULT_DPI,
    var path: String? = null,
    /** Real file name from the storage provider, when known (overrides [path]-derived title). */
    var displayName: String? = null,
    var dirty: Boolean = false,
    /**
     * Embedded source PDF (for PDF-imported notes), so the note stays self-contained. Held as a
     * **file** on disk, not bytes in RAM: the file is memory-mapped by the renderer and streamed
     * into/out of the `.xnote` bundle, so even a very large PDF never has to fit in the heap. The
     * file lives in a private cache dir owned by the platform layer (which manages its lifetime).
     */
    var pdfFile: java.io.File? = null,
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
    /** Document-wide ("all pages") style override; per-page [Page.style] layers on top. */
    var style: PageStyle = PageStyle(),
    /** The document-wide flowing rich text (empty until typed into; persisted only when non-empty). */
    val flow: TextFlow = TextFlow(),
) {
    /** Transient: set by the codec when legacy ink was compacted at load (debug overlay readout). */
    var compactedOnLoad = false

    /** Derived: the storage display name (or path) base name without extension, or "Untitled". */
    val title: String
        get() = displayName?.let { Paths.stem(it) }
            ?: path?.let { Paths.stem(it) }
            ?: "Untitled"

    val hasPdf: Boolean get() = pdfFile != null

    /**
     * Appends a blank page sized [width] × [height] and marks the document
     * dirty. The UI passes the current page's dimensions so a note stays uniform.
     */
    fun addPage(width: Double, height: Double, pdfPage: Int? = null): Page {
        val page = Page(width, height, pdfPage = pdfPage)
        pages.add(page)
        dirty = true
        return page
    }

    companion object {
        const val DEFAULT_NEW_PAGES = 1

        /** A blank document of [count] pages of the given size/orientation. */
        fun blank(
            count: Int = DEFAULT_NEW_PAGES,
            size: PageSize = PageSize.A4,
            orientation: Orientation = Orientation.PORTRAIT,
            dpi: Int = PageSize.DEFAULT_DPI,
        ): Document {
            val doc = Document(dpi = dpi)
            repeat(count) { doc.pages.add(Page.blank(size, orientation, dpi)) }
            return doc
        }
    }
}
