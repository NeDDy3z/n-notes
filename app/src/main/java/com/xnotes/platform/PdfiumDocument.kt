package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Why a PDF did not open, from PDFium's FPDF_ERR_* codes. */
enum class PdfOpenError { FILE, FORMAT, PASSWORD, SECURITY, UNKNOWN }

/**
 * A PDF opened with PDFium. The native document lives on [PdfiumThread]: opening is queued
 * there and returns at once, and the calls that need it wait, so keep them off the main thread.
 */
class PdfiumDocument private constructor(
    /** The PDF read, owned by the caller: [close] never deletes it. */
    val file: File,
    private val pdfium: PdfiumThread,
) : AutoCloseable {
    private class Opened(val pageCount: Int, val error: PdfOpenError?)

    /** Cancelled by [close], which drops this document's queued jobs. */
    private val lifetime = CancelToken()
    private val closed = AtomicBoolean(false)

    /** The native document; read and written on the PDFium thread only. */
    private var handle = 0L

    private val opening = pdfium.submit(PdfPriority.INTERACTIVE, lifetime) { openNative() }

    /** The page count, 0 when the PDF did not open. Waits for the open. */
    val pageCount: Int get() = opened().pageCount

    /** Why the PDF did not open, null when it did. Waits for the open. */
    val error: PdfOpenError? get() = opened().error

    private fun opened(): Opened = opening.await() ?: NOT_OPENED

    private fun openNative(): Opened {
        if (!PdfiumNative.loaded) return NOT_OPENED
        val t0 = System.nanoTime()
        val out = IntArray(2)
        val h = PdfiumNative.nativeOpen(file.path, out)
        if (h == 0L) return Opened(0, errorOf(out[0]))
        handle = h
        openCount.incrementAndGet()
        lastOpenMs = (System.nanoTime() - t0) / 1_000_000
        return Opened(out[1], null)
    }

    /**
     * Width and height in points of [count] pages from [from], as displayed (/Rotate applied),
     * 0 for a page that does not load; null when not open. Every page by default.
     */
    fun pageSizes(
        from: Int = 0, count: Int = Int.MAX_VALUE, priority: PdfPriority = PdfPriority.INTERACTIVE,
    ): FloatArray? = withHandle(priority) { PdfiumNative.nativePageSizes(it, from, count) }

    /**
     * The boxes of the images on page [index], those inside form XObjects included: left, top,
     * right, bottom in points as displayed (top-left origin, /Rotate applied); null when not open.
     */
    fun imageRects(index: Int, priority: PdfPriority): FloatArray? =
        withHandle(priority) { PdfiumNative.nativeImageRects(it, index) }

    /**
     * The link areas on page [index], one per line of a link that spans several, with where each
     * goes: another page of this PDF, or a URI. Null when not open.
     */
    fun links(index: Int, priority: PdfPriority): List<PdfLink>? = withHandle(priority) { handle ->
        val parts = PdfiumNative.nativeLinks(handle, index) ?: return@withHandle null
        val boxes = parts[0] as FloatArray
        val dests = parts[1] as IntArray
        val uris = parts[2] as Array<*>
        List(dests.size) { i ->
            val rect = RectF(boxes[4 * i], boxes[4 * i + 1], boxes[4 * i + 2], boxes[4 * i + 3])
            val uri = (uris[i] as ByteArray?)?.toString(Charsets.UTF_8)
            PdfLink(rect, uri, dests[i].takeIf { it >= 0 && uri == null })
        }
    }

    /**
     * The document outline in reading order, at most [maxEntries] bookmarks, those without a title
     * dropped (their children stay); null when not open. A bookmark chain that loops is cut. Read in
     * short slices, one job each, so a big outline never holds up a page render for long.
     */
    @Synchronized
    fun outline(maxEntries: Int, priority: PdfPriority): List<PdfOutlineEntry>? {
        val out = ArrayList<PdfOutlineEntry>()
        var restart = true
        while (true) {
            val parts = withHandle(priority) { PdfiumNative.nativeOutline(it, maxEntries, SLICE_MS, restart) }
                ?: return null
            restart = false
            val titles = parts[0] as Array<*>
            if (titles.isEmpty()) return out
            val pages = parts[1] as IntArray
            val levels = parts[2] as IntArray
            for (i in titles.indices) {
                val title = (titles[i] as String).trim()
                if (title.isNotEmpty()) out += PdfOutlineEntry(title, pages[i], levels[i])
            }
        }
    }

    /**
     * Renders the part of page [index] at ([left], [top]) of a [fullW] x [fullH] raster of the
     * whole page into [bitmap] (ARGB_8888), on white. False when cancelled, failed or not open.
     */
    fun render(
        index: Int, bitmap: Bitmap, fullW: Int, fullH: Int, left: Int, top: Int,
        priority: PdfPriority, token: CancelToken? = null,
    ): Boolean = withHandle(priority, token) {
        PdfiumNative.nativeRender(it, index, bitmap, fullW, fullH, left, top, lifetime, token)
    } ?: false

    /** Runs [body] with the native document on the PDFium thread; null when it is not open. */
    private fun <T> withHandle(priority: PdfPriority, token: CancelToken? = null, body: (Long) -> T): T? {
        val tokens = if (token == null) arrayOf(lifetime) else arrayOf(lifetime, token)
        return pdfium.call(priority, *tokens) { if (handle == 0L) null else body(handle) }
    }

    /** Frees the native document once its running job, if any, ends. Never blocks. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel()
        // Behind what is on screen: freeing a big book takes tens of ms.
        pdfium.submit(PdfPriority.THUMBNAIL) {
            if (handle != 0L) {
                PdfiumNative.nativeClose(handle)
                handle = 0L
                openCount.decrementAndGet()
            }
        }
    }

    companion object {
        /** How long one job of a sliced read may run, so renders queued meanwhile get their turn. */
        private const val SLICE_MS = 8

        private val NOT_OPENED = Opened(0, PdfOpenError.UNKNOWN)
        private val openCount = AtomicInteger()
        @Volatile private var lastOpenMs = -1L

        /** Starts opening [file] on the shared PDFium thread. */
        fun open(file: File): PdfiumDocument = PdfiumDocument(file, PdfiumThread.shared)

        /** The revision, the open documents and the last open's time, for the debug HUD. */
        val hud: String
            get() = "%.10s  docs %d  open %s".format(
                PdfiumNative.revision, openCount.get(), if (lastOpenMs < 0) "-" else "$lastOpenMs ms",
            )

        private fun errorOf(code: Int): PdfOpenError = when (code) {
            2 -> PdfOpenError.FILE
            3 -> PdfOpenError.FORMAT
            4 -> PdfOpenError.PASSWORD
            5 -> PdfOpenError.SECURITY
            else -> PdfOpenError.UNKNOWN
        }
    }
}
