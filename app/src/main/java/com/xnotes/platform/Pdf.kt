package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.Tool
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Turns a loaded [PdfSource] into a paged note to annotate (spec 08 §5). */
object PdfImporter {
    fun import(source: PdfSource, dpi: Int = PageSize.DEFAULT_DPI): Document {
        val doc = Document(dpi = dpi)
        doc.pdfFile = source.file
        for (i in 0 until source.pageCount) {
            val (wPts, hPts) = source.pageSizePoints(i)
            val w = wPts / 72.0 * dpi
            val h = hPts / 72.0 * dpi
            doc.pages.add(Page(w, h, pdfPage = i))
        }
        if (doc.pages.isEmpty()) doc.pages.add(Page.blank(PageSize.A4, com.xnotes.core.model.Orientation.PORTRAIT, dpi))
        return doc
    }
}

/**
 * Flattens a document into a new PDF (spec 08 §6).
 *
 * The imported PDF background stays **vector** — its pages are copied straight into the output via
 * PdfBox, never rasterized — so a 4 MB source no longer balloons into a 100 MB export. Annotations
 * are drawn on top: plain ink, shapes and inserted images become real vector/image objects through
 * [PdfBoxRenderer]; only effect-heavy items (neon glow, the highlighter's multiply blend,
 * translucent ink) and text boxes are rasterized in place — cropped to their own box, drawn at the
 * right z-order, and (for the highlighter) composited with Multiply so they still tint what's below.
 *
 * Fallbacks keep it correct everywhere: a source page with a non-zero `/Rotate` is written as one
 * upright full-page raster (overlay coordinates for rotated pages aren't handled yet), and if PdfBox
 * cannot parse the source PDF at all we fall back to the framework rasterizer ([exportRasterized]).
 */
object PdfExporter {

    /**
     * How the exporter draws the flow-text layer: [paint] renders a page-local region
     * and [bounds] gives a page's flow extent (null = no flow there). Flow text exports
     * as raster, like TextItems, so [paint] only ever receives raster renderers.
     */
    class FlowExport(
        val paint: (Page, Renderer, Rect) -> Unit,
        val bounds: (Page) -> Rect?,
    ) {
        companion object {
            val NONE = FlowExport({ _, _, _ -> }, { null })
        }
    }

    private const val MAX_RASTER_DIM = 4096

    /** Supersample factor for rasterized effect/text items (×150 dpi content ⇒ ~300 dpi). */
    private const val RASTER_ITEM_SCALE = 2.0

    /** Cap on PdfBox's in-RAM scratch buffers during export; the rest spills to temp files so a large
     *  source PDF can't exhaust the heap. Small/medium exports stay fully in memory (fast). */
    private const val SCRATCH_MAIN_MEM_BYTES = 32L * 1024 * 1024

    /**
     * [paperColor] gives each page's background fill (the on-screen paper colour, e.g. dark-theme
     * `#161616`) — used for plain note pages; an imported PDF page keeps its own background instead.
     * [onProgress] reports `(pagesDone, totalPages)` (once as `(0, total)` first) and [isCancelled]
     * is polled per page so a long export can show a dialog and abort before [out] is written.
     */
    fun export(
        context: Context,
        doc: Document,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        paintRuling: (Page, Renderer) -> Unit = { _, _ -> },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        flow: FlowExport = FlowExport.NONE,
    ) {
        val file = doc.pdfFile
        // Cap PdfBox's in-RAM scratch to a few tens of MB and spill the rest to temp files in the
        // cache dir, so a large source PDF can't exhaust the heap while it's parsed/written.
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MAIN_MEM_BYTES).setTempDir(context.cacheDir)
        val srcDoc = if (file != null) loadSource(context, file, mem) else null
        // A PDF background we can't parse with PdfBox: keep the working framework rasterizer.
        if (file != null && srcDoc == null) {
            exportRasterized(doc, source, out, paperColor, paintRuling, onProgress, isCancelled, flow)
            return
        }
        try {
            exportVector(doc, srcDoc, source, out, paperColor, paintRuling, onProgress, isCancelled, mem, flow)
        } finally {
            srcDoc?.runCatching { close() }
        }
    }

    private fun loadSource(context: Context, file: File, mem: MemoryUsageSetting): PDDocument? = try {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file, mem) // file-backed + scratch-capped: never reads the whole PDF into RAM
    } catch (_: Throwable) {
        null
    }

    private fun exportVector(
        doc: Document,
        srcDoc: PDDocument?,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        paintRuling: (Page, Renderer) -> Unit,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        mem: MemoryUsageSetting,
        flow: FlowExport,
    ) {
        val s = 72.0 / doc.dpi
        val total = doc.pages.size
        onProgress(0, total)

        // Fast path: the note is exactly the imported PDF's pages in their original order (optionally
        // with blank pages appended). Annotate the source document in place and save *it* — its
        // already-compressed page/image streams are copied straight through, never decoded and
        // re-encoded, and no second copy of the document is built. This is the case for "import a PDF
        // and draw on it", so the common big-PDF export is both fast and low-memory.
        if (srcDoc != null && canAnnotateSourceInPlace(doc, srcDoc)) {
            val n = srcDoc.numberOfPages
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                if (index < n) {
                    // Flow text counts as content too: a typed-only page must still annotate.
                    if (page.items.isNotEmpty() || flow.bounds(page) != null) {
                        annotatePage(srcDoc, srcDoc.getPage(index), page, s, paintRuling, flow)
                    }
                } else {
                    vectorBlankPage(srcDoc, page, s, paperColor, paintRuling, flow) // a blank note page appended after the PDF
                }
                releaseInkGeometry(page)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            // Page work is near-instant on this path, so all the time is in writing the PDF — one
            // opaque PdfBox call. Report it as byte progress (output ≈ the source PDF's size) so the
            // dialog keeps moving instead of freezing at "done". total = -1 marks the writing phase.
            val est = doc.pdfFile?.length() ?: 0L
            if (est > 0) {
                onProgress(0, -1) // enter the writing phase at 0% right away (no spinner flash)
                srcDoc.save(ProgressOutputStream(out, est) { p -> onProgress(p, -1) })
            } else {
                srcDoc.save(out)
            }
            return
        }

        // Fallback: pages were reordered/deleted, or a source page is rotated, or it's a pure note —
        // rebuild a fresh document. Scratch is capped (see [mem]) so this can't exhaust the heap either.
        val outDoc = PDDocument(mem)
        try {
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val srcIdx = page.pdfPage
                val hasSource = srcIdx != null && srcDoc != null && srcIdx in 0 until srcDoc.numberOfPages
                when {
                    hasSource && srcDoc!!.getPage(srcIdx!!).rotation % 360 == 0 ->
                        vectorImportedPage(outDoc, srcDoc, srcIdx, page, s, paintRuling, flow)
                    hasSource ->
                        rasterFullPage(outDoc, page, source, paperColor, s, paintRuling, flow) // rotated source page
                    else ->
                        vectorBlankPage(outDoc, page, s, paperColor, paintRuling, flow)
                }
                releaseInkGeometry(page)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            outDoc.save(out)
        } finally {
            outDoc.runCatching { close() }
        }
    }

    /**
     * Drop the ribbon geometry painting [page] just built. An export is a single pass that never
     * revisits a page, so holding every page's ribbons to the end would leave the whole document's
     * worth resident (~30 bytes per sample) exactly while the encoder wants the heap. The canvas
     * rebuilds whatever it still needs on its next frame.
     */
    private fun releaseInkGeometry(page: Page) {
        for (item in page.items) if (item is Stroke) item.releaseGeometry()
    }

    /** True when the note's pages are the source's pages 0..N-1 in order (rotation-0), optionally
     *  followed by blank note pages — the shape that lets us annotate the source in place. */
    private fun canAnnotateSourceInPlace(doc: Document, srcDoc: PDDocument): Boolean {
        val n = srcDoc.numberOfPages
        if (doc.pages.size < n) return false // a source page was deleted -> rebuild
        for (i in 0 until n) {
            if (doc.pages[i].pdfPage != i) return false // reordered/duplicated/remapped -> rebuild
            if (srcDoc.getPage(i).rotation % 360 != 0) return false // rotated source page -> rebuild
        }
        for (i in n until doc.pages.size) {
            if (doc.pages[i].pdfPage != null) return false // a source page where a blank is expected -> rebuild
        }
        return true
    }

    /** Copy a (rotation-0) source page in as vector, then overlay its ruling + annotations. */
    private fun vectorImportedPage(outDoc: PDDocument, srcDoc: PDDocument, srcIdx: Int, page: Page, s: Double, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport) {
        annotatePage(outDoc, outDoc.importPage(srcDoc.getPage(srcIdx)), page, s, paintRuling, flow)
    }

    /** Append [page]'s ruling + annotations as a new content stream over an existing [pdfPage] of [doc]. */
    private fun annotatePage(doc: PDDocument, pdfPage: PDPage, page: Page, s: Double, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport) {
        val crop = pdfPage.cropBox
        val ox = crop.lowerLeftX.toDouble()
        val oy = (crop.lowerLeftY + crop.height).toDouble()
        PDPageContentStream(doc, pdfPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            paintItems(cs, doc, page, ox, oy, s, paintRuling, flow)
        }
    }

    /** A note page with no PDF background: blank page filled with the paper colour, then ruling + annotations. */
    private fun vectorBlankPage(outDoc: PDDocument, page: Page, s: Double, paperColor: (Page) -> Rgba, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport) {
        val wPts = (page.width * s).toFloat().coerceAtLeast(1f)
        val hPts = (page.height * s).toFloat().coerceAtLeast(1f)
        val pdfPage = PDPage(PDRectangle(wPts, hPts))
        outDoc.addPage(pdfPage)
        PDPageContentStream(outDoc, pdfPage).use { cs ->
            val paper = paperColor(page)
            cs.setNonStrokingColor(paper.r / 255f, paper.g / 255f, paper.b / 255f)
            cs.addRect(0f, 0f, wPts, hPts)
            cs.fill()
            paintItems(cs, outDoc, page, ox = 0.0, oy = hPts.toDouble(), s, paintRuling, flow)
        }
    }

    /** Draw a page's ruling (behind ink), the flow text (raster), then its items in z-order. */
    private fun paintItems(cs: PDPageContentStream, outDoc: PDDocument, page: Page, ox: Double, oy: Double, s: Double, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport) {
        val renderer = PdfBoxRenderer(cs, outDoc, ox, oy, s)
        paintRuling(page, renderer) // page ruling sits behind the ink
        rasterizeFlow(page, flow)?.let { raster ->
            renderer.drawItemBitmap(raster.bmp, raster.rect, multiply = false)
            raster.bmp.recycle()
        }
        for (item in page.items) {
            if (needsRaster(item)) {
                val raster = rasterizeItem(item, page) ?: continue
                renderer.drawItemBitmap(raster.bmp, raster.rect, raster.multiply)
                raster.bmp.recycle()
            } else {
                item.paint(renderer)
            }
        }
    }

    /**
     * A source page with a non-zero rotation: render it (rotation already applied by [PdfSource])
     * plus its items into one upright bitmap and embed that as a full-page JPEG on a rotation-0 page.
     * Still vector for everything else; only these rare pages stay rasterized.
     */
    private fun rasterFullPage(outDoc: PDDocument, page: Page, source: PdfSource?, paperColor: (Page) -> Rgba, s: Double, paintRuling: (Page, Renderer) -> Unit, flow: FlowExport) {
        val wPx = page.width.toInt().coerceIn(1, MAX_RASTER_DIM)
        val hPx = page.height.toInt().coerceIn(1, MAX_RASTER_DIM)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paperColor(page).toArgb())
        val srcIdx = page.pdfPage
        if (srcIdx != null && source != null) {
            val bg = source.renderPage(srcIdx, wPx, hPx)
            if (bg != null) {
                canvas.drawBitmap(bg.bitmap, null, RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                bg.recycle()
            }
        }
        val r = AndroidRenderer(canvas)
        paintRuling(page, r) // ruling behind ink
        flow.paint(page, r, Rect(0.0, 0.0, page.width, page.height))
        for (item in page.items) item.paint(r)

        val wPts = (page.width * s).toFloat().coerceAtLeast(1f)
        val hPts = (page.height * s).toFloat().coerceAtLeast(1f)
        val pdfPage = PDPage(PDRectangle(wPts, hPts))
        outDoc.addPage(pdfPage)
        PDPageContentStream(outDoc, pdfPage).use { cs ->
            val img = JPEGFactory.createFromImage(outDoc, bmp, 0.8f)
            cs.drawImage(img, 0f, 0f, wPts, hPts)
        }
        bmp.recycle()
    }

    /** Items whose look can't be reproduced as plain vector and so are rasterized in place. */
    private fun needsRaster(item: CanvasItem): Boolean = when (item) {
        is Stroke -> item.tool == Tool.HIGHLIGHTER ||
            (item.config.neon && item.tool != Tool.HIGHLIGHTER) ||
            item.renderColor.a < 255
        is ShapeItem -> item.neon ||
            item.strokeRgba.a < 255 ||
            (item.fillRgba?.let { it.a < 255 } ?: false)
        is TextItem -> true
        else -> false // ImageItem is embedded as an image XObject by the renderer's drawRaster
    }

    private class RasterItem(val bmp: Bitmap, val rect: Rect, val multiply: Boolean)

    /**
     * Wraps [out] and reports write progress as a 0..999 permille of [estTotal] bytes (throttled to
     * one call per permille step), so a long PdfBox `save` can drive a moving progress bar. Capped at
     * 999 so it never reads "100%" before the save actually returns. Does not own [out].
     */
    private class ProgressOutputStream(
        private val out: OutputStream,
        private val estTotal: Long,
        private val onPermille: (Int) -> Unit,
    ) : OutputStream() {
        private var written = 0L
        private var last = -1
        override fun write(b: Int) { out.write(b); written++; tick() }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len; tick() }
        override fun flush() { out.flush() }
        override fun close() { out.close() }
        private fun tick() {
            val p = ((written * 1000) / estTotal).toInt().coerceIn(0, 999)
            if (p != last) { last = p; onPermille(p) }
        }
    }

    /** Render the page's flow text, cropped to its extent, into a transparent bitmap (like an item). */
    private fun rasterizeFlow(page: Page, flow: FlowExport): RasterItem? {
        val fb = flow.bounds(page) ?: return null
        val left = fb.left.coerceAtLeast(0.0)
        val top = fb.top.coerceAtLeast(0.0)
        val right = fb.right.coerceAtMost(page.width)
        val bottom = fb.bottom.coerceAtMost(page.height)
        val cw = right - left
        val ch = bottom - top
        if (cw <= 0.0 || ch <= 0.0) return null
        val w = ceil(cw * RASTER_ITEM_SCALE).toInt().coerceIn(1, MAX_RASTER_DIM)
        val h = ceil(ch * RASTER_ITEM_SCALE).toInt().coerceIn(1, MAX_RASTER_DIM)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale((w / cw).toFloat(), (h / ch).toFloat())
        canvas.translate(-left.toFloat(), -top.toFloat())
        flow.paint(page, AndroidRenderer(canvas), Rect(left, top, cw, ch))
        return RasterItem(bmp, Rect(left, top, cw, ch), multiply = false)
    }

    /** Render a single item, cropped to its (page-clamped) paint bounds, into a transparent bitmap. */
    private fun rasterizeItem(item: CanvasItem, page: Page): RasterItem? {
        val pb = item.paintBounds()
        val left = pb.left.coerceAtLeast(0.0)
        val top = pb.top.coerceAtLeast(0.0)
        val right = pb.right.coerceAtMost(page.width)
        val bottom = pb.bottom.coerceAtMost(page.height)
        val cw = right - left
        val ch = bottom - top
        if (cw <= 0.0 || ch <= 0.0) return null
        val w = ceil(cw * RASTER_ITEM_SCALE).toInt().coerceIn(1, MAX_RASTER_DIM)
        val h = ceil(ch * RASTER_ITEM_SCALE).toInt().coerceIn(1, MAX_RASTER_DIM)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // starts fully transparent
        val canvas = Canvas(bmp)
        canvas.scale((w / cw).toFloat(), (h / ch).toFloat()) // content px → bitmap px
        canvas.translate(-left.toFloat(), -top.toFloat())
        item.paint(AndroidRenderer(canvas))
        val multiply = item is Stroke && item.tool == Tool.HIGHLIGHTER
        return RasterItem(bmp, Rect(left, top, cw, ch), multiply)
    }

    /**
     * Original framework-[PdfDocument] path: rasterizes each page (background + items) to a bitmap.
     * Kept only as a fallback for source PDFs PdfBox can't parse.
     */
    private fun exportRasterized(
        doc: Document,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        paintRuling: (Page, Renderer) -> Unit,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        flow: FlowExport,
    ) {
        val pdf = PdfDocument()
        val scale = (72.0 / doc.dpi).toFloat()
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val total = doc.pages.size
        try {
            onProgress(0, total)
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val wPts = (page.width / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val hPts = (page.height / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(wPts, hPts, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(paperColor(page).toArgb())
                canvas.scale(scale, scale)

                val src = page.pdfPage
                if (src != null && source != null) {
                    val bg = source.renderPage(src, page.width.toInt(), page.height.toInt())
                    if (bg != null) {
                        canvas.drawBitmap(bg.bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), bitmapPaint)
                        bg.recycle()
                    }
                }
                val renderer = AndroidRenderer(canvas)
                paintRuling(page, renderer) // ruling behind ink
                flow.paint(page, renderer, Rect(0.0, 0.0, page.width, page.height))
                for (item in page.items) item.paint(renderer)
                pdf.finishPage(pdfPage)
                releaseInkGeometry(page)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
