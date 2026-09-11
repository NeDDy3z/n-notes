package com.xnotes.core.text

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec

/** Page dimensions handed to layout (content px at the document dpi). */
data class PageBox(val width: Double, val height: Double)

/** A drawable text fragment: [text] starts at page-local [x] on the line baseline. */
class Seg(val text: String, val x: Double, val font: FontSpec, val style: CharStyle)

/** A decorated span of one line (underline/strike/highlight/inline-code chip), page-local x0..x1. */
class Deco(val x0: Double, val x1: Double, val font: FontSpec, val style: CharStyle)

/**
 * The bullet/number/checkbox marker of a list paragraph's first line; [rect] is
 * the gutter box. Ordered lists carry their pre-measured label ([text] drawn at
 * [textX] on the line baseline) so painting needs no measurer.
 */
class Marker(
    val kind: ListKind,
    val checked: Boolean,
    val ordinal: Int,
    val rect: Rect,
    val text: String? = null,
    val textX: Double = 0.0,
    val font: FontSpec = FontSpec(TextFlow.DEFAULT_SIZE_PT),
)

/**
 * One laid-out line: chars [startChar, endChar) of paragraph [paraIndex], placed
 * at page-local coordinates. [xs] holds every char boundary x (justify already
 * distributed), so caret mapping and hit-testing are exact lookups.
 */
class PlacedLine(
    val paraIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val top: Double,
    val baseline: Double,
    val bottom: Double,
    val xs: DoubleArray,
    val segs: List<Seg>,
    val decos: List<Deco>,
    val marker: Marker?,
    val codeLine: Boolean,
    val codeLeft: Double,
    val codeRight: Double,
) {
    val height: Double get() = bottom - top

    /** Page-local x of the caret before character [offset] (paragraph-local), clamped. */
    fun caretX(offset: Int): Double = xs[(offset - startChar).coerceIn(0, xs.size - 1)]

    /** The paragraph-local offset whose boundary is nearest to page-local [x]. */
    fun offsetAt(x: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (k in xs.indices) {
            val d = kotlin.math.abs(x - xs[k])
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return startChar + best
    }
}

/** The flow laid onto one page: its content rect and the lines placed there (top to bottom). */
class PageFlow(val contentRect: Rect, val lines: List<PlacedLine>)

/** What a text-tool tap landed on. */
sealed class FlowHit {
    class Caret(val pos: FlowPos) : FlowHit()
    class Checkbox(val paraIndex: Int) : FlowHit()

    /** Below the end of the flow: the tap asks for empty-line fill. */
    object BeyondEnd : FlowHit()
}

/**
 * An immutable snapshot of the fully laid-out flow. Painters on any thread read
 * only this (never the live model); queries answer caret/hit geometry from it.
 * [extraPagesNeeded] is how many pages typing overflowed past the real ones.
 */
class FlowFrame(
    val pages: List<PageFlow>,
    val flowRev: Int,
    val extraPagesNeeded: Int,
    val defaultColor: Rgba,
    /** Code chip background from the active code theme; null = the painter's neutral grey. */
    val codeBg: Rgba? = null,
) {
    private val byPara = HashMap<Int, MutableList<Pair<Int, PlacedLine>>>()

    /** Every placed line in document order, page index attached. */
    val lines: List<Pair<Int, PlacedLine>>

    private val lineOrder = HashMap<PlacedLine, Int>()

    init {
        val all = mutableListOf<Pair<Int, PlacedLine>>()
        for ((pi, page) in pages.withIndex()) {
            for (line in page.lines) {
                byPara.getOrPut(line.paraIndex) { mutableListOf() }.add(pi to line)
                lineOrder[line] = all.size
                all.add(pi to line)
            }
        }
        lines = all
    }

    val isEmpty: Boolean get() = pages.all { it.lines.isEmpty() }

    fun pagesWithLines(): Set<Int> = pages.indices.filterTo(mutableSetOf()) { pages[it].lines.isNotEmpty() }

    /** The page-local extent of the flow on [pageIndex] (padded for chips/markers), or null. */
    fun pageFlowBounds(pageIndex: Int): Rect? {
        val page = pages.getOrNull(pageIndex) ?: return null
        val first = page.lines.firstOrNull() ?: return null
        val last = page.lines.last()
        val pad = FlowPainter.CODE_PAD
        return Rect(page.contentRect.left - pad, first.top, page.contentRect.w + 2 * pad, last.bottom - first.top)
    }

    /** (page index, bottom y) of the last placed line, or null when nothing is placed. */
    fun lastLineEnd(): Pair<Int, Double>? {
        for (pi in pages.indices.reversed()) {
            pages[pi].lines.lastOrNull()?.let { return pi to it.bottom }
        }
        return null
    }

    /** The placed line a caret at [pos] sits on (wrap boundaries resolve downward). */
    fun placedLineFor(pos: FlowPos): Pair<Int, PlacedLine>? {
        val paraLines = byPara[pos.para] ?: return null
        for (entry in paraLines) {
            if (pos.offset >= entry.second.startChar && pos.offset < entry.second.endChar) return entry
        }
        return paraLines.last()
    }

    /** Page index + page-local caret rect for [pos], or null when it is not laid out. */
    fun caretRect(pos: FlowPos): Pair<Int, Rect>? {
        val (pg, line) = placedLineFor(pos) ?: return null
        val offset = pos.offset.coerceAtMost(line.endChar)
        return pg to Rect(line.caretX(offset), line.top, CARET_WIDTH, line.height)
    }

    /** The position one visual line above/below [pos], keeping the caret x; null at the edges. */
    fun moveVertical(pos: FlowPos, dir: Int): FlowPos? {
        val (_, cur) = placedLineFor(pos) ?: return null
        val idx = lineOrder[cur] ?: return null
        val (_, target) = lines.getOrNull(idx + dir) ?: return null
        return FlowPos(target.paraIndex, target.offsetAt(cur.caretX(pos.offset)))
    }

    /** The start or end of [pos]'s visual line. */
    fun lineEdge(pos: FlowPos, start: Boolean): FlowPos? {
        val (_, line) = placedLineFor(pos) ?: return null
        return FlowPos(line.paraIndex, if (start) line.startChar else line.endChar)
    }

    /** Resolve a text-tool tap at page-local [local] on page [pageIndex]. */
    fun hitTest(pageIndex: Int, local: Pt): FlowHit {
        val end = lastLineEnd() ?: return FlowHit.BeyondEnd
        if (pageIndex > end.first || (pageIndex == end.first && local.y >= end.second)) {
            return FlowHit.BeyondEnd
        }
        val page = pages.getOrNull(pageIndex) ?: return FlowHit.BeyondEnd
        if (page.lines.isEmpty()) {
            // A degenerate mid-flow page with no lines: land at the next placed line.
            for (pi in (pageIndex + 1) until pages.size) {
                pages[pi].lines.firstOrNull()?.let {
                    return FlowHit.Caret(FlowPos(it.paraIndex, it.startChar))
                }
            }
            return FlowHit.BeyondEnd
        }
        for (line in page.lines) {
            val m = line.marker ?: continue
            if (m.kind == ListKind.CHECK && m.rect.outset(CHECKBOX_HIT_PAD).contains(local)) {
                return FlowHit.Checkbox(line.paraIndex)
            }
        }
        val line = page.lines.firstOrNull { local.y < it.bottom } ?: page.lines.last()
        return FlowHit.Caret(FlowPos(line.paraIndex, line.offsetAt(local.x)))
    }

    /** The hyperlink under a finger tap at page-local [local], or null. Bounded to the line's
     *  actual text extent so a tap in the empty margin past a link never opens it. */
    fun linkAt(pageIndex: Int, local: Pt): String? {
        val page = pages.getOrNull(pageIndex) ?: return null
        val line = page.lines.firstOrNull { local.y >= it.top && local.y < it.bottom } ?: return null
        val leftX = line.xs.firstOrNull() ?: return null
        val rightX = line.xs.lastOrNull() ?: return null
        if (local.x < leftX || local.x > rightX) return null
        for ((i, seg) in line.segs.withIndex()) {
            val segRight = line.segs.getOrNull(i + 1)?.x ?: rightX
            if (local.x >= seg.x && local.x <= segRight) return seg.style.link
        }
        return null
    }

    /** Page-local highlight rects (page index keyed) covering [range]. */
    fun selectionRects(range: FlowRange): List<Pair<Int, Rect>> {
        val r = range.normalized()
        if (r.collapsed) return emptyList()
        val out = mutableListOf<Pair<Int, Rect>>()
        for ((pi, page) in pages.withIndex()) {
            for (line in page.lines) {
                if (line.paraIndex < r.start.para || line.paraIndex > r.end.para) continue
                val from = if (line.paraIndex == r.start.para) maxOf(line.startChar, r.start.offset) else line.startChar
                val to = if (line.paraIndex == r.end.para) minOf(line.endChar, r.end.offset) else line.endChar
                if (to < from) continue
                val x0 = line.caretX(from)
                var w = line.caretX(to) - x0
                // Mark the paragraph break itself while the selection continues past it.
                if (to == line.endChar && line.paraIndex < r.end.para) w += NEWLINE_TAIL
                if (w > 0.0) out.add(pi to Rect(x0, line.top, w, line.height))
            }
        }
        return out
    }

    /**
     * How many empty lines of height [slotH] must be appended so the caret can
     * land on the tapped line at page-local [y] on [pageIndex] (walking through
     * the intermediate pages' content rects). Zero when the tap is not beyond
     * the flow end.
     */
    fun emptyLinesToReach(pageIndex: Int, y: Double, slotH: Double): Int {
        if (pages.isEmpty() || slotH <= 0.0 || pageIndex !in pages.indices) return 0
        val end = lastLineEnd()
        val endPage = end?.first ?: 0
        val endY = end?.second ?: pages[0].contentRect.top
        if (pageIndex < endPage || (pageIndex == endPage && y < endY)) return 0
        fun slotsIn(h: Double): Int = maxOf(1, kotlin.math.floor(h / slotH).toInt())
        if (pageIndex == endPage) {
            val want = kotlin.math.floor((y - endY) / slotH).toInt() + 1
            val cap = slotsIn(pages[endPage].contentRect.bottom - endY)
            return want.coerceAtMost(cap)
        }
        var count = kotlin.math.floor((pages[endPage].contentRect.bottom - endY) / slotH).toInt()
            .coerceAtLeast(0)
        for (p in (endPage + 1) until pageIndex) count += slotsIn(pages[p].contentRect.h)
        val rect = pages[pageIndex].contentRect
        val want = kotlin.math.floor((y - rect.top).coerceAtLeast(0.0) / slotH).toInt() + 1
        return count + want.coerceAtMost(slotsIn(rect.h))
    }

    companion object {
        val EMPTY = FlowFrame(emptyList(), -1, 0, TextFlow.DEFAULT_COLOR)

        const val CARET_WIDTH = 2.0
        const val NEWLINE_TAIL = 8.0
        const val CHECKBOX_HIT_PAD = 6.0
    }
}

/** The double-tap word range around [pos]: same-class char run (word/space/other). */
fun wordRangeAt(flow: TextFlow, pos: FlowPos): FlowRange {
    val para = flow.paragraphs.getOrNull(pos.para) ?: return FlowRange.caret(pos)
    val text = para.plainText()
    if (text.isEmpty()) return FlowRange.caret(FlowPos(pos.para, 0))
    val at = pos.offset.coerceIn(0, text.length - 1)
    fun cls(c: Char): Int = when {
        c.isLetterOrDigit() || c == '_' -> 0
        c == ' ' || c == '\t' -> 1
        else -> 2
    }
    val k = cls(text[at])
    var s = at
    var e = at + 1
    while (s > 0 && cls(text[s - 1]) == k) s--
    while (e < text.length && cls(text[e]) == k) e++
    return FlowRange(FlowPos(pos.para, s), FlowPos(pos.para, e))
}

/**
 * The next word boundary from [pos] in the given direction, for Ctrl+arrow movement
 * and Ctrl+Backspace/Delete. Works in flat plainText space so it crosses paragraphs:
 * skip separators then a same-class content run (letters/digits/_ vs punctuation).
 */
fun wordBoundary(flow: TextFlow, pos: FlowPos, forward: Boolean): FlowPos {
    val text = flow.plainText()
    val n = text.length
    var j = flow.globalOffset(pos).coerceIn(0, n)
    fun content(c: Char): Int = if (c.isLetterOrDigit() || c == '_') 0 else 1
    if (forward) {
        while (j < n && text[j].isWhitespace()) j++
        if (j < n) {
            val k = content(text[j])
            while (j < n && !text[j].isWhitespace() && content(text[j]) == k) j++
        }
    } else {
        while (j > 0 && text[j - 1].isWhitespace()) j--
        if (j > 0) {
            val k = content(text[j - 1])
            while (j > 0 && !text[j - 1].isWhitespace() && content(text[j - 1]) == k) j--
        }
    }
    return flow.posAtGlobal(j)
}
