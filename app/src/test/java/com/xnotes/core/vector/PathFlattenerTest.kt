package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PathFlattenerTest {

    private fun contour(vararg segs: VectorSeg, start: Pt = Pt(0.0, 0.0), closed: Boolean = false) =
        VectorContour(start, segs.toList(), closed)

    @Test
    fun `a polyline comes back unchanged`() {
        val c = contour(VectorSeg.Line(Pt(10.0, 0.0)), VectorSeg.Line(Pt(10.0, 10.0)))
        val pts = PathFlattener.flatten(c, 0.01)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0)), pts)
    }

    @Test
    fun `a straight cubic needs no interior points`() {
        val c = contour(VectorSeg.Cubic(Pt(3.0, 0.0), Pt(6.0, 0.0), Pt(9.0, 0.0)))
        assertEquals(2, PathFlattener.flatten(c, 0.01).size)
    }

    @Test
    fun `a curved cubic stays within tolerance of the true curve`() {
        val p0 = Pt(0.0, 0.0)
        val c1 = Pt(0.0, 100.0)
        val c2 = Pt(100.0, 100.0)
        val p3 = Pt(100.0, 0.0)
        val tolerance = 0.05
        val pts = PathFlattener.flatten(contour(VectorSeg.Cubic(c1, c2, p3), start = p0), tolerance)
        assertTrue(pts.size > 4)
        // Sample the true curve densely; every sample must sit near the polyline.
        for (i in 0..400) {
            val t = i / 400.0
            val on = cubicAt(p0, c1, c2, p3, t)
            assertTrue(distanceToPolyline(pts, on) <= tolerance * 2.0)
        }
    }

    @Test
    fun `a tighter tolerance costs more points`() {
        val c = contour(VectorSeg.Cubic(Pt(0.0, 100.0), Pt(100.0, 100.0), Pt(100.0, 0.0)))
        val coarse = PathFlattener.flatten(c, 1.0).size
        val fine = PathFlattener.flatten(c, 0.01).size
        assertTrue("$fine should exceed $coarse", fine > coarse)
    }

    @Test
    fun `a closed ring does not repeat its first point`() {
        val c = contour(
            VectorSeg.Line(Pt(10.0, 0.0)),
            VectorSeg.Line(Pt(10.0, 10.0)),
            VectorSeg.Line(Pt(0.0, 0.0)),
            closed = true,
        )
        val pts = PathFlattener.flatten(c, 0.01)
        assertEquals(3, pts.size)
    }

    @Test
    fun `a loop that starts and ends together is not collapsed`() {
        val c = contour(VectorSeg.Cubic(Pt(60.0, -40.0), Pt(-60.0, -40.0), Pt(0.0, 0.0)))
        assertTrue(PathFlattener.flatten(c, 0.05).size > 4)
    }

    private fun cubicAt(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Double): Pt {
        val u = 1.0 - t
        val a = u * u * u
        val b = 3.0 * u * u * t
        val c = 3.0 * u * t * t
        val d = t * t * t
        return Pt(
            a * p0.x + b * p1.x + c * p2.x + d * p3.x,
            a * p0.y + b * p1.y + c * p2.y + d * p3.y,
        )
    }

    private fun distanceToPolyline(pts: List<Pt>, p: Pt): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) best = minOf(best, distanceToSegment(p, pts[i], pts[i + 1]))
        return best
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    @Test
    fun `an affine composes in the order it is applied`() {
        val scale = Affine.scale(2.0, 3.0)
        val move = Affine.translate(5.0, 7.0)
        // move runs first: (5,7) -> (10,14) -> (20,42).
        assertEquals(20.0, scale.times(move).map(Pt(5.0, 7.0)).x, 1e-9)
        assertEquals(42.0, scale.times(move).map(Pt(5.0, 7.0)).y, 1e-9)
        assertEquals(6.0, Affine.scale(2.0, 18.0).lengthScale(), 1e-9)
        assertTrue(abs(Affine.IDENTITY.map(Pt(3.0, 4.0)).x - 3.0) < 1e-12)
    }
}
