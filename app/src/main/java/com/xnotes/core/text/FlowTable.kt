package com.xnotes.core.text

import com.xnotes.core.model.Rgba

/** Which grid lines a table draws. [id] is the stable serialized token. */
enum class TableBorders(val id: String) {
    ALL("all"),
    OUTER("outer"),
    HORIZONTAL("horizontal"),
    NONE("none");

    companion object {
        fun fromId(id: String?): TableBorders = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * A table's look as a value. Lengths are points; a null colour follows the
 * theme ([lineColor] the faded text colour, [tint] the accent). [tint] fills
 * the header row, and fainter every other body row when [banded].
 */
data class TableStyle(
    val paddingPt: Double = DEFAULT_PADDING_PT,
    val lineColor: Rgba? = null,
    val lineWidthPt: Double = DEFAULT_LINE_PT,
    val borders: TableBorders = TableBorders.ALL,
    val headerRow: Boolean = false,
    val banded: Boolean = false,
    val tint: Rgba? = null,
) {
    /** The same style with its lengths clamped to the supported ranges. */
    fun clamped(): TableStyle = copy(
        paddingPt = paddingPt.coerceIn(0.0, MAX_PADDING_PT),
        lineWidthPt = lineWidthPt.coerceIn(MIN_LINE_PT, MAX_LINE_PT),
    )

    companion object {
        const val DEFAULT_PADDING_PT = 4.0
        const val DEFAULT_LINE_PT = 0.75
        const val MAX_PADDING_PT = 24.0
        const val MIN_LINE_PT = 0.25
        const val MAX_LINE_PT = 6.0

        /** Content px per point: the scale flow fonts are measured at. */
        const val PX_PER_PT = 150.0 / 72.0
    }
}

/** What the insert dialog starts from and "Default for new tables" saves: grid size plus look. */
data class TableDefaults(
    val rows: Int = DEFAULT_ROWS,
    val cols: Int = DEFAULT_COLS,
    val style: TableStyle = TableStyle(),
) {
    val isFactory: Boolean get() = this == TableDefaults()

    companion object {
        const val DEFAULT_ROWS = 3
        const val DEFAULT_COLS = 3
        const val MAX_ROWS = 60
        const val MAX_COLS = 12
    }
}

/**
 * A table in the flow. Its cell text is ordinary [Paragraph]s in the flow's flat
 * list tagged with this table: each cell opens on a [Paragraph.cellStart]
 * paragraph and cells run in row-major order, so a paragraph's grid position is
 * derived ([CellIndex]), never stored, and the caret, IME mirror and undo work on
 * cell text unchanged. The table itself holds only the grid geometry and look.
 * Mutable and identity-compared; [com.xnotes.core.history.FlowTableEdit] records changes.
 */
class FlowTable(
    /** Column widths as fractions of the table width (summing to 1). */
    var widths: List<Double>,
    /** Per-row minimum heights in points (0 = as tall as the text). */
    var minHeights: List<Double> = emptyList(),
    var style: TableStyle = TableStyle(),
    /** The table's own width, as a fraction of the text column it sits in. */
    var width: Double = FULL_WIDTH,
) {
    val cols: Int get() = widths.size

    fun minHeightPt(row: Int): Double = minHeights.getOrElse(row) { 0.0 }

    fun snapshot(): TableSnapshot = TableSnapshot(widths, minHeights, style, width)

    fun copy(): FlowTable = FlowTable(widths, minHeights, style, width)

    companion object {
        /** A column never narrows below this fraction of the table. */
        const val MIN_FRACTION = 0.02

        /** A table spanning the whole text column. */
        const val FULL_WIDTH = 1.0

        /** A table never narrows below this fraction of the text column. */
        const val MIN_WIDTH = 0.1

        /** [width] clamped to the supported range; anything unreadable is the full width. */
        fun clampWidth(width: Double): Double =
            if (width.isFinite()) width.coerceIn(MIN_WIDTH, FULL_WIDTH) else FULL_WIDTH

        /**
         * [width] after the table's right edge is dragged [dx] px from a table
         * [grabbed] px wide. Both are measured at the grab, since the drag moves
         * the very width it would otherwise divide by; that way the edge travels
         * with the finger however far the drag has already gone.
         */
        fun widthAfterDrag(width: Double, grabbed: Double, dx: Double): Double {
            val was = clampWidth(width)
            return if (grabbed > 0.0) clampWidth(was * (1.0 + dx / grabbed)) else was
        }

        fun even(cols: Int): List<Double> = List(cols) { 1.0 / cols }

        /** [widths] scaled to sum to 1 with none below [MIN_FRACTION]; valid input comes back as is. */
        fun normalized(widths: List<Double>): List<Double> {
            if (widths.isEmpty()) return widths
            val floor = minOf(MIN_FRACTION, 1.0 / widths.size)
            if (widths.all { it.isFinite() && it >= floor - EPS } && kotlin.math.abs(widths.sum() - 1.0) < EPS) {
                return widths
            }
            var w = widths.map { if (it.isFinite() && it > 0.0) it else 0.0 }
            val total = w.sum()
            w = if (total <= 0.0) even(widths.size) else w.map { it / total }
            repeat(widths.size) {
                val low = w.map { it < floor }
                if (low.none { it }) return w
                val spare = 1.0 - floor * low.count { it }
                val rest = w.filterIndexed { i, _ -> !low[i] }.sum()
                w = w.mapIndexed { i, v -> if (low[i]) floor else v * spare / rest }
            }
            return w
        }

        private const val EPS = 1e-9
    }
}

/** A table's geometry and look at one moment (the undo state of [FlowTable]). */
data class TableSnapshot(
    val widths: List<Double>,
    val minHeights: List<Double>,
    val style: TableStyle,
    val width: Double = FlowTable.FULL_WIDTH,
) {
    fun applyTo(table: FlowTable) {
        table.widths = widths
        table.minHeights = minHeights
        table.style = style
        table.width = width
    }
}

/** One table's paragraph block [first, last] in the flow, its cells in row-major order. */
class TableBlock(
    val table: FlowTable,
    val ordinal: Int,
    val first: Int,
    val last: Int,
    private val cellFirst: IntArray,
    private val cellLast: IntArray,
) {
    val cols: Int = table.cols.coerceAtLeast(1)
    val cellCount: Int get() = cellFirst.size
    val rows: Int get() = (cellCount + cols - 1) / cols

    operator fun contains(para: Int): Boolean = para in first..last

    fun hasCell(row: Int, col: Int): Boolean =
        row >= 0 && col in 0 until cols && row * cols + col < cellCount

    fun cellFirstPara(row: Int, col: Int): Int = cellFirst[row * cols + col]

    fun cellLastPara(row: Int, col: Int): Int = cellLast[row * cols + col]

    fun cellFirstPara(cell: Int): Int = cellFirst[cell]

    fun cellLastPara(cell: Int): Int = cellLast[cell]

    fun rowFirstPara(row: Int): Int = cellFirst[row * cols]

    fun rowLastPara(row: Int): Int = cellLast[minOf(row * cols + cols, cellCount) - 1]

    /** Grid of the block's paragraphs: rows of cells of paragraphs, read from [paragraphs]. */
    fun grid(paragraphs: List<Paragraph>): MutableList<MutableList<MutableList<Paragraph>>> =
        MutableList(rows) { r ->
            MutableList(cols) { c ->
                if (hasCell(r, c)) {
                    paragraphs.subList(cellFirstPara(r, c), cellLastPara(r, c) + 1).toMutableList()
                } else {
                    mutableListOf(Paragraph(table = table, cellStart = true))
                }
            }
        }
}

/** A covered stretch [from, to) of paragraph [para]. */
data class ParaSpan(val para: Int, val from: Int, val to: Int)

/** How a selection reads once tables are involved ([CellIndex.shapeOf]). */
sealed class SelShape {
    /** Ordinary text: inside one cell, or body text with no table in between. */
    object Text : SelShape()

    /** A rectangle of cells inside one table. */
    class Cells(val block: TableBlock, val rows: IntRange, val cols: IntRange) : SelShape()

    /** Body text and table rows together; a table it enters counts in whole rows. */
    object Mixed : SelShape()
}

/**
 * Where each flow paragraph sits relative to tables: body text, or a cell of a
 * table block. Derived in one pass from the [Paragraph.table] tags and
 * [Paragraph.cellStart] flags and immutable afterwards, so a layout frame can
 * keep the one it was built from.
 */
class CellIndex(paragraphs: List<Paragraph>) {
    val tables: List<TableBlock>
    private val tableOf = IntArray(paragraphs.size) { -1 }
    private val cellOf = IntArray(paragraphs.size) { -1 }

    init {
        val blocks = mutableListOf<TableBlock>()
        var i = 0
        while (i < paragraphs.size) {
            val t = paragraphs[i].table
            if (t == null) {
                i++
                continue
            }
            val start = i
            val firsts = mutableListOf<Int>()
            val lasts = mutableListOf<Int>()
            while (i < paragraphs.size && paragraphs[i].table === t) {
                if (i == start || paragraphs[i].cellStart) {
                    if (firsts.isNotEmpty()) lasts.add(i - 1)
                    firsts.add(i)
                }
                tableOf[i] = blocks.size
                cellOf[i] = firsts.size - 1
                i++
            }
            lasts.add(i - 1)
            blocks.add(TableBlock(t, blocks.size, start, i - 1, firsts.toIntArray(), lasts.toIntArray()))
        }
        tables = blocks
    }

    val isEmpty: Boolean get() = tables.isEmpty()

    fun inTable(para: Int): Boolean = tableOf.getOrElse(para) { -1 } >= 0

    fun blockAt(para: Int): TableBlock? = tableOf.getOrElse(para) { -1 }.takeIf { it >= 0 }?.let { tables[it] }

    fun blockOf(table: FlowTable): TableBlock? = tables.firstOrNull { it.table === table }

    /** Row-major cell number of [para] within its table, or -1 in body text. */
    fun cellAt(para: Int): Int = cellOf.getOrElse(para) { -1 }

    fun rowOf(para: Int): Int {
        val b = blockAt(para) ?: return -1
        return cellOf[para] / b.cols
    }

    fun colOf(para: Int): Int {
        val b = blockAt(para) ?: return -1
        return cellOf[para] % b.cols
    }

    /** True when paragraphs [a] and [b] share a cell, or are both body text. */
    fun sameContainer(a: Int, b: Int): Boolean =
        tableOf.getOrElse(a) { -1 } == tableOf.getOrElse(b) { -1 } &&
            cellOf.getOrElse(a) { -1 } == cellOf.getOrElse(b) { -1 }

    fun shapeOf(range: FlowRange): SelShape {
        val r = range.normalized()
        val s = r.start.para
        val e = r.end.para
        if (s !in tableOf.indices || e !in tableOf.indices) return SelShape.Text
        val ts = tableOf[s]
        val te = tableOf[e]
        if (ts >= 0 && ts == te) {
            if (cellOf[s] == cellOf[e]) return SelShape.Text
            val rs = rowOf(s)
            val re = rowOf(e)
            val cs = colOf(s)
            val ce = colOf(e)
            return SelShape.Cells(tables[ts], minOf(rs, re)..maxOf(rs, re), minOf(cs, ce)..maxOf(cs, ce))
        }
        if (ts < 0 && te < 0 && tables.none { it.first in s..e }) return SelShape.Text
        return SelShape.Mixed
    }

    /** The rows of [block] a [SelShape.Mixed] selection over [range] covers. */
    fun mixedRows(block: TableBlock, range: FlowRange): IntRange {
        val r = range.normalized()
        val from = if (r.start.para in block) rowOf(r.start.para) else 0
        val to = if (r.end.para in block) rowOf(r.end.para) else block.rows - 1
        return from..to
    }

    /** Every paragraph stretch [range] covers under its [shapeOf] reading, in flow order. */
    fun spans(paragraphs: List<Paragraph>, range: FlowRange): List<ParaSpan> {
        val r = range.normalized()
        val out = mutableListOf<ParaSpan>()
        if (paragraphs.isEmpty()) return out
        val s = r.start.para.coerceIn(0, paragraphs.size - 1)
        val e = r.end.para.coerceIn(0, paragraphs.size - 1)
        fun textSpan(p: Int) {
            val len = paragraphs[p].length
            val from = if (p == s) r.start.offset.coerceIn(0, len) else 0
            val to = if (p == e) r.end.offset.coerceIn(0, len) else len
            if (to >= from) out.add(ParaSpan(p, from, to))
        }
        fun whole(p: Int) = out.add(ParaSpan(p, 0, paragraphs[p].length))
        when (val shape = shapeOf(FlowRange(FlowPos(s, r.start.offset), FlowPos(e, r.end.offset)))) {
            SelShape.Text -> for (p in s..e) textSpan(p)
            is SelShape.Cells -> for (row in shape.rows) {
                for (col in shape.cols) {
                    if (!shape.block.hasCell(row, col)) continue
                    for (p in shape.block.cellFirstPara(row, col)..shape.block.cellLastPara(row, col)) whole(p)
                }
            }
            SelShape.Mixed -> {
                var p = s
                while (p <= e) {
                    val b = blockAt(p)
                    if (b == null) {
                        textSpan(p)
                        p++
                        continue
                    }
                    val rows = mixedRows(b, r)
                    for (q in b.rowFirstPara(rows.first)..b.rowLastPara(rows.last)) whole(q)
                    p = b.last + 1
                }
            }
        }
        return out
    }

    /** [range]'s text for the clipboard: cells tab-separated, each table row on its own line. */
    fun textOf(paragraphs: List<Paragraph>, range: FlowRange): String {
        val sb = StringBuilder()
        var lastKey: Long? = null
        var lastCell = -1
        for (span in spans(paragraphs, range)) {
            val text = paragraphs[span.para].plainText().substring(span.from, span.to)
            val b = blockAt(span.para)
            if (b == null) {
                if (lastKey != null || sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
                lastKey = null
                continue
            }
            val key = (b.ordinal.toLong() shl 32) or rowOf(span.para).toLong()
            val cell = cellAt(span.para)
            when {
                key != lastKey -> {
                    if (sb.isNotEmpty() || lastKey != null) sb.append('\n')
                }
                cell != lastCell -> sb.append('\t')
                else -> sb.append(' ')
            }
            sb.append(text)
            lastKey = key
            lastCell = cell
        }
        return sb.toString()
    }
}

/**
 * [paras] about to sit between [before] and [after] (null = the flow's edge),
 * padded with empty body paragraphs wherever a table would touch the edge or
 * another table.
 */
fun withTableSeparators(paras: List<Paragraph>, before: Paragraph?, after: Paragraph?): MutableList<Paragraph> {
    val out = paras.toMutableList()
    if (out.none { it.table != null }) return out
    var i = 0
    while (i < out.size - 1) {
        val a = out[i].table
        val b = out[i + 1].table
        if (a != null && b != null && a !== b) out.add(i + 1, Paragraph())
        i++
    }
    if (out.first().table != null && (before == null || before.table != null)) out.add(0, Paragraph())
    if (out.last().table != null && (after == null || after.table != null)) out.add(Paragraph())
    return out
}

/**
 * Repair table invariants in place (after loading or pasting; not undoable):
 * a block opens on a cell start and fills whole rows, has one width per column
 * and one min height per row, and sits between body paragraphs; cell paragraphs
 * carry no list, code or indent. A table reused by a later separate block gets
 * its own copy. Returns true when anything changed.
 */
fun normalizeTables(flow: TextFlow): Boolean {
    val src = flow.paragraphs
    if (src.none { it.table != null }) return false
    val out = ArrayList<Paragraph>(src.size + 4)
    val seen = java.util.IdentityHashMap<FlowTable, Boolean>()
    var changed = false
    var i = 0
    while (i < src.size) {
        val p = src[i]
        val t0 = p.table
        if (t0 == null) {
            out.add(p)
            i++
            continue
        }
        var j = i
        while (j < src.size && src[j].table === t0) j++
        val table = if (seen.containsKey(t0)) t0.copy().also { changed = true } else t0
        seen[table] = true
        if (table.cols == 0) {
            table.widths = listOf(1.0)
            changed = true
        }
        val widths = FlowTable.normalized(table.widths)
        if (widths != table.widths) {
            table.widths = widths
            changed = true
        }
        val width = FlowTable.clampWidth(table.width)
        if (width != table.width) {
            table.width = width
            changed = true
        }
        if (out.isEmpty() || out.last().table != null) {
            out.add(Paragraph())
            changed = true
        }
        var cells = 0
        for (k in i until j) {
            val c = src[k]
            if (c.table !== table) {
                c.table = table
                changed = true
            }
            if (k == i && !c.cellStart) {
                c.cellStart = true
                changed = true
            }
            if (c.cellStart) cells++
            if (c.list != ListKind.NONE || c.codeLang != null || c.indent != 0 || c.checked) {
                c.list = ListKind.NONE
                c.codeLang = null
                c.indent = 0
                c.checked = false
                c.touch()
                changed = true
            }
            out.add(c)
        }
        while (cells % table.cols != 0) {
            out.add(Paragraph(table = table, cellStart = true))
            cells++
            changed = true
        }
        val rows = cells / table.cols
        if (table.minHeights.size != rows) {
            table.minHeights = List(rows) { table.minHeights.getOrElse(it) { 0.0 } }
            changed = true
        }
        if (j >= src.size || src[j].table != null) {
            out.add(Paragraph())
            changed = true
        }
        i = j
    }
    if (changed) {
        src.clear()
        src.addAll(out)
        flow.touch()
    }
    return changed
}
