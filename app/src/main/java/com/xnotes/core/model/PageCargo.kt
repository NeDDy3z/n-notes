package com.xnotes.core.model

import com.xnotes.core.audio.RecordingIds
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.TextMeasurer

/**
 * Pages lifted out of a note to go into another, carrying what they need there: each its own look,
 * the templates it is ruled with, its bookmarks and recordings. Typed text stays behind. Their PDF
 * pages are named by place in [pdfIndices], the source PDF's pages they show, which the caller
 * copies into a PDF of their own ([pdf]) so the note they came from may close.
 */
class PageCargo private constructor(
    private val pages: List<Page>,
    val dpi: Int,
    /** The source PDF's pages the cargo shows, in the order [pdf] holds them. */
    val pdfIndices: List<Int>,
    private val templates: Map<String, String>,
    private val templatePackages: Map<String, java.io.File>,
    /** Bookmarks on the cargo, their page an index into it. */
    val bookmarks: List<Bookmark>,
    /** The first page was its note's cover. */
    val cover: Boolean,
) {
    /** A PDF of just the [pdfIndices] pages, in that order; null until the caller makes one, or without PDF pages. */
    var pdf: java.io.File? = null

    val size: Int get() = pages.size

    /**
     * Fresh copies of the pages for [target]: sized to its dpi, their PDF pages counted from
     * [pdfBase] (null when the cargo's PDF did not join, which leaves them blank), and their
     * recordings numbered so they take over no ink already in [target]. The templates they are
     * ruled with join [target]'s, where it has none of that name. Returns the copies, in order.
     */
    fun unpack(target: Document, pdfBase: Int?, measurer: TextMeasurer): List<Page> {
        val k = target.dpi.toDouble() / dpi
        val copies = pages.map { src ->
            src.deepCopy(measurer).also { page ->
                page.pdfPage = src.pdfPage?.let { i -> pdfBase?.let { it + i } }
                if (page.pdfPage == null) page.pdfTurn = 0
                if (k != 1.0) rescale(page, k)
            }
        }
        RecordingIds.adopt(copies.flatMap { it.items }, target.pages.flatMap { it.items }, moves = true)
        target.templates = templates.filterKeys { it !in target.templates } + target.templates
        target.templatePackages = templatePackages.filterKeys { it !in target.templatePackages } + target.templatePackages
        return copies
    }

    /** The source PDF page each copy shows, in [pdfIndices] terms mapped back, for pasting into the note they came from. */
    fun sourcePdfPages(): List<Int?> = pages.map { p -> p.pdfPage?.let { pdfIndices.getOrNull(it) } }

    companion object {

        /**
         * The pages of [doc] at [indices], packed in note order. Each is a copy in its own look,
         * the note-wide style and margins written onto it. Ink tied to a recording that is not
         * among them loses the tie.
         */
        fun pack(doc: Document, indices: List<Int>, measurer: TextMeasurer): PageCargo {
            val picked = indices.filter { it in doc.pages.indices }.distinct().sorted()
            val pdfIndices = picked.mapNotNull { doc.pages[it].pdfPage }.distinct()
            val pdfAt = pdfIndices.withIndex().associate { it.value to it.index }
            val pages = picked.map { i ->
                val src = doc.pages[i]
                src.deepCopy(measurer).also { page ->
                    page.style = src.bakedStyle(doc)
                    page.margins = src.resolvedMargins(doc)
                    page.pdfPage = src.pdfPage?.let { pdfAt[it] }
                }
            }
            val rids = pages.flatMap { it.items }.filterIsInstance<AudioItem>().mapTo(HashSet()) { it.rid }
            for (page in pages) for (item in page.items) if (item.sync?.rid?.let { it !in rids } == true) item.sync = null
            val keys = pages.mapNotNullTo(HashSet()) { it.style.template }
            val bookmarks = doc.bookmarks.mapNotNull { b -> picked.indexOf(b.page).takeIf { it >= 0 }?.let { Bookmark(it, b.label) } }
            val cover = picked.firstOrNull() == 0 && doc.pages.firstOrNull()?.cover == true
            return PageCargo(
                pages, doc.dpi, pdfIndices,
                doc.templates.filterKeys { it in keys }, doc.templatePackages.filterKeys { it in keys },
                bookmarks, cover,
            )
        }

        /** Scale [page] and everything on it by [k], from a note of one dpi to another. */
        private fun rescale(page: Page, k: Double) {
            page.width *= k
            page.height *= k
            val t = Affine.scaleAbout(Pt.ZERO, k, k)
            for (item in page.items) item.applyTransform(t)
            page.style.spacing?.let { page.style = page.style.copy(spacing = it * k) }
        }
    }
}

/** [this] page's look, fixed into its own style, so it shows the same under another note's note-wide style. */
fun Page.bakedStyle(doc: Document): PageStyle {
    val template = resolvedTemplate(doc)
    return PageStyle(
        pageColor = style.pageColor ?: doc.style.pageColor,
        template = template,
        patternColor = style.patternColor ?: doc.style.patternColor?.takeIf { sharesNoteTemplate(doc, template) },
        spacing = style.spacing ?: doc.style.spacing?.takeIf { sharesNoteTemplate(doc, template) },
        accentColor = style.accentColor ?: doc.style.accentColor?.takeIf { sharesNoteTemplate(doc, template) },
        params = resolvedParams(doc).ifEmpty { null },
        colors = resolvedColors(doc).ifEmpty { null },
    )
}

/** True when the page shows the note-wide template, so the note-wide template settings reach it. */
private fun sharesNoteTemplate(doc: Document, template: String): Boolean =
    (doc.style.template ?: PageTemplates.NONE) == template
