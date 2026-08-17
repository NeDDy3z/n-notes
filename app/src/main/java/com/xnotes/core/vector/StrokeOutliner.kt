package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.MeshBuilder
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns a stroked polyline into the triangles that fill its outline, with the caps and joins SVG
 * asks for.
 *
 * Ink can get away with a disc at every vertex, because a pen nib is round. A vector stroke cannot:
 * a mitred corner on a diagram's box is a sharp point, and drawing it round is visibly wrong at the
 * zoom this canvas reaches. So each segment contributes a quad and each interior vertex contributes
 * whatever its join needs, a mitre being the intersection of the two outer edges, cut back to a
 * bevel once it grows past the file's own miter limit.
 */
internal object StrokeOutliner {

    fun outline(
        mb: MeshBuilder,
        points: List<Pt>,
        closed: Boolean,
        halfWidth: Double,
        cap: LineCap,
        join: LineJoin,
        miterLimit: Double,
        tolerance: Double,
    ) {
        if (halfWidth <= 0.0) return
        val pts = if (closed && points.size > 2 && same(points.first(), points.last())) {
            points.subList(0, points.size - 1)
        } else {
            points
        }
        if (pts.size < 2) {
            if (pts.size == 1 && cap == LineCap.ROUND) mb.circle(pts[0].x, pts[0].y, halfWidth, tolerance)
            return
        }
        val n = pts.size
        val segments = if (closed) n else n - 1
        for (i in 0 until segments) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val d = unit(a, b) ?: continue
            segmentQuad(mb, a.x, a.y, b.x, b.y, -d.y * halfWidth, d.x * halfWidth)
        }
        val firstJoin = if (closed) 0 else 1
        val lastJoin = if (closed) n - 1 else n - 2
        for (i in firstJoin..lastJoin) {
            val prev = pts[(i + n - 1) % n]
            val here = pts[i]
            val next = pts[(i + 1) % n]
            joinAt(mb, prev, here, next, halfWidth, join, miterLimit, tolerance)
        }
        if (!closed) {
            capAt(mb, pts[1], pts[0], halfWidth, cap, tolerance)
            capAt(mb, pts[n - 2], pts[n - 1], halfWidth, cap, tolerance)
        }
    }

    /**
     * Split [points] into the runs a dash pattern actually paints. The pattern cycles, on runs at
     * even positions, so an SVG's arbitrary-length dasharray works rather than only an on/off pair.
     */
    fun dash(points: List<Pt>, closed: Boolean, pattern: DoubleArray, offset: Double): List<List<Pt>> {
        val period = pattern.sum()
        if (points.size < 2 || pattern.isEmpty() || period <= 1e-9) return listOf(points)
        val path = if (closed) points + points.first() else points
        // Walk the pattern to where the offset lands, so a dashed outline keeps one rhythm.
        var index = 0
        var left = pattern[0]
        var into = ((offset % period) + period) % period
        while (into > 0.0) {
            if (into < left) {
                left -= into
                into = 0.0
            } else {
                into -= left
                index = (index + 1) % pattern.size
                left = pattern[index]
            }
        }
        val runs = ArrayList<List<Pt>>()
        var current = ArrayList<Pt>()
        var on = index % 2 == 0
        var at = path[0]
        if (on) current.add(at)
        var i = 1
        var guard = MAX_DASH_RUNS
        while (i < path.size && guard-- > 0) {
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
                if (on) current.add(at)
                i++
                continue
            }
            val f = left / len
            val cut = Pt(at.x + dx * f, at.y + dy * f)
            if (on) {
                current.add(cut)
                if (current.size >= 2) runs.add(current)
                current = ArrayList()
            } else {
                current = ArrayList()
                current.add(cut)
            }
            at = cut
            on = !on
            index = (index + 1) % pattern.size
            left = pattern[index]
        }
        if (on && current.size >= 2) runs.add(current)
        return runs
    }

    private fun joinAt(
        mb: MeshBuilder,
        prev: Pt,
        here: Pt,
        next: Pt,
        halfWidth: Double,
        join: LineJoin,
        miterLimit: Double,
        tolerance: Double,
    ) {
        if (join == LineJoin.ROUND) {
            mb.circle(here.x, here.y, halfWidth, tolerance)
            return
        }
        val d1 = unit(prev, here) ?: return
        val d2 = unit(here, next) ?: return
        val turn = d1.x * d2.y - d1.y * d2.x
        if (abs(turn) < 1e-12) return // collinear, or doubled straight back: nothing to fill
        // The outer side of the corner is whichever normal points away from the turn.
        val s = if (turn > 0.0) -1.0 else 1.0
        val n1x = -d1.y * halfWidth * s
        val n1y = d1.x * halfWidth * s
        val n2x = -d2.y * halfWidth * s
        val n2y = d2.x * halfWidth * s
        val ax = here.x + n1x
        val ay = here.y + n1y
        val bx = here.x + n2x
        val by = here.y + n2y
        val c = mb.vertex(here.x, here.y)
        val a = mb.vertex(ax, ay, n1x, n1y)
        val b = mb.vertex(bx, by, n2x, n2y)
        if (join == LineJoin.MITER) {
            val mx = n1x + n2x
            val my = n1y + n2y
            val len = hypot(mx, my)
            if (len > 1e-12) {
                // The mitre reaches halfWidth / cos(half the corner), which is the limit SVG caps.
                val cosHalf = (mx * n1x + my * n1y) / (len * halfWidth)
                if (cosHalf > 1e-6 && 1.0 / cosHalf <= miterLimit) {
                    val reach = halfWidth / cosHalf
                    val px = here.x + mx / len * reach
                    val py = here.y + my / len * reach
                    val p = mb.vertex(px, py, mx / len * reach, my / len * reach)
                    mb.triangle(c, a, p)
                    mb.triangle(c, p, b)
                    return
                }
            }
        }
        mb.triangle(c, a, b)
    }

    private fun capAt(mb: MeshBuilder, from: Pt, end: Pt, halfWidth: Double, cap: LineCap, tolerance: Double) {
        when (cap) {
            LineCap.BUTT -> Unit
            LineCap.ROUND -> mb.circle(end.x, end.y, halfWidth, tolerance)
            LineCap.SQUARE -> {
                val d = unit(from, end) ?: return
                segmentQuad(
                    mb, end.x, end.y, end.x + d.x * halfWidth, end.y + d.y * halfWidth,
                    -d.y * halfWidth, d.x * halfWidth,
                )
            }
        }
    }

    /**
     * One segment's ribbon, from ([ax], [ay]) to ([bx], [by]) offset both ways by the normal
     * ([nx], [ny]). Each vertex carries its own displacement from the spine, which is what lets the
     * shader keep a hair-thin line visible instead of letting it shimmer.
     */
    private fun segmentQuad(
        mb: MeshBuilder,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        nx: Double,
        ny: Double,
    ) {
        val v0 = mb.vertex(ax + nx, ay + ny, nx, ny)
        val v1 = mb.vertex(ax - nx, ay - ny, -nx, -ny)
        val v2 = mb.vertex(bx + nx, by + ny, nx, ny)
        val v3 = mb.vertex(bx - nx, by - ny, -nx, -ny)
        mb.triangle(v0, v1, v3)
        mb.triangle(v0, v3, v2)
    }

    private fun unit(a: Pt, b: Pt): Pt? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        return if (len < 1e-12) null else Pt(dx / len, dy / len)
    }

    private fun same(a: Pt, b: Pt) = abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9

    /** A dash pattern short against a long path can produce a great many runs; this bounds it. */
    private const val MAX_DASH_RUNS = 20000
}
