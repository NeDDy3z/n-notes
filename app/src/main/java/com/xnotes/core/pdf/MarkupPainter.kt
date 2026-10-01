package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * Paints text markups in points as displayed, the space their quads are in. A quad is measured in
 * its reading frame: u runs along the line from where it starts, v from the glyphs' tops down to
 * their feet, which [TextQuad.quarter] turns onto the page.
 */
object MarkupPainter {

    /** Paints [markups] bottom first, at [scale] px a point from the page's top-left corner. */
    fun paint(r: Renderer, markups: List<TextMarkup>, scale: Double) {
        if (markups.isEmpty()) return
        r.withSave {
            r.scale(scale, scale)
            for (m in markups) paint(r, m)
        }
    }

    /** Paints [m] in points. */
    fun paint(r: Renderer, m: TextMarkup) {
        if (m.type == MarkupType.HIGHLIGHT) {
            // One layer per markup: its own overlapping lines never darken twice, stacked markups do.
            quadBounds(m.quads)?.let { bounds ->
                r.saveLayerBlended(bounds, m.intensity, BlendMode.MULTIPLY)
                for (q in m.quads) r.fillRect(Rect.ltrb(q.left.toDouble(), q.top.toDouble(), q.right.toDouble(), q.bottom.toDouble()), m.color)
                r.restore()
            }
        } else {
            for (q in m.quads) {
                val line = lineOf(m.type, q)
                if (line.size >= 2) r.strokePolyline(line, Pen(m.color, thickness(q), cosmetic = false))
            }
        }
        if (m.note != null) markerOf(m)?.let { marker ->
            r.fillCircle(marker.center, marker.radius, m.color)
            r.fillPolygon(marker.tail, m.color, FillRule.NONZERO)
        }
    }

    /** Where [m] paints, in points; null when it has no quads. */
    fun bounds(m: TextMarkup): Rect? {
        val quads = quadBounds(m.quads) ?: return null
        val marker = if (m.note != null) markerOf(m)?.bounds else null
        return if (marker == null) quads else quads.union(marker)
    }

    /** The note marker: a speech bubble just past the markup's end, level with the line's top. */
    class Marker(val center: Pt, val radius: Double, val tail: List<Pt>) {
        val bounds: Rect get() = Rect.bounding(tail).union(Rect(center.x - radius, center.y - radius, 2 * radius, 2 * radius))
    }

    /** Where [m]'s note marker goes, whether or not it has a note; null when it has no quads. */
    fun markerOf(m: TextMarkup): Marker? {
        val q = m.quads.lastOrNull() ?: return null
        val h = across(q)
        val radius = MARKER_SIZE * h / 2
        val u = along(q) + MARKER_GAP * h + radius
        val v = radius
        val tail = listOf(Pt(-0.2, 0.9), Pt(-0.75, 0.55), Pt(-0.95, 1.15)).map { (du, dv) ->
            frame(q, u + du * radius, v + dv * radius)
        }
        return Marker(frame(q, u, v), radius, tail)
    }

    /** The centre line of a line type along [q]: a straight line, or the squiggle's wave. */
    internal fun lineOf(type: MarkupType, q: TextQuad): List<Pt> {
        val len = along(q)
        val h = across(q)
        val w = thickness(q)
        return when (type) {
            MarkupType.UNDERLINE -> listOf(frame(q, 0.0, h - w), frame(q, len, h - w))
            MarkupType.STRIKEOUT -> listOf(frame(q, 0.0, h / 2), frame(q, len, h / 2))
            MarkupType.SQUIGGLY -> {
                val period = h / WAVE_PERIODS
                val amp = h * WAVE_AMPLITUDE
                val mid = h - w / 2 - amp
                val steps = max(2, ceil(len / period * WAVE_STEPS).toInt())
                List(steps + 1) { i ->
                    val u = len * i / steps
                    frame(q, u, mid + amp * sin(2 * PI * u / period))
                }
            }
            MarkupType.HIGHLIGHT -> emptyList()
        }
    }

    /** How thick a line type is drawn on [q]. */
    internal fun thickness(q: TextQuad): Double = max(across(q) / THICKNESS_DIVISOR, MIN_THICKNESS)

    private fun along(q: TextQuad): Double = (if (q.quarter % 2 == 0) q.right - q.left else q.bottom - q.top).toDouble()

    private fun across(q: TextQuad): Double = (if (q.quarter % 2 == 0) q.bottom - q.top else q.right - q.left).toDouble()

    /** The page point at [u] along [q]'s line from its start and [v] down from its glyphs' tops. */
    internal fun frame(q: TextQuad, u: Double, v: Double): Pt {
        val l = q.left.toDouble()
        val t = q.top.toDouble()
        val r = q.right.toDouble()
        val b = q.bottom.toDouble()
        return when (q.quarter) {
            1 -> Pt(r - v, t + u)
            2 -> Pt(r - u, b - v)
            3 -> Pt(l + v, b - u)
            else -> Pt(l + u, t + v)
        }
    }

    private fun quadBounds(quads: List<TextQuad>): Rect? {
        if (quads.isEmpty()) return null
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (q in quads) {
            l = minOf(l, q.left)
            t = minOf(t, q.top)
            r = maxOf(r, q.right)
            b = maxOf(b, q.bottom)
        }
        return Rect.ltrb(l.toDouble(), t.toDouble(), r.toDouble(), b.toDouble())
    }

    private const val THICKNESS_DIVISOR = 14.0
    private const val MIN_THICKNESS = 0.5
    private const val WAVE_PERIODS = 3.5
    private const val WAVE_AMPLITUDE = 1.0 / 12
    private const val WAVE_STEPS = 8
    private const val MARKER_SIZE = 0.55
    private const val MARKER_GAP = 0.15
}
