package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.max

/**
 * A grid table (spec: n-notes addition): an outer [rect] divided into columns and rows. Column
 * widths and row heights are stored as fractions that sum to 1, so the grid scales with the box and
 * individual columns/rows can be resized by moving an interior line. Only lines are drawn; cells
 * hold no content of their own (the user writes/draws over them like any paper ruling).
 */
class TableItem(
    var rect: Rect,
    colFractions: List<Double>,
    rowFractions: List<Double>,
    var strokeRgba: Rgba,
    var strokeWidth: Double = 2.0,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override var locked = false

    val colFractions: MutableList<Double> = colFractions.toMutableList()
    val rowFractions: MutableList<Double> = rowFractions.toMutableList()

    val cols: Int get() = colFractions.size
    val rows: Int get() = rowFractions.size

    /** Absolute x of the [i]-th vertical line (0 = left edge, [cols] = right edge). */
    fun colX(i: Int): Double {
        var f = 0.0
        for (k in 0 until i.coerceIn(0, cols)) f += colFractions[k]
        return rect.left + f * rect.w
    }

    /** Absolute y of the [j]-th horizontal line (0 = top edge, [rows] = bottom edge). */
    fun rowY(j: Int): Double {
        var f = 0.0
        for (k in 0 until j.coerceIn(0, rows)) f += rowFractions[k]
        return rect.top + f * rect.h
    }

    override fun paint(r: Renderer) {
        val pen = Pen(color = strokeRgba, width = strokeWidth, cosmetic = false)
        r.strokeRect(rect, pen)
        for (i in 1 until cols) r.strokePolyline(listOf(Pt(colX(i), rect.top), Pt(colX(i), rect.bottom)), pen)
        for (j in 1 until rows) r.strokePolyline(listOf(Pt(rect.left, rowY(j)), Pt(rect.right, rowY(j))), pen)
    }

    override fun bounds(): Rect = rect.outset(strokeWidth / 2.0 + 1.0)

    override fun translate(dx: Double, dy: Double) { rect = rect.translate(dx, dy) }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        val p = Pt(cx, cy)
        if (bounds().distanceTo(p) > radius) return false
        val tol = radius + strokeWidth / 2.0
        // Any of the four borders or interior lines within tolerance counts as a hit.
        for (i in 0..cols) if (Geometry.distancePointToSegment(p, Pt(colX(i), rect.top), Pt(colX(i), rect.bottom)) <= tol) return true
        for (j in 0..rows) if (Geometry.distancePointToSegment(p, Pt(rect.left, rowY(j)), Pt(rect.right, rowY(j))) <= tol) return true
        return false
    }

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) rect = handle.rect
    }

    override fun snapshotGeometry(): GeometrySnapshot =
        TableSnapshot(rect, colFractions.toList(), rowFractions.toList(), strokeWidth)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is TableSnapshot) return
        rect = snap.rect
        colFractions.clear(); colFractions.addAll(snap.cols)
        rowFractions.clear(); rowFractions.addAll(snap.rows)
        strokeWidth = snap.strokeWidth
    }

    /** Scale (and, for a rotation, re-fit upright) the box; fractions are relative, so they follow. */
    override fun applyTransform(t: Affine) {
        strokeWidth *= t.linearScale
        val corners = listOf(
            t.apply(Pt(rect.left, rect.top)), t.apply(Pt(rect.right, rect.top)),
            t.apply(Pt(rect.right, rect.bottom)), t.apply(Pt(rect.left, rect.bottom)),
        )
        rect = Rect.bounding(corners)
    }

    // --- structure edits ---

    /** Add a column of average width on the right, keeping the others' proportions. */
    fun addColumn() {
        val avg = 1.0 / (cols + 1)
        val k = cols.toDouble() / (cols + 1)
        for (i in colFractions.indices) colFractions[i] = colFractions[i] * k
        colFractions.add(avg)
    }

    /** Add a row of average height at the bottom, keeping the others' proportions. */
    fun addRow() {
        val avg = 1.0 / (rows + 1)
        val k = rows.toDouble() / (rows + 1)
        for (i in rowFractions.indices) rowFractions[i] = rowFractions[i] * k
        rowFractions.add(avg)
    }

    /** Remove the last column (keeps at least one), renormalizing the rest. */
    fun removeColumn() {
        if (cols <= 1) return
        colFractions.removeAt(colFractions.size - 1)
        renormalize(colFractions)
    }

    /** Remove the last row (keeps at least one), renormalizing the rest. */
    fun removeRow() {
        if (rows <= 1) return
        rowFractions.removeAt(rowFractions.size - 1)
        renormalize(rowFractions)
    }

    fun setColumns(n: Int) { val t = n.coerceIn(1, MAX_LINES); while (cols < t) addColumn(); while (cols > t) removeColumn() }
    fun setRows(n: Int) { val t = n.coerceIn(1, MAX_LINES); while (rows < t) addRow(); while (rows > t) removeRow() }

    /** Move interior vertical line [i] (1..cols-1) to absolute x, resizing its two neighbour columns. */
    fun moveColumnLine(i: Int, x: Double) {
        if (i < 1 || i >= cols) return
        val leftEdge = colX(i - 1)
        val rightEdge = colX(i + 1)
        val span = rightEdge - leftEdge
        if (span <= 0) return
        val minFrac = MIN_CELL / rect.w
        val newLeft = ((x - leftEdge) / rect.w).coerceIn(minFrac, (rightEdge - leftEdge) / rect.w - minFrac)
        val pairTotal = colFractions[i - 1] + colFractions[i]
        colFractions[i - 1] = newLeft
        colFractions[i] = pairTotal - newLeft
    }

    /** Move interior horizontal line [j] (1..rows-1) to absolute y, resizing its two neighbour rows. */
    fun moveRowLine(j: Int, y: Double) {
        if (j < 1 || j >= rows) return
        val topEdge = rowY(j - 1)
        val bottomEdge = rowY(j + 1)
        if (bottomEdge - topEdge <= 0) return
        val minFrac = MIN_CELL / rect.h
        val newTop = ((y - topEdge) / rect.h).coerceIn(minFrac, (bottomEdge - topEdge) / rect.h - minFrac)
        val pairTotal = rowFractions[j - 1] + rowFractions[j]
        rowFractions[j - 1] = newTop
        rowFractions[j] = pairTotal - newTop
    }

    /** The interior vertical line nearest [p] within [tol] (1..cols-1), or -1. */
    fun columnLineNear(p: Pt, tol: Double): Int {
        if (p.y < rect.top - tol || p.y > rect.bottom + tol) return -1
        for (i in 1 until cols) if (kotlin.math.abs(p.x - colX(i)) <= tol) return i
        return -1
    }

    /** The interior horizontal line nearest [p] within [tol] (1..rows-1), or -1. */
    fun rowLineNear(p: Pt, tol: Double): Int {
        if (p.x < rect.left - tol || p.x > rect.right + tol) return -1
        for (j in 1 until rows) if (kotlin.math.abs(p.y - rowY(j)) <= tol) return j
        return -1
    }

    companion object {
        const val KIND = "table"
        const val MIN_CELL = 12.0
        const val MAX_LINES = 64

        private fun renormalize(fr: MutableList<Double>) {
            val sum = fr.sum()
            if (sum <= 0) { val e = 1.0 / fr.size; for (i in fr.indices) fr[i] = e } else for (i in fr.indices) fr[i] = fr[i] / sum
        }

        /** A fresh table of [cols]×[rows] even cells filling [rect]. */
        fun create(rect: Rect, cols: Int, rows: Int, strokeRgba: Rgba, strokeWidth: Double = 2.0): TableItem {
            val c = cols.coerceIn(1, MAX_LINES)
            val r = rows.coerceIn(1, MAX_LINES)
            return TableItem(rect, List(c) { 1.0 / c }, List(r) { 1.0 / r }, strokeRgba, strokeWidth)
        }
    }
}

/** Snapshot of a table's geometry (box + line distribution + line width) for undo. */
private data class TableSnapshot(
    val rect: Rect,
    val cols: List<Double>,
    val rows: List<Double>,
    val strokeWidth: Double,
) : GeometrySnapshot
