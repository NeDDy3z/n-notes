package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A uniform hash grid over item paint bounds, so a frame submits only the geometry the viewport
 * can see. The canvas is unbounded, so only occupied cells exist: they are keyed by packed cell
 * coordinates in a hash map rather than laid out in an array. Items compare by identity, matching
 * the model, and each one remembers the cell span it was filed under so removal is O(span).
 *
 * An item wider than [MAX_SPAN_CELLS] cells on either axis is not binned at all. Filing a canvas
 * spanning image into thousands of cells costs more than testing it on every query, so those go in
 * an [oversized] list that every query scans. Real documents hold very few of them.
 *
 * Bounds are taken from [CanvasItem.paintBounds], not [CanvasItem.bounds], so a neon glow's
 * overflow is culled with the item that casts it rather than clipped at the viewport edge.
 */
class SpatialIndex(val cellSize: Double = DEFAULT_CELL_SIZE) {

    private val cells = HashMap<Long, MutableList<CanvasItem>>()

    /** Per-item cell span as `[x0, y0, x1, y1]` inclusive; absent for oversized and unfiled items. */
    private val spans = IdentityHashMap<CanvasItem, IntArray>()

    private val oversized = ArrayList<CanvasItem>()

    /** Number of indexed items, binned and oversized together. */
    val size: Int get() = spans.size + oversized.size

    fun isEmpty(): Boolean = size == 0

    fun clear() {
        cells.clear()
        spans.clear()
        oversized.clear()
    }

    /** File [item] under its current paint bounds. Re-filing an already-indexed item is a no-op. */
    fun insert(item: CanvasItem) {
        if (spans.containsKey(item) || containsOversized(item)) return
        val span = spanOf(item.paintBounds())
        if (span == null) {
            oversized.add(item)
            return
        }
        spans[item] = span
        forEachCell(span) { key -> cells.getOrPut(key) { ArrayList(4) }.add(item) }
    }

    fun remove(item: CanvasItem) {
        val span = spans.remove(item)
        if (span == null) {
            removeOversized(item)
            return
        }
        forEachCell(span) { key ->
            val bucket = cells[key] ?: return@forEachCell
            bucket.removeRef(item)
            if (bucket.isEmpty()) cells.remove(key)
        }
    }

    /** Re-file [item] after its geometry moved. Cheap when the span is unchanged. */
    fun update(item: CanvasItem) {
        val old = spans[item]
        val new = spanOf(item.paintBounds())
        if (old != null && new != null && old.contentEquals(new)) return
        remove(item)
        insert(item)
    }

    /**
     * Append every item whose paint bounds intersect [rect] to [out]. An item spanning several
     * cells is appended once per hit cell, so the caller must de-duplicate. [InfiniteDocument]
     * does that while sorting into z-order, which it has to walk anyway.
     */
    fun queryRaw(rect: Rect, out: MutableList<CanvasItem>) {
        for (item in oversized) if (item.paintBounds().intersects(rect)) out.add(item)
        val span = cellRange(rect)
        for (cy in span[1]..span[3]) {
            for (cx in span[0]..span[2]) {
                val bucket = cells[key(cx, cy)] ?: continue
                for (item in bucket) if (item.paintBounds().intersects(rect)) out.add(item)
            }
        }
    }

    /** Distinct items whose paint bounds intersect [rect], in no particular order. */
    fun query(rect: Rect): List<CanvasItem> {
        val raw = ArrayList<CanvasItem>()
        queryRaw(rect, raw)
        if (raw.size < 2) return raw
        val seen = IdentityHashMap<CanvasItem, Unit>(raw.size * 2)
        val out = ArrayList<CanvasItem>(raw.size)
        for (item in raw) if (seen.put(item, Unit) == null) out.add(item)
        return out
    }

    // --- cell math ---

    /** Inclusive cell span of [r], or null when it covers more than [MAX_SPAN_CELLS] on an axis. */
    private fun spanOf(r: Rect): IntArray? {
        if (!r.isFinite()) return null
        val s = cellRange(r)
        val w = s[2].toLong() - s[0] + 1
        val h = s[3].toLong() - s[1] + 1
        if (w > MAX_SPAN_CELLS || h > MAX_SPAN_CELLS) return null
        return s
    }

    /** Inclusive cell span of [r], clamped to the representable cell range. */
    private fun cellRange(r: Rect): IntArray = intArrayOf(
        cellIndex(r.left, floorMode = true),
        cellIndex(r.top, floorMode = true),
        cellIndex(r.right, floorMode = false),
        cellIndex(r.bottom, floorMode = false),
    )

    /**
     * Cell index of a content coordinate. The right/bottom edges use `ceil - 1` so a rect that
     * ends exactly on a cell boundary does not claim the next cell along.
     */
    private fun cellIndex(v: Double, floorMode: Boolean): Int {
        if (v.isNaN()) return 0
        val q = v / cellSize
        val raw = if (floorMode) floor(q) else ceil(q) - 1.0
        return raw.coerceIn(MIN_CELL.toDouble(), MAX_CELL.toDouble()).toInt()
    }

    private inline fun forEachCell(span: IntArray, body: (Long) -> Unit) {
        for (cy in span[1]..span[3]) for (cx in span[0]..span[2]) body(key(cx, cy))
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    private fun containsOversized(item: CanvasItem): Boolean = oversized.any { it === item }

    private fun removeOversized(item: CanvasItem) {
        oversized.removeRef(item)
    }

    companion object {
        /**
         * Cell edge in content pixels. Roughly a line of handwriting at the document dpi: small
         * enough that a screenful of a dense canvas touches tens of cells, large enough that a
         * single stroke rarely files into more than a handful.
         */
        const val DEFAULT_CELL_SIZE = 512.0

        /** Widest cell span an item may be binned across before it moves to the oversized list. */
        const val MAX_SPAN_CELLS = 64

        private const val MIN_CELL = Int.MIN_VALUE / 2
        private const val MAX_CELL = Int.MAX_VALUE / 2
    }
}

/** True when every edge is finite, so the rect can be turned into a cell span. */
internal fun Rect.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()

internal fun <T> MutableList<T>.removeRef(target: T): Boolean {
    val i = indexOfFirst { it === target }
    if (i < 0) return false
    removeAt(i)
    return true
}
