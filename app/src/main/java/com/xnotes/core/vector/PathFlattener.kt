package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import kotlin.math.abs

/**
 * Turns a contour's curves into a polyline within a stated tolerance.
 *
 * Geometry is uploaded once and drawn at every zoom, so the tolerance is fixed at mesh time rather
 * than chased as the view moves. The mesher passes the deviation that is invisible at the canvas's
 * maximum zoom, exactly as ink does, and the result stays sharp all the way in.
 *
 * Subdivision is adaptive: a nearly straight curve costs two points, a tight one costs as many as
 * it needs up to [MAX_DEPTH]. That cap is what stops a pathological control polygon from turning
 * one glyph into a hundred thousand vertices.
 */
object PathFlattener {

    /** [contour] as a polyline within [tolerance] of the true curve, in the contour's own space. */
    fun flatten(contour: VectorContour, tolerance: Double): List<Pt> {
        val out = ArrayList<Pt>(contour.segments.size + 1)
        out.add(contour.start)
        var at = contour.start
        val tol = if (tolerance.isFinite() && tolerance > 0.0) tolerance else 1e-3
        for (seg in contour.segments) {
            when (seg) {
                is VectorSeg.Line -> out.add(seg.end)
                is VectorSeg.Cubic -> {
                    cubic(out, at, seg.c1, seg.c2, seg.end, tol * tol, 0)
                    out.add(seg.end)
                }
            }
            at = seg.end
        }
        dropRepeats(out)
        // A closed ring is implicitly closed by the consumer, so a duplicated last point is noise.
        if (contour.closed && out.size > 1 && near(out.first(), out.last())) out.removeAt(out.size - 1)
        return out
    }

    /**
     * [points] thinned to the fewest that still stay within [tolerance] of the original, by
     * Ramer-Douglas-Peucker.
     *
     * Uniformly sampled geometry, which is all a platform path walker can hand back, spends most of
     * its points on the straight parts. A capital H sampled every fraction of an em is a hundred
     * points describing twelve corners; this puts it back to twelve.
     */
    fun decimate(points: List<Pt>, tolerance: Double): List<Pt> {
        if (points.size < 3 || tolerance <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        rdp(points, keep, 0, points.size - 1, tolerance)
        val out = ArrayList<Pt>(points.size)
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    private fun rdp(points: List<Pt>, keep: BooleanArray, first: Int, last: Int, tolerance: Double) {
        if (last <= first + 1) return
        val a = points[first]
        val b = points[last]
        var worst = -1
        var worstDist = tolerance
        for (i in first + 1 until last) {
            val d = distanceToSegment(points[i], a, b)
            if (d > worstDist) {
                worstDist = d
                worst = i
            }
        }
        if (worst < 0) return
        keep[worst] = true
        rdp(points, keep, first, worst, tolerance)
        rdp(points, keep, worst, last, tolerance)
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-18) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /**
     * Emit the interior of one cubic, exclusive of both ends. The flatness test is the standard
     * one: the control points' distance from the chord, compared squared so no square root is
     * taken per candidate.
     */
    private fun cubic(out: MutableList<Pt>, p0: Pt, p1: Pt, p2: Pt, p3: Pt, tolSq: Double, depth: Int) {
        if (depth >= MAX_DEPTH) return
        val dx = p3.x - p0.x
        val dy = p3.y - p0.y
        val d1 = abs((p1.x - p3.x) * dy - (p1.y - p3.y) * dx)
        val d2 = abs((p2.x - p3.x) * dy - (p2.y - p3.y) * dx)
        val d = d1 + d2
        val chordSq = dx * dx + dy * dy
        if (d * d <= tolSq * chordSq) {
            // A closed loop has a zero-length chord, which the test above always passes: fall back
            // to the control polygon's own extent so a teardrop shape is not collapsed to a point.
            if (chordSq > 1e-18 || spread(p0, p1, p2, p3) <= tolSq) return
        }
        val a1 = mid(p0, p1)
        val a2 = mid(p1, p2)
        val a3 = mid(p2, p3)
        val b1 = mid(a1, a2)
        val b2 = mid(a2, a3)
        val m = mid(b1, b2)
        cubic(out, p0, a1, b1, m, tolSq, depth + 1)
        out.add(m)
        cubic(out, m, b2, a3, p3, tolSq, depth + 1)
    }

    private fun spread(p0: Pt, p1: Pt, p2: Pt, p3: Pt): Double {
        val d1 = (p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y)
        val d2 = (p2.x - p0.x) * (p2.x - p0.x) + (p2.y - p0.y) * (p2.y - p0.y)
        val d3 = (p3.x - p0.x) * (p3.x - p0.x) + (p3.y - p0.y) * (p3.y - p0.y)
        return maxOf(d1, d2, d3)
    }

    private fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

    private fun dropRepeats(pts: MutableList<Pt>) {
        var i = pts.size - 1
        while (i > 0) {
            if (near(pts[i], pts[i - 1])) pts.removeAt(i)
            i--
        }
    }

    private fun near(a: Pt, b: Pt): Boolean = abs(a.x - b.x) < EPS && abs(a.y - b.y) < EPS

    private const val EPS = 1e-9

    /** Deepest a single curve is ever split: 2^12 segments is far past any real outline. */
    private const val MAX_DEPTH = 12
}
