package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Growable position and index arrays, so a whole item tessellates without allocating per vertex.
 *
 * Everything the canvas draws is triangles built here: ink ribbons, shape outlines, shape fills.
 * Positions stay in content-space doubles, because the canvas is unbounded and a float absolute
 * position has already lost visible precision a million pixels out; the uploader splits each one
 * into a chunk index and a small local offset on its way to the GPU.
 */
internal class MeshBuilder(vertexHint: Int = 64, indexHint: Int = 96) {

    private var pos = DoubleArray(maxOf(8, vertexHint * 2))
    private var idx = IntArray(maxOf(12, indexHint))
    private var vertexCount = 0
    private var indexCount = 0

    val isEmpty: Boolean get() = indexCount == 0

    fun vertex(x: Double, y: Double): Int {
        if (2 * vertexCount + 2 > pos.size) pos = pos.copyOf(pos.size * 2)
        pos[2 * vertexCount] = x
        pos[2 * vertexCount + 1] = y
        return vertexCount++
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[indexCount] = a
        idx[indexCount + 1] = b
        idx[indexCount + 2] = c
        indexCount += 3
    }

    /** A filled disc as a triangle fan, cut fine enough to stay within [tolerance] of true. */
    fun circle(cx: Double, cy: Double, radius: Double, tolerance: Double) {
        if (radius <= 0.0) return
        val segments = circleSegments(radius, tolerance)
        val centre = vertex(cx, cy)
        val step = 2.0 * PI / segments
        var first = -1
        var prev = -1
        for (k in 0 until segments) {
            val a = k * step
            val v = vertex(cx + radius * cos(a), cy + radius * sin(a))
            if (first < 0) first = v else triangle(centre, prev, v)
            prev = v
        }
        if (first >= 0 && prev != first) triangle(centre, prev, first)
    }

    /** A filled ellipse as a triangle fan; the segment count follows the larger radius. */
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, tolerance: Double) {
        if (rx <= 0.0 || ry <= 0.0) return
        val segments = circleSegments(maxOf(rx, ry), tolerance)
        val centre = vertex(cx, cy)
        val step = 2.0 * PI / segments
        var first = -1
        var prev = -1
        for (k in 0 until segments) {
            val a = k * step
            val v = vertex(cx + rx * cos(a), cy + ry * sin(a))
            if (first < 0) first = v else triangle(centre, prev, v)
            prev = v
        }
        if (first >= 0 && prev != first) triangle(centre, prev, first)
    }

    /** Two triangles over an axis-aligned rectangle. */
    fun rect(x: Double, y: Double, w: Double, h: Double) {
        if (w <= 0.0 || h <= 0.0) return
        val a = vertex(x, y)
        val b = vertex(x + w, y)
        val c = vertex(x + w, y + h)
        val d = vertex(x, y + h)
        triangle(a, b, c)
        triangle(a, c, d)
    }

    /**
     * A constant-width ribbon down [points], with a disc at every vertex so ends are round and hard
     * turns cannot pinch. This is the shape outline's equivalent of the ink ribbon, and it draws the
     * same silhouette a round-capped, round-joined pen would.
     */
    fun polylineRibbon(points: List<Pt>, halfWidth: Double, closed: Boolean, tolerance: Double) {
        if (halfWidth <= 0.0 || points.size < 2) {
            if (points.size == 1 && halfWidth > 0.0) circle(points[0].x, points[0].y, halfWidth, tolerance)
            return
        }
        val n = points.size
        val segments = if (closed) n else n - 1
        for (i in 0 until segments) {
            val a = points[i]
            val b = points[(i + 1) % n]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val nx = -dy / len * halfWidth
            val ny = dx / len * halfWidth
            val v0 = vertex(a.x + nx, a.y + ny)
            val v1 = vertex(a.x - nx, a.y - ny)
            val v2 = vertex(b.x + nx, b.y + ny)
            val v3 = vertex(b.x - nx, b.y - ny)
            triangle(v0, v1, v3)
            triangle(v0, v3, v2)
        }
        // A disc at every vertex rounds the two ends and fills the notch at each corner.
        val discs = if (closed) n else n
        for (i in 0 until discs) circle(points[i].x, points[i].y, halfWidth, tolerance)
    }

    /**
     * A filled simple polygon, by ear clipping. Only shapes the recognizer produces ever reach
     * here, so the input is a few dozen vertices at most and the quadratic worst case never bites.
     */
    fun polygon(points: List<Pt>) {
        if (points.size < 3) return
        val remaining = ArrayList<Pt>(points)
        // Work in a consistent winding, so the convexity test has one sign to check.
        if (signedArea(remaining) < 0.0) remaining.reverse()
        val handles = IntArray(remaining.size) { vertex(remaining[it].x, remaining[it].y) }
        val live = ArrayList<Int>(remaining.indices.toList())
        var guard = live.size * live.size + 8
        while (live.size > 3 && guard-- > 0) {
            var clipped = false
            for (i in live.indices) {
                val prev = live[(i + live.size - 1) % live.size]
                val cur = live[i]
                val next = live[(i + 1) % live.size]
                if (!isEar(remaining, live, prev, cur, next)) continue
                triangle(handles[prev], handles[cur], handles[next])
                live.removeAt(i)
                clipped = true
                break
            }
            // A self-intersecting outline has no ear left; fan the rest rather than give up on it.
            if (!clipped) break
        }
        if (live.size >= 3) {
            for (i in 1 until live.size - 1) {
                triangle(handles[live[0]], handles[live[i]], handles[live[i + 1]])
            }
        }
    }

    fun build(): MeshData = MeshData(pos.copyOf(2 * vertexCount), idx.copyOf(indexCount))

    private fun isEar(points: List<Pt>, live: List<Int>, prev: Int, cur: Int, next: Int): Boolean {
        val a = points[prev]
        val b = points[cur]
        val c = points[next]
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        if (cross <= 0.0) return false // reflex corner, not an ear
        for (other in live) {
            if (other == prev || other == cur || other == next) continue
            if (inTriangle(points[other], a, b, c)) return false
        }
        return true
    }

    private fun inTriangle(p: Pt, a: Pt, b: Pt, c: Pt): Boolean {
        fun side(x: Pt, y: Pt) = (y.x - x.x) * (p.y - x.y) - (y.y - x.y) * (p.x - x.x)
        val d1 = side(a, b)
        val d2 = side(b, c)
        val d3 = side(c, a)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun signedArea(points: List<Pt>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    companion object {
        /** Fewest and most segments a full circle is ever cut into. */
        const val MIN_CIRCLE_SEGMENTS = 8
        const val MAX_CIRCLE_SEGMENTS = 64

        /** Segments a circle of [radius] needs to stay within [tolerance] of true. */
        fun circleSegments(radius: Double, tolerance: Double): Int {
            if (!radius.isFinite() || radius <= 0.0) return MIN_CIRCLE_SEGMENTS
            if (!tolerance.isFinite() || tolerance <= 0.0) return MAX_CIRCLE_SEGMENTS
            if (tolerance >= radius) return MIN_CIRCLE_SEGMENTS
            val a = kotlin.math.acos(1.0 - tolerance / radius)
            if (a <= 0.0) return MAX_CIRCLE_SEGMENTS
            return kotlin.math.ceil(PI / a).toInt().coerceIn(MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS)
        }

        /**
         * [points] cut into the runs a dashed outline actually paints, walking the path and
         * alternating [dashLength] on and [gapLength] off. A degenerate dash returns the whole
         * path, so a bad setting draws a solid line rather than nothing.
         */
        fun dashRuns(points: List<Pt>, dashLength: Double, gapLength: Double, closed: Boolean): List<List<Pt>> {
            if (points.size < 2) return listOf(points)
            val on = dashLength.coerceAtLeast(0.0)
            val off = gapLength.coerceAtLeast(0.0)
            if (on <= 1e-6 || off <= 1e-6) return listOf(points)
            val path = if (closed) points + points.first() else points
            val runs = ArrayList<List<Pt>>()
            var current = ArrayList<Pt>()
            var drawing = true
            var left = on
            var at = path[0]
            current.add(at)
            var i = 1
            while (i < path.size) {
                val target = path[i]
                val dx = target.x - at.x
                val dy = target.y - at.y
                val len = hypot(dx, dy)
                if (len < 1e-12) {
                    i++
                    continue
                }
                if (len <= left) {
                    left -= len
                    at = target
                    if (drawing) current.add(at)
                    i++
                    continue
                }
                val f = left / len
                val cut = Pt(at.x + dx * f, at.y + dy * f)
                if (drawing) {
                    current.add(cut)
                    if (current.size >= 2) runs.add(current)
                    current = ArrayList()
                } else {
                    current = ArrayList()
                    current.add(cut)
                }
                at = cut
                drawing = !drawing
                left = if (drawing) on else off
            }
            if (drawing && current.size >= 2) runs.add(current)
            return runs
        }

        /** Absolute difference between two angles, folded into `[0, PI]`. */
        fun angleBetween(a: Double, b: Double): Double {
            var d = abs(a - b) % (2 * PI)
            if (d > PI) d = 2 * PI - d
            return d
        }
    }
}
