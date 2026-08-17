package com.xnotes.core.vector

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.FillRule

/**
 * Turns a filled path's rings into triangles, holes and all.
 *
 * Triangles are what makes a vector image free to draw: they go into the canvas's shared vertex
 * buffer alongside the ink and cost nothing per frame beyond their own vertices. The alternative,
 * stencilling each path and covering it, costs a pair of draw calls per path, which a
 * thousand-path diagram cannot afford. So this is the default and the stencil is the escape.
 *
 * Rings are classified by containment first, because an SVG path is one path whether it is a
 * letter O, two letter Os, or a map with islands in its lakes. Each ring's nesting depth decides
 * whether it is solid or a hole, then each solid ring is bridged to its own holes and ear-clipped.
 *
 * Returns null when it will not do a good job — too many vertices for the quadratic ear clip, or
 * an outline so tangled no ear is left. The caller falls back to stencil-and-cover, which is exact
 * for any outline at all.
 */
object Triangulator {

    /** One fill turned into triangles. [indices] are triples into [points]. */
    class Mesh(val points: List<Pt>, val indices: IntArray)

    /** Past this an ear clip's quadratic inner loop stops being worth it. */
    const val MAX_RING_VERTICES = 2048

    fun triangulate(rings: List<List<Pt>>, rule: FillRule): Mesh? {
        val usable = rings.filter { it.size >= 3 }.map { Ring(it) }
        if (usable.isEmpty()) return null
        val groups = group(usable, rule)
        if (groups.isEmpty()) return null
        val points = ArrayList<Pt>()
        val indices = IntBuilder()
        for (g in groups) {
            val merged = bridge(g.outer, g.holes) ?: return null
            if (merged.size > MAX_RING_VERTICES) return null
            val base = points.size
            points.addAll(merged)
            if (!earClip(merged, base, indices)) return null
        }
        if (indices.isEmpty) return null
        return Mesh(points, indices.build())
    }

    /** A ring with its shoelace area and box cached, since classification asks for them a lot. */
    private class Ring(val points: List<Pt>) {
        val area: Double = signedArea(points)
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
    }

    private class Group(val outer: List<Pt>, val holes: List<List<Pt>>)

    /**
     * Sort the rings into solids and their holes.
     *
     * Depth is how many other rings a ring sits inside. Under the even-odd rule that alone decides
     * it: odd depth is a hole. Under non-zero a nested ring is only a hole when it runs the other
     * way round, which is how every real drawing tool writes one, and a same-way ring at odd depth
     * stays solid.
     */
    private fun group(rings: List<Ring>, rule: FillRule): List<Group> {
        val n = rings.size
        val parent = IntArray(n) { -1 }
        val depth = IntArray(n)
        for (i in 0 until n) {
            var best = -1
            for (j in 0 until n) {
                if (i == j || !encloses(rings[j], rings[i])) continue
                depth[i]++
                if (best < 0 || kotlin.math.abs(rings[j].area) < kotlin.math.abs(rings[best].area)) best = j
            }
            parent[i] = best
        }
        val isHole = BooleanArray(n) { i ->
            if (depth[i] % 2 == 0) {
                false
            } else if (rule == FillRule.EVEN_ODD) {
                true
            } else {
                val p = parent[i]
                p >= 0 && (rings[i].area > 0.0) != (rings[p].area > 0.0)
            }
        }
        val out = ArrayList<Group>()
        for (i in 0 until n) {
            if (isHole[i]) continue
            val holes = ArrayList<List<Pt>>()
            for (j in 0 until n) {
                if (isHole[j] && parent[j] == i) holes.add(wound(rings[j].points, positive = false))
            }
            out.add(Group(wound(rings[i].points, positive = true), holes))
        }
        return out
    }

    /** Whether [outer] contains [inner], by bounding box first and then by a vertex of [inner]. */
    private fun encloses(outer: Ring, inner: Ring): Boolean {
        if (inner.left < outer.left || inner.right > outer.right) return false
        if (inner.top < outer.top || inner.bottom > outer.bottom) return false
        return Geometry.pointInPolygon(outer.points, inner.points[0])
    }

    private fun wound(points: List<Pt>, positive: Boolean): List<Pt> =
        if ((signedArea(points) >= 0.0) == positive) points else points.asReversed().toList()

    /**
     * Splice every hole into the outer ring, so what comes out is one simple ring the ear clip can
     * chew. Each hole is joined by a two-sided bridge from its rightmost vertex to a vertex of the
     * outer ring that can see it, which is the standard construction.
     *
     * Holes are taken rightmost-first, so a bridge already laid is part of the ring the next hole
     * searches, and two bridges cannot cross.
     */
    private fun bridge(outer: List<Pt>, holes: List<List<Pt>>): List<Pt>? {
        if (holes.isEmpty()) return outer
        var merged = ArrayList(outer)
        for (hole in holes.sortedByDescending { h -> h.maxOf { it.x } }) {
            merged = spliceHole(merged, hole) ?: return null
        }
        return merged
    }

    private fun spliceHole(outer: ArrayList<Pt>, hole: List<Pt>): ArrayList<Pt>? {
        val hi = indexOfMaxX(hole)
        val h = hole[hi]
        val mi = visibleOuterVertex(outer, h) ?: return null
        val out = ArrayList<Pt>(outer.size + hole.size + 2)
        for (i in 0..mi) out.add(outer[i])
        for (k in hole.indices) out.add(hole[(hi + k) % hole.size])
        out.add(h)
        out.add(outer[mi])
        for (i in mi + 1 until outer.size) out.add(outer[i])
        return out
    }

    /**
     * A vertex of [outer] that the hole's rightmost point [h] can be joined to without the bridge
     * crossing anything: cast a ray right from [h], take the edge it first hits, then keep the
     * endpoint that can actually see [h] once the reflex vertices in the way are accounted for.
     */
    private fun visibleOuterVertex(outer: List<Pt>, h: Pt): Int? {
        var hitX = Double.MAX_VALUE
        var edge = -1
        for (i in outer.indices) {
            val a = outer[i]
            val b = outer[(i + 1) % outer.size]
            // Half-open crossing test, so a vertex shared by two edges is only counted once.
            if ((a.y > h.y) == (b.y > h.y)) continue
            val x = a.x + (h.y - a.y) / (b.y - a.y) * (b.x - a.x)
            if (x < h.x || x >= hitX) continue
            hitX = x
            edge = if (a.x > b.x) i else (i + 1) % outer.size
        }
        if (edge < 0) return null
        val m = outer[edge]
        // A reflex vertex inside the triangle (h, hit, m) would be crossed by the direct bridge, so
        // prefer the one nearest the ray. Same tie-break the reference implementation uses.
        var best = edge
        var bestTan = Double.MAX_VALUE
        val hit = Pt(hitX, h.y)
        for (i in outer.indices) {
            if (i == edge) continue
            val p = outer[i]
            if (p.x <= h.x || p.x > hitX) continue
            if (!inTriangle(p, h, hit, m)) continue
            if (!isReflex(outer, i)) continue
            val tan = kotlin.math.abs(p.y - h.y) / (p.x - h.x)
            if (tan < bestTan) {
                bestTan = tan
                best = i
            }
        }
        return best
    }

    private fun isReflex(ring: List<Pt>, i: Int): Boolean {
        val n = ring.size
        val a = ring[(i + n - 1) % n]
        val b = ring[i]
        val c = ring[(i + 1) % n]
        return cross(a, b, c) <= 0.0
    }

    private fun indexOfMaxX(ring: List<Pt>): Int {
        var best = 0
        for (i in ring.indices) if (ring[i].x > ring[best].x) best = i
        return best
    }

    /**
     * Classic ear clipping over a doubly linked ring. Only reflex vertices can sit inside a
     * candidate ear, so the inner loop skips the convex ones, which is what keeps the quadratic
     * worst case off a normal outline.
     */
    private fun earClip(ring: List<Pt>, base: Int, out: IntBuilder): Boolean {
        val n = ring.size
        if (n < 3) return false
        val prev = IntArray(n) { (it + n - 1) % n }
        val next = IntArray(n) { (it + 1) % n }
        var remaining = n
        var cur = 0
        var misses = 0
        while (remaining > 3) {
            if (isEar(ring, prev, next, cur, remaining)) {
                out.add(base + prev[cur], base + cur, base + next[cur])
                next[prev[cur]] = next[cur]
                prev[next[cur]] = prev[cur]
                cur = next[cur]
                remaining--
                misses = 0
            } else {
                cur = next[cur]
                misses++
                // A full lap with no ear means the outline crosses itself; the stencil handles that.
                if (misses > remaining) return false
            }
        }
        out.add(base + prev[cur], base + cur, base + next[cur])
        return true
    }

    private fun isEar(ring: List<Pt>, prev: IntArray, next: IntArray, i: Int, remaining: Int): Boolean {
        val a = ring[prev[i]]
        val b = ring[i]
        val c = ring[next[i]]
        if (cross(a, b, c) <= 0.0) return false
        var k = next[next[i]]
        var left = remaining - 3
        while (left-- > 0) {
            val p = ring[k]
            if (cross(ring[prev[k]], p, ring[next[k]]) <= 0.0 && inTriangle(p, a, b, c)) return false
            k = next[k]
        }
        return true
    }

    private fun cross(a: Pt, b: Pt, c: Pt): Double = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun inTriangle(p: Pt, a: Pt, b: Pt, c: Pt): Boolean {
        val d1 = cross(a, b, p)
        val d2 = cross(b, c, p)
        val d3 = cross(c, a, p)
        val neg = d1 < 0.0 || d2 < 0.0 || d3 < 0.0
        val pos = d1 > 0.0 || d2 > 0.0 || d3 > 0.0
        return !(neg && pos)
    }

    fun signedArea(points: List<Pt>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    /** Growable int triples, so a triangulation allocates once rather than per triangle. */
    private class IntBuilder {
        private var data = IntArray(96)
        private var count = 0

        val isEmpty: Boolean get() = count == 0

        fun add(a: Int, b: Int, c: Int) {
            if (count + 3 > data.size) data = data.copyOf(data.size * 2)
            data[count] = a
            data[count + 1] = b
            data[count + 2] = c
            count += 3
        }

        fun build(): IntArray = data.copyOf(count)
    }
}
