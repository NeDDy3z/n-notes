package com.xnotes.core.text

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * Paints one page of a [FlowFrame] in page-local content space (origin at the
 * page's top-left), modeled on `paintPagePattern`: pure, region-aware, safe to
 * call from any thread against the immutable frame. Draw order per line: code
 * chip, highlight/inline-code backgrounds, list marker, text runs, then
 * underline/strike so decorations sit over their glyphs. Translucent fills use
 * plain colour alpha; every backend that paints flow text rasterizes (PDF
 * export receives the flow pre-rendered), so no layer tricks are needed.
 */
object FlowPainter {

    fun paintPage(r: Renderer, frame: FlowFrame, pageIndex: Int, region: Rect) {
        val page = frame.pages.getOrNull(pageIndex) ?: return
        for (table in page.tables) {
            if (table.bottom < region.top || table.top > region.bottom) continue
            paintTableFills(r, table)
            paintTableRules(r, table)
        }
        paintCodeChips(r, frame, page.lines, region)
        for (line in page.lines) {
            if (line.bottom < region.top || line.top > region.bottom) continue
            paintLine(r, frame, line)
        }
    }

    /**
     * One background rect per contiguous code block, not per line: abutting
     * per-line rects leave antialiased seam hairlines at their shared fractional
     * edges (two half-covered translucent fills never sum back to a solid one).
     */
    private fun paintCodeChips(r: Renderer, frame: FlowFrame, lines: List<PlacedLine>, region: Rect) {
        var i = 0
        while (i < lines.size) {
            if (!lines[i].codeLine) {
                i++
                continue
            }
            val top = lines[i].top
            var left = lines[i].codeLeft
            var right = lines[i].codeRight
            while (i + 1 < lines.size && lines[i + 1].codeLine) {
                i++
                left = minOf(left, lines[i].codeLeft)
                right = maxOf(right, lines[i].codeRight)
            }
            val bottom = lines[i].bottom
            if (bottom >= region.top && top <= region.bottom) {
                r.fillRect(
                    Rect(left - CODE_PAD, top, right - left + 2 * CODE_PAD, bottom - top),
                    frame.codeBg ?: CODE_BG,
                )
            }
            i++
        }
    }

    /** Header row and banded rows (bands count from the first body row). */
    private fun paintTableFills(r: Renderer, t: TableFrag) {
        val header = t.headerFill != null
        for (row in t.rows) {
            val body = row.row - if (header) 1 else 0
            val fill = when {
                header && row.row == 0 -> t.headerFill
                body >= 0 && body % 2 == 1 -> t.bandFill
                else -> null
            } ?: continue
            r.fillRect(Rect(t.left, row.top, t.right - t.left, row.bottom - row.top), fill)
        }
    }

    /**
     * Rules as filled rects centred on the grid edges. Horizontals run the full
     * width; verticals stop short of them, so a translucent rule never doubles up
     * at a crossing.
     */
    private fun paintTableRules(r: Renderer, t: TableFrag) {
        if (t.borders == TableBorders.NONE) return
        val w = t.lineWidth
        val half = w / 2.0
        fun h(y: Double) = r.fillRect(Rect(t.left - half, y - half, t.right - t.left + w, w), t.lineColor)
        fun v(x: Double) {
            var y = t.top + half
            for (row in t.rows) {
                val end = row.bottom - half
                if (end > y) r.fillRect(Rect(x - half, y, w, end - y), t.lineColor)
                y = row.bottom + half
            }
        }
        h(t.top)
        when (t.borders) {
            TableBorders.OUTER -> {
                h(t.bottom)
                v(t.left)
                v(t.right)
            }
            TableBorders.HORIZONTAL -> for (row in t.rows) h(row.bottom)
            else -> {
                for (row in t.rows) h(row.bottom)
                for (x in t.colXs) v(x)
            }
        }
    }

    private fun paintLine(r: Renderer, frame: FlowFrame, line: PlacedLine) {
        for (deco in line.decos) {
            deco.style.highlight?.let {
                r.fillRect(Rect(deco.x0, line.top, deco.x1 - deco.x0, line.height), it)
            }
            if (deco.style.code && !line.codeLine) {
                r.fillRect(
                    Rect(deco.x0 - CHIP_PAD, line.top, deco.x1 - deco.x0 + 2 * CHIP_PAD, line.height),
                    frame.codeBg ?: CHIP_BG,
                )
            }
            // A formula showing its LaTeX: the chip is the only thing telling the
            // user this is an equation opened up rather than text that lost it.
            deco.math?.let {
                r.fillRect(
                    Rect(deco.x0 - CHIP_PAD, line.top, deco.x1 - deco.x0 + 2 * CHIP_PAD, line.height),
                    if (it == MathShow.ERROR) MATH_ERROR_BG else MATH_BG,
                )
            }
        }
        line.marker?.let { paintMarker(r, frame, line, it) }
        for (seg in line.segs) {
            val color = inkColor(seg.style, frame)
            if (seg.math) {
                r.drawMath(seg.text, seg.x, line.baseline, seg.font.pointSize, color, seg.style.mathDisplay)
                continue
            }
            r.drawTextRun(seg.text, seg.x, line.baseline, seg.font, color)
        }
        val ascent = line.baseline - line.top
        val descent = line.bottom - line.baseline
        val thickness = (line.height / 14.0).coerceAtLeast(1.0)
        for (deco in line.decos) {
            val color = inkColor(deco.style, frame)
            // A hyperlink is underlined even without the explicit underline style.
            if (deco.style.underline || deco.style.link != null) {
                r.fillRect(Rect(deco.x0, line.baseline + descent * 0.35, deco.x1 - deco.x0, thickness), color)
            }
            if (deco.style.strike) {
                r.fillRect(Rect(deco.x0, line.baseline - ascent * 0.30, deco.x1 - deco.x0, thickness), color)
            }
            // LaTeX that would not set is ruled underneath, so a broken formula
            // reads as broken from across the page and not only by its tint.
            if (deco.math == MathShow.ERROR) {
                r.fillRect(
                    Rect(deco.x0 - CHIP_PAD, line.bottom - thickness, deco.x1 - deco.x0 + 2 * CHIP_PAD, thickness),
                    MATH_ERROR_RULE,
                )
            }
        }
    }

    /** A run's ink colour: its own colour, else the link colour for a hyperlink, else the flow default. */
    private fun inkColor(style: CharStyle, frame: FlowFrame): Rgba =
        style.color ?: if (style.link != null) LINK_COLOR else frame.defaultColor

    private fun paintMarker(r: Renderer, frame: FlowFrame, line: PlacedLine, m: Marker) {
        val color = frame.defaultColor
        val ascent = line.baseline - line.top
        when (m.kind) {
            ListKind.BULLET -> {
                val radius = (ascent * 0.16).coerceIn(2.0, 6.0)
                val cx = m.rect.right - FlowLayout.MARKER_GAP - radius
                val cy = line.baseline - ascent * 0.32
                r.fillCircle(Pt(cx, cy), radius, color)
            }
            ListKind.ORDERED -> {
                m.text?.let { r.drawTextRun(it, m.textX, line.baseline, m.font, color) }
            }
            ListKind.CHECK -> {
                val side = (ascent * 0.85).coerceIn(8.0, 26.0)
                val box = Rect(m.rect.right - FlowLayout.MARKER_GAP - side, line.baseline - side, side, side)
                r.strokeRect(box, Pen(color, width = (side / 9.0).coerceAtLeast(1.2), cosmetic = false))
                // Checked state is a solid inner fill: legible on any paper without knowing it.
                if (m.checked) r.fillRect(box.outset(-side * 0.25), color)
            }
            ListKind.NONE -> {}
        }
    }

    /** Code line/chip backgrounds: neutral translucent grey, legible on light and dark paper. */
    val CODE_BG = Rgba(128, 128, 128, 42)
    val CHIP_BG = Rgba(128, 128, 128, 56)

    /** Hyperlink ink: an azure that reads on both light and dark paper. */
    val LINK_COLOR = Rgba(90, 156, 255, 255)

    /**
     * Chips behind a formula showing its source. Both are translucent tints so
     * they sit on light and dark paper alike, and the cool one is deliberately
     * not the code grey: opening an equation is a different thing from a code
     * span, and the warm one says the LaTeX is broken rather than merely open.
     */
    val MATH_BG = Rgba(88, 140, 216, 44)
    val MATH_ERROR_BG = Rgba(214, 74, 68, 52)
    val MATH_ERROR_RULE = Rgba(214, 74, 68, 190)

    const val CODE_PAD = 6.0
    const val CHIP_PAD = 3.0
}
