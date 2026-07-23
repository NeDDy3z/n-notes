package com.xnotes.core.text

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextMeasurer

/** The concrete font a run resolves to: style overrides over the flow defaults; code is mono. */
fun resolveFont(flow: TextFlow, para: Paragraph, style: CharStyle): FontSpec = FontSpec(
    pointSize = style.sizePt ?: flow.defaultSizePt,
    face = if (para.codeLang != null || style.code) flow.monoFace else style.face ?: flow.defaultFace,
    bold = style.bold,
    italic = style.italic,
)

/**
 * One wrapped line of a paragraph: characters [startChar, endChar) of its plain
 * text. Trailing spaces hang (they are inside the span but excluded from
 * [width]); [spaceCount] is the stretchable interior spaces justify distributes
 * into; [hardBroken] lines (mid-word) never justify.
 */
class BrokenLine(
    val startChar: Int,
    val endChar: Int,
    val width: Double,
    val ascent: Double,
    val descent: Double,
    val spaceCount: Int,
    val hardBroken: Boolean,
) {
    val height: Double get() = ascent + descent
}

/**
 * Lays the flow out: greedy word wrap per paragraph (cached by paragraph
 * revision and width), then pagination into per-page content rects. Everything
 * here is pure and deterministic against the [TextMeasurer].
 */
class FlowLayout(private val measurer: TextMeasurer) {

    /**
     * Derived syntax-highlight colours for a code paragraph (start-sorted char
     * spans), or null for none. Installed by the host; read at placement time so
     * highlight results recolour segments on the next republish, never persisting.
     */
    var codeSpans: (Paragraph) -> List<CodeSpan>? = { null }

    /** The active code theme's block background, read at layout time (null = neutral grey). */
    var codeBackground: () -> Rgba? = { null }

    /** Theme-derived colour for text when the flow sets no explicit default, read at layout time. */
    var autoColor: () -> Rgba? = { null }

    // --- per-paragraph measurement (cached) ---

    /** Advances and per-run fonts/metrics for one paragraph at given flow defaults. */
    private class ParaShape(
        val text: String,
        val adv: DoubleArray,
        val runEnds: IntArray,
        val runMetrics: Array<LineMetrics>,
        val runFonts: Array<FontSpec>,
        val emptyMetrics: LineMetrics,
    )

    private class ShapeEntry(val rev: Int, val defaultsKey: Long, val shape: ParaShape)
    private class BreakEntry(val rev: Int, val defaultsKey: Long, val width: Double, val lines: List<BrokenLine>)

    private val shapes = HashMap<Paragraph, ShapeEntry>()
    private val breaks = HashMap<Paragraph, BreakEntry>()

    private fun defaultsKey(flow: TextFlow): Long =
        (flow.defaultFace.id.hashCode().toLong() shl 32) xor
            (flow.monoFace.id.hashCode().toLong() shl 16) xor
            flow.defaultSizePt.toRawBits()

    private fun shapeOf(flow: TextFlow, para: Paragraph): ParaShape {
        val key = defaultsKey(flow)
        shapes[para]?.let { if (it.rev == para.rev && it.defaultsKey == key) return it.shape }
        val text = para.plainText()
        val adv = DoubleArray(text.length)
        val runEnds = IntArray(para.runs.size)
        val runMetrics = arrayOfNulls<LineMetrics>(para.runs.size)
        val runFonts = arrayOfNulls<FontSpec>(para.runs.size)
        var offset = 0
        for ((i, run) in para.runs.withIndex()) {
            val font = resolveFont(flow, para, run.style)
            val a = measurer.advances(run.text, font)
            a.copyInto(adv, offset)
            offset += run.text.length
            runEnds[i] = offset
            runFonts[i] = font
            runMetrics[i] = measurer.metrics(font)
        }
        val emptyFont = resolveFont(flow, para, CharStyle.DEFAULT)
        val shape = ParaShape(
            text, adv, runEnds,
            @Suppress("UNCHECKED_CAST") (runMetrics as Array<LineMetrics>),
            @Suppress("UNCHECKED_CAST") (runFonts as Array<FontSpec>),
            measurer.metrics(emptyFont),
        )
        shapes[para] = ShapeEntry(para.rev, key, shape)
        return shape
    }

    // --- line breaking ---

    /**
     * Greedy word wrap of [para] at [availWidth]: break opportunities sit after
     * space runs; a word wider than the line hard-breaks per character (always
     * placing at least one char so layout progresses). [fromChar] re-breaks a
     * paragraph remainder when it continues onto a page of different width.
     */
    fun breakLines(flow: TextFlow, para: Paragraph, availWidth: Double, fromChar: Int = 0): List<BrokenLine> {
        val key = defaultsKey(flow)
        if (fromChar == 0) {
            breaks[para]?.let {
                if (it.rev == para.rev && it.defaultsKey == key && it.width == availWidth) return it.lines
            }
        }
        val shape = shapeOf(flow, para)
        val lines = breakShape(shape, availWidth.coerceAtLeast(MIN_LINE_WIDTH), fromChar)
        if (fromChar == 0) breaks[para] = BreakEntry(para.rev, key, availWidth, lines)
        return lines
    }

    private fun breakShape(shape: ParaShape, availWidth: Double, fromChar: Int): List<BrokenLine> {
        val text = shape.text
        val len = text.length
        val start0 = fromChar.coerceIn(0, len)
        if (start0 >= len) {
            return listOf(lineOf(shape, start0, len, 0.0, 0))
        }
        val out = mutableListOf<BrokenLine>()
        var i = start0
        while (i < len) {
            var w = 0.0
            var wAtLastContent = 0.0
            var spacesBeforeLastContent = 0
            var spacesSeen = 0
            var lastBreak = -1
            var lastBreakWidth = 0.0
            var lastBreakSpaces = 0
            var j = i
            var emitted = false
            while (j < len) {
                val c = text[j]
                val a = shape.adv[j]
                if (c == ' ') {
                    // Spaces hang: they widen the running total but never overflow a line.
                    spacesSeen++
                    w += a
                    j++
                    continue
                }
                // A non-space after spaces marks a break opportunity at its start.
                if (j > i && text[j - 1] == ' ') {
                    lastBreak = j
                    lastBreakWidth = wAtLastContent
                    lastBreakSpaces = spacesBeforeLastContent
                }
                if (w + a > availWidth && j > i) {
                    if (lastBreak > i) {
                        out.add(lineOf(shape, i, lastBreak, lastBreakWidth, lastBreakSpaces))
                        i = lastBreak
                    } else {
                        out.add(lineOf(shape, i, j, w, spacesSeen, hardBroken = true))
                        i = j
                    }
                    emitted = true
                    break
                }
                w += a
                wAtLastContent = w
                spacesBeforeLastContent = spacesSeen
                j++
            }
            if (!emitted) {
                out.add(lineOf(shape, i, len, wAtLastContent, spacesBeforeLastContent))
                i = len
            }
        }
        return out
    }

    private fun lineOf(
        shape: ParaShape,
        start: Int,
        end: Int,
        width: Double,
        spaceCount: Int,
        hardBroken: Boolean = false,
    ): BrokenLine {
        var ascent = 0.0
        var descent = 0.0
        if (start >= end) {
            ascent = shape.emptyMetrics.ascent
            descent = shape.emptyMetrics.descent
        } else {
            var runStart = 0
            for (r in shape.runEnds.indices) {
                val runEnd = shape.runEnds[r]
                if (runEnd > start && runStart < end) {
                    val m = shape.runMetrics[r]
                    if (m.ascent > ascent) ascent = m.ascent
                    if (m.descent > descent) descent = m.descent
                }
                runStart = runEnd
            }
            if (ascent == 0.0 && descent == 0.0) {
                ascent = shape.emptyMetrics.ascent
                descent = shape.emptyMetrics.descent
            }
        }
        return BrokenLine(start, end, width, ascent, descent, spaceCount, hardBroken)
    }

    /** Drop cached measurements for paragraphs no longer in [flow] (call after big splices). */
    fun pruneCaches(flow: TextFlow) {
        val live = HashSet<Paragraph>(flow.paragraphs)
        shapes.keys.retainAll(live)
        breaks.keys.retainAll(live)
    }

    // --- pagination ---

    /** The empty-line slot height at the flow's default font (empty-line fill math). */
    fun defaultSlotHeight(flow: TextFlow): Double =
        measurer.metrics(FontSpec(flow.defaultSizePt, flow.defaultFace)).height

    /**
     * Lay the whole flow onto [pageBoxes]: break each paragraph at its page's
     * content width, fill pages top to bottom, re-break a paragraph remainder
     * when it continues onto a page of different width, and count how many
     * virtual pages the tail overflowed past the real ones.
     */
    fun layout(flow: TextFlow, pageBoxes: List<PageBox>, dpi: Int): FlowFrame {
        val defColor = flow.defaultColor ?: autoColor() ?: TextFlow.DEFAULT_COLOR
        if (pageBoxes.isEmpty()) return FlowFrame(emptyList(), flow.rev, 0, defColor, codeBackground())
        pruneCaches(flow)
        val contentRects = pageBoxes.map { contentRectOf(it, flow.margins, dpi) }
        val pageLines = List(pageBoxes.size) { mutableListOf<PlacedLine>() }
        var page = 0
        var y = contentRects[0].top
        var ordinal = 0
        for ((paraIndex, para) in flow.paragraphs.withIndex()) {
            ordinal = if (para.list == ListKind.ORDERED) ordinal + 1 else 0
            val indentPx = para.indent * INDENT_STEP_PX +
                if (para.list != ListKind.NONE) MARKER_GUTTER_PX else 0.0
            var rect = contentRects.getOrElse(page) { contentRects.last() }
            var avail = (rect.w - indentPx).coerceAtLeast(MIN_LINE_WIDTH)
            var lines = breakLines(flow, para, avail)
            var li = 0
            var firstLine = true
            while (li < lines.size) {
                val bl = lines[li]
                if (y + bl.height > rect.bottom + EPS && y > rect.top + EPS) {
                    page++
                    rect = contentRects.getOrElse(page) { contentRects.last() }
                    y = rect.top
                    val widthNow = (rect.w - indentPx).coerceAtLeast(MIN_LINE_WIDTH)
                    if (widthNow != avail) {
                        avail = widthNow
                        lines = breakLines(flow, para, avail, fromChar = bl.startChar)
                        li = 0
                        continue
                    }
                }
                val placed = placeLine(
                    flow, para, paraIndex, shapeOf(flow, para), bl, rect, indentPx, avail, y,
                    lastLineOfPara = li == lines.lastIndex,
                    firstLineOfPara = firstLine && bl.startChar == 0,
                    ordinal = ordinal,
                )
                pageLines.getOrNull(page)?.add(placed)
                y += bl.height
                firstLine = false
                li++
            }
        }
        val extra = (page - (pageBoxes.size - 1)).coerceAtLeast(0)
        val pages = pageBoxes.indices.map { PageFlow(contentRects[it], pageLines[it]) }
        return FlowFrame(pages, flow.rev, extra, defColor, codeBackground())
    }

    private fun placeLine(
        flow: TextFlow,
        para: Paragraph,
        paraIndex: Int,
        shape: ParaShape,
        bl: BrokenLine,
        contentRect: Rect,
        indentPx: Double,
        avail: Double,
        y: Double,
        lastLineOfPara: Boolean,
        firstLineOfPara: Boolean,
        ordinal: Int,
    ): PlacedLine {
        val n = bl.endChar - bl.startChar
        val justify = para.align == ParaAlign.JUSTIFY &&
            !lastLineOfPara && !bl.hardBroken && bl.spaceCount > 0
        val extraPerSpace = if (justify) ((avail - bl.width) / bl.spaceCount).coerceAtLeast(0.0) else 0.0
        val alignOffset = when (para.align) {
            ParaAlign.CENTER -> ((avail - bl.width) / 2.0).coerceAtLeast(0.0)
            ParaAlign.RIGHT -> (avail - bl.width).coerceAtLeast(0.0)
            else -> 0.0
        }
        val left = contentRect.left + indentPx + alignOffset
        val xs = DoubleArray(n + 1)
        xs[0] = left
        var stretched = 0
        for (k in 0 until n) {
            val ci = bl.startChar + k
            var a = shape.adv[ci]
            if (extraPerSpace > 0.0 && shape.text[ci] == ' ' && stretched < bl.spaceCount) {
                a += extraPerSpace
                stretched++
            }
            xs[k + 1] = xs[k] + a
        }
        val segs = mutableListOf<Seg>()
        val decos = mutableListOf<Deco>()
        val spans = if (para.codeLang != null) codeSpans(para) else null
        var runStart = 0
        for (r in para.runs.indices) {
            val runEnd = shape.runEnds[r]
            val from = maxOf(bl.startChar, runStart)
            val to = minOf(bl.endChar, runEnd)
            if (to > from) {
                val font = shape.runFonts[r]
                val style = para.runs[r].style
                if (style.underline || style.strike || style.highlight != null || style.code) {
                    decos.add(Deco(xs[from - bl.startChar], xs[to - bl.startChar], font, style))
                }
                // Words draw one run-fragment at a time; spaces are gaps the xs already carry.
                var s = from
                while (s < to) {
                    if (shape.text[s] == ' ') {
                        s++
                        continue
                    }
                    var e = s
                    while (e < to && shape.text[e] != ' ') e++
                    if (spans == null) {
                        segs.add(Seg(shape.text.substring(s, e), xs[s - bl.startChar], font, style))
                    } else {
                        emitHighlighted(shape, spans, s, e, xs, bl.startChar, font, style, segs)
                    }
                    s = e
                }
            }
            runStart = runEnd
        }
        val marker = if (firstLineOfPara && para.list != ListKind.NONE) {
            val gutterLeft = contentRect.left + para.indent * INDENT_STEP_PX
            val gutter = Rect(gutterLeft, y, MARKER_GUTTER_PX, bl.height)
            val font = FontSpec(flow.defaultSizePt, flow.defaultFace)
            if (para.list == ListKind.ORDERED) {
                val label = "$ordinal."
                val labelW = measurer.advances(label, font).sum()
                Marker(para.list, para.checked, ordinal, gutter, label, gutter.right - MARKER_GAP - labelW, font)
            } else {
                Marker(para.list, para.checked, ordinal, gutter, font = font)
            }
        } else {
            null
        }
        return PlacedLine(
            paraIndex, bl.startChar, bl.endChar,
            top = y, baseline = y + bl.ascent, bottom = y + bl.height,
            xs = xs, segs = segs, decos = decos, marker = marker,
            codeLine = para.codeLang != null,
            codeLeft = contentRect.left + indentPx,
            codeRight = contentRect.right,
        )
    }

    /** Emit one word's segments, split at highlight-span boundaries with their colours. */
    private fun emitHighlighted(
        shape: ParaShape,
        spans: List<CodeSpan>,
        from: Int,
        to: Int,
        xs: DoubleArray,
        blStart: Int,
        font: FontSpec,
        style: CharStyle,
        segs: MutableList<Seg>,
    ) {
        var i = from
        while (i < to) {
            val covering = spans.firstOrNull { it.start <= i && i < it.end }
            val boundary = if (covering != null) {
                minOf(to, covering.end)
            } else {
                minOf(to, spans.firstOrNull { it.start > i }?.start ?: to)
            }
            val st = if (covering != null) style.copy(color = covering.color) else style
            segs.add(Seg(shape.text.substring(i, boundary), xs[i - blStart], font, st))
            i = boundary
        }
    }

    private fun contentRectOf(box: PageBox, m: FlowMargins, dpi: Int): Rect {
        val l = PageSize.mmToPx(m.leftMm, dpi)
        val t = PageSize.mmToPx(m.topMm, dpi)
        val r = PageSize.mmToPx(m.rightMm, dpi)
        val b = PageSize.mmToPx(m.bottomMm, dpi)
        var left = l
        var w = box.width - l - r
        if (w < MIN_LINE_WIDTH) {
            w = minOf(MIN_LINE_WIDTH, box.width)
            left = ((box.width - w) / 2.0).coerceAtLeast(0.0)
        }
        var top = t
        var h = box.height - t - b
        if (h < MIN_CONTENT_HEIGHT) {
            h = minOf(MIN_CONTENT_HEIGHT, box.height)
            top = ((box.height - h) / 2.0).coerceAtLeast(0.0)
        }
        return Rect(left, top, w, h)
    }

    companion object {
        /** Indent step per level, content px at 150 dpi (~8 mm). */
        const val INDENT_STEP_PX = 48.0

        /** Gutter reserved left of list paragraphs for bullet/number/checkbox markers. */
        const val MARKER_GUTTER_PX = 40.0

        /** Gap between a marker's right edge and the paragraph text. */
        const val MARKER_GAP = 10.0

        /** Layout never wraps narrower than this, however extreme the margins. */
        const val MIN_LINE_WIDTH = 20.0

        /** A content rect is never shorter than this, however extreme the margins. */
        const val MIN_CONTENT_HEIGHT = 16.0

        private const val EPS = 0.01
    }
}
