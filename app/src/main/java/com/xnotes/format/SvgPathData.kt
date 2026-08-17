package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Parses SVG path data — the `d` attribute's mini-language — into contours.
 *
 * Quadratics and elliptical arcs both become cubics on the way in, so everything downstream deals
 * with lines and cubics only. An arc is split at the quadrant boundaries, which is the standard
 * construction and stays inside a thousandth of the true ellipse.
 *
 * Reading is forgiving in the same way the rest of the format layer is: a malformed command ends
 * the path where it went wrong rather than failing the whole document, so a slightly broken file
 * still draws what it got right.
 */
object SvgPathData {

    fun parse(d: String): List<VectorContour> {
        val out = ArrayList<VectorContour>()
        val s = Scanner(d)
        var segs = ArrayList<VectorSeg>()
        var start = Pt(0.0, 0.0)
        var cur = Pt(0.0, 0.0)
        var cubicCtrl: Pt? = null
        var quadCtrl: Pt? = null
        var cmd = ' '

        fun flush(closed: Boolean) {
            if (segs.isNotEmpty()) out.add(VectorContour(start, segs, closed))
            segs = ArrayList()
        }

        while (true) {
            s.skipSeparators()
            if (s.atEnd) break
            val letter = s.takeCommand()
            if (letter != null) {
                cmd = letter
            } else if (cmd == ' ') {
                break // numbers before any command at all
            }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = s.number() ?: break
                    val y = s.number() ?: break
                    flush(closed = false)
                    start = if (rel) Pt(cur.x + x, cur.y + y) else Pt(x, y)
                    cur = start
                    cubicCtrl = null
                    quadCtrl = null
                    // Further coordinate pairs after a moveto are linetos, per the grammar.
                    cmd = if (rel) 'l' else 'L'
                }

                'Z' -> {
                    flush(closed = true)
                    cur = start
                    cubicCtrl = null
                    quadCtrl = null
                }

                'L' -> {
                    val x = s.number() ?: break
                    val y = s.number() ?: break
                    cur = if (rel) Pt(cur.x + x, cur.y + y) else Pt(x, y)
                    segs.add(VectorSeg.Line(cur))
                    cubicCtrl = null
                    quadCtrl = null
                }

                'H' -> {
                    val x = s.number() ?: break
                    cur = Pt(if (rel) cur.x + x else x, cur.y)
                    segs.add(VectorSeg.Line(cur))
                    cubicCtrl = null
                    quadCtrl = null
                }

                'V' -> {
                    val y = s.number() ?: break
                    cur = Pt(cur.x, if (rel) cur.y + y else y)
                    segs.add(VectorSeg.Line(cur))
                    cubicCtrl = null
                    quadCtrl = null
                }

                'C' -> {
                    val c1 = s.point(cur, rel) ?: break
                    val c2 = s.point(cur, rel) ?: break
                    val end = s.point(cur, rel) ?: break
                    segs.add(VectorSeg.Cubic(c1, c2, end))
                    cur = end
                    cubicCtrl = c2
                    quadCtrl = null
                }

                'S' -> {
                    val c2 = s.point(cur, rel) ?: break
                    val end = s.point(cur, rel) ?: break
                    val c1 = reflect(cubicCtrl, cur)
                    segs.add(VectorSeg.Cubic(c1, c2, end))
                    cur = end
                    cubicCtrl = c2
                    quadCtrl = null
                }

                'Q' -> {
                    val q = s.point(cur, rel) ?: break
                    val end = s.point(cur, rel) ?: break
                    segs.add(quadratic(cur, q, end))
                    cur = end
                    quadCtrl = q
                    cubicCtrl = null
                }

                'T' -> {
                    val end = s.point(cur, rel) ?: break
                    val q = reflect(quadCtrl, cur)
                    segs.add(quadratic(cur, q, end))
                    cur = end
                    quadCtrl = q
                    cubicCtrl = null
                }

                'A' -> {
                    val rx = s.number() ?: break
                    val ry = s.number() ?: break
                    val rot = s.number() ?: break
                    val large = s.flag() ?: break
                    val sweep = s.flag() ?: break
                    val end = s.point(cur, rel) ?: break
                    arc(cur, rx, ry, rot, large, sweep, end, segs)
                    cur = end
                    cubicCtrl = null
                    quadCtrl = null
                }

                else -> break // an unknown command: stop rather than misread the rest
            }
        }
        flush(closed = false)
        return out
    }

    /** [ctrl] mirrored through [cur], the smooth-curve rule; [cur] itself when there is no previous. */
    private fun reflect(ctrl: Pt?, cur: Pt): Pt =
        if (ctrl == null) cur else Pt(2.0 * cur.x - ctrl.x, 2.0 * cur.y - ctrl.y)

    /** A quadratic as the cubic it is exactly equal to. */
    private fun quadratic(p0: Pt, q: Pt, p2: Pt): VectorSeg.Cubic = VectorSeg.Cubic(
        Pt(p0.x + 2.0 / 3.0 * (q.x - p0.x), p0.y + 2.0 / 3.0 * (q.y - p0.y)),
        Pt(p2.x + 2.0 / 3.0 * (q.x - p2.x), p2.y + 2.0 / 3.0 * (q.y - p2.y)),
        p2,
    )

    /**
     * An elliptical arc as cubics, by the endpoint-to-centre conversion in the SVG spec's
     * implementation notes. Radii too small to reach the endpoint are scaled up, as the spec
     * requires, and the sweep is cut at the quadrants so no piece exceeds a quarter turn.
     */
    private fun arc(
        p0: Pt,
        rxIn: Double,
        ryIn: Double,
        rotationDeg: Double,
        largeArc: Boolean,
        sweep: Boolean,
        p1: Pt,
        out: MutableList<VectorSeg>,
    ) {
        if (abs(p0.x - p1.x) < 1e-12 && abs(p0.y - p1.y) < 1e-12) return
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < 1e-12 || ry < 1e-12) {
            out.add(VectorSeg.Line(p1))
            return
        }
        val phi = rotationDeg * PI / 180.0
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx2 = (p0.x - p1.x) / 2.0
        val dy2 = (p0.y - p1.y) / 2.0
        val x1 = cosPhi * dx2 + sinPhi * dy2
        val y1 = -sinPhi * dx2 + cosPhi * dy2
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1.0) {
            val k = sqrt(lambda)
            rx *= k
            ry *= k
        }
        val num = (rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1).coerceAtLeast(0.0)
        val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        val coef = (if (largeArc == sweep) -1.0 else 1.0) * sqrt(if (den <= 0.0) 0.0 else num / den)
        val cxp = coef * rx * y1 / ry
        val cyp = -coef * ry * x1 / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (p0.x + p1.x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (p0.y + p1.y) / 2.0
        val theta = angle(1.0, 0.0, (x1 - cxp) / rx, (y1 - cyp) / ry)
        var delta = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
        if (!sweep && delta > 0.0) delta -= 2.0 * PI
        if (sweep && delta < 0.0) delta += 2.0 * PI
        val pieces = ceil(abs(delta) / (PI / 2.0) - 1e-9).toInt().coerceAtLeast(1)
        val step = delta / pieces
        val alpha = 4.0 / 3.0 * tan(step / 4.0)
        for (k in 0 until pieces) {
            val ta = theta + k * step
            val tb = ta + step
            val pa = onEllipse(cx, cy, rx, ry, cosPhi, sinPhi, ta)
            val pb = onEllipse(cx, cy, rx, ry, cosPhi, sinPhi, tb)
            val da = ellipseTangent(rx, ry, cosPhi, sinPhi, ta)
            val db = ellipseTangent(rx, ry, cosPhi, sinPhi, tb)
            out.add(
                VectorSeg.Cubic(
                    Pt(pa.x + alpha * da.x, pa.y + alpha * da.y),
                    Pt(pb.x - alpha * db.x, pb.y - alpha * db.y),
                    pb,
                ),
            )
        }
    }

    private fun onEllipse(cx: Double, cy: Double, rx: Double, ry: Double, cosPhi: Double, sinPhi: Double, t: Double) =
        Pt(
            cx + rx * cosPhi * cos(t) - ry * sinPhi * sin(t),
            cy + rx * sinPhi * cos(t) + ry * cosPhi * sin(t),
        )

    private fun ellipseTangent(rx: Double, ry: Double, cosPhi: Double, sinPhi: Double, t: Double) =
        Pt(
            -rx * cosPhi * sin(t) - ry * sinPhi * cos(t),
            -rx * sinPhi * sin(t) + ry * cosPhi * cos(t),
        )

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val lu = hypot(ux, uy)
        val lv = hypot(vx, vy)
        if (lu < 1e-18 || lv < 1e-18) return 0.0
        val dot = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
        val sign = if (ux * vy - uy * vx < 0.0) -1.0 else 1.0
        return sign * acos(dot)
    }

    /**
     * A cursor over path data. Numbers may run together without separators, a sign starts a new
     * one, and the arc flags are single digits with no delimiter at all, so this reads the grammar
     * directly rather than splitting on whitespace.
     */
    private class Scanner(private val src: String) {
        private var i = 0

        val atEnd: Boolean get() = i >= src.length

        fun skipSeparators() {
            while (i < src.length && (src[i].isWhitespace() || src[i] == ',')) i++
        }

        /** The next character when it is a command letter, consuming it. */
        fun takeCommand(): Char? {
            if (atEnd) return null
            val c = src[i]
            if (c !in "MmZzLlHhVvCcSsQqTtAa") return null
            i++
            return c
        }

        fun number(): Double? {
            skipSeparators()
            val begin = i
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            var digits = false
            while (i < src.length && src[i].isDigit()) {
                i++
                digits = true
            }
            if (i < src.length && src[i] == '.') {
                i++
                while (i < src.length && src[i].isDigit()) {
                    i++
                    digits = true
                }
            }
            if (!digits) {
                i = begin
                return null
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                val mark = i
                i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                if (i < src.length && src[i].isDigit()) {
                    while (i < src.length && src[i].isDigit()) i++
                } else {
                    i = mark
                }
            }
            return src.substring(begin, i).toDoubleOrNull().also { if (it == null) i = begin }
        }

        /** An arc flag: one character, '0' or '1', with no separator required after it. */
        fun flag(): Boolean? {
            skipSeparators()
            if (atEnd) return null
            return when (src[i]) {
                '0' -> { i++; false }
                '1' -> { i++; true }
                else -> null
            }
        }

        fun point(cur: Pt, relative: Boolean): Pt? {
            val x = number() ?: return null
            val y = number() ?: return null
            return if (relative) Pt(cur.x + x, cur.y + y) else Pt(x, y)
        }
    }
}
