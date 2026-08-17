package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt

/**
 * Clips geometry to a convex polygon, which is what a rectangular `clip-path` becomes once the
 * item it belongs to has been placed and possibly turned.
 *
 * A rectangle is the clip worth getting exactly right: it is what an exporter writes to hold its
 * artboard, and it is convex, so Sutherland-Hodgman handles it in a few lines and with no polygon
 * boolean library behind it. A genuinely arbitrary clip path is not built here at all; the reader
 * names it and leaves the content unclipped, since hiding it would lose far more.
 */
internal object PolygonClip {

    /** [ring] clipped to the convex polygon [clip], both as closed rings. */
    fun polygon(ring: List<Pt>, clip: List<Pt>): List<Pt> {
        if (ring.size < 3 || clip.size < 3) return emptyList()
        var current: List<Pt> = ring
        for (i in clip.indices) {
            if (current.isEmpty()) return emptyList()
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val next = ArrayList<Pt>(current.size + 4)
            for (k in current.indices) {
                val p = current[k]
                val q = current[(k + 1) % current.size]
                val fp = side(a, b, p)
                val fq = side(a, b, q)
                if (fp >= 0.0) next.add(p)
                if ((fp >= 0.0) != (fq >= 0.0)) next.add(cut(p, q, fp, fq))
            }
            current = next
        }
        return current
    }

    /** [line] cut into the runs of it that lie inside the convex polygon [clip]. */
    fun polyline(line: List<Pt>, closed: Boolean, clip: List<Pt>): List<List<Pt>> {
        if (line.size < 2 || clip.size < 3) return emptyList()
        val path = if (closed) line + line.first() else line
        val runs = ArrayList<List<Pt>>()
        var current = ArrayList<Pt>()
        var openAtEnd = false
        for (i in 0 until path.size - 1) {
            val span = segment(path[i], path[i + 1], clip)
            if (span == null) {
                if (current.size >= 2) runs.add(current)
                current = ArrayList()
                openAtEnd = false
                continue
            }
            val (t0, t1) = span
            val a = lerp(path[i], path[i + 1], t0)
            val b = lerp(path[i], path[i + 1], t1)
            // A run continues only where this segment starts exactly where the last one stopped.
            if (!openAtEnd || t0 > 1e-12) {
                if (current.size >= 2) runs.add(current)
                current = ArrayList()
                current.add(a)
            }
            current.add(b)
            openAtEnd = t1 >= 1.0 - 1e-12
        }
        if (current.size >= 2) runs.add(current)
        return runs
    }

    /** The part of the segment inside the clip, as a parameter range, or null when it misses. */
    private fun segment(p0: Pt, p1: Pt, clip: List<Pt>): Pair<Double, Double>? {
        var enter = 0.0
        var leave = 1.0
        for (i in clip.indices) {
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val f0 = side(a, b, p0)
            val f1 = side(a, b, p1)
            val d = f1 - f0
            if (kotlin.math.abs(d) < 1e-15) {
                if (f0 < 0.0) return null
                continue
            }
            val t = -f0 / d
            if (d > 0.0) enter = maxOf(enter, t) else leave = minOf(leave, t)
            if (enter > leave) return null
        }
        return if (enter > leave) null else enter to leave
    }

    /** Which side of the directed edge a→b the point sits on; non-negative is inside. */
    private fun side(a: Pt, b: Pt, p: Pt): Double =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    private fun cut(p: Pt, q: Pt, fp: Double, fq: Double): Pt {
        val d = fp - fq
        val t = if (kotlin.math.abs(d) < 1e-15) 0.0 else fp / d
        return lerp(p, q, t)
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** [corners] wound so that [side] reads non-negative for the inside. */
    fun wound(corners: List<Pt>): List<Pt> =
        if (Triangulator.signedArea(corners) >= 0.0) corners else corners.asReversed().toList()
}
