package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lasso is published as runs and a moving tail, like a wet stroke, so a sample costs the same
 * at the end of a long loop as at its start. That only holds if the pieces together cover what one
 * mesh of the whole loop covers: a hole at a seam would show as the marquee breaking behind the
 * pen, and a missing chord would leave the loop visibly open.
 *
 * Coverage is checked as a point-in-triangles test rather than by comparing meshes, since a run
 * puts round ends where the whole loop would have had a corner.
 */
class LassoRunMeshTest {

    private val accent = Rgba(80, 140, 255)
    private val tolerance = StrokeTessellator.DEFAULT_TOLERANCE

    private fun loop(count: Int): List<Pt> = (0 until count).map { i ->
        val u = i * 0.13
        Pt(400.0 + cos(u) * 180.0 + sin(u * 2.7) * 14.0, 300.0 + sin(u) * 140.0)
    }

    /** Whether [p] falls inside any triangle of [parts]. */
    private fun covers(parts: List<MeshPart>, p: Pt): Boolean {
        for (part in parts) {
            val pos = part.mesh.positions
            val idx = part.mesh.indices
            var i = 0
            while (i + 2 < idx.size) {
                val a = idx[i] * 2
                val b = idx[i + 1] * 2
                val c = idx[i + 2] * 2
                if (inside(pos[a], pos[a + 1], pos[b], pos[b + 1], pos[c], pos[c + 1], p)) return true
                i += 3
            }
        }
        return false
    }

    private fun inside(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, p: Pt,
    ): Boolean {
        val d1 = (p.x - bx) * (ay - by) - (ax - bx) * (p.y - by)
        val d2 = (p.x - cx) * (by - cy) - (bx - cx) * (p.y - cy)
        val d3 = (p.x - ax) * (cy - ay) - (cx - ax) * (p.y - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    @Test
    fun `runs and tail together cover every vertex of the loop`() {
        val points = loop(60)
        val settled = 33
        val parts = OverlayTessellator.lassoRun(points, 0, settled, 1.0, accent, tolerance) +
            OverlayTessellator.lassoTail(points, settled - 1, 1.0, accent, tolerance)
        for (p in points) {
            assertTrue("vertex $p left uncovered", covers(parts, p))
        }
    }

    @Test
    fun `the tail carries the chord back to the start`() {
        val points = loop(40)
        val tail = OverlayTessellator.lassoTail(points, 30, 1.0, accent, tolerance)
        // The midpoint of the closing chord belongs to no segment of the open run.
        val last = points[points.size - 1]
        val first = points[0]
        val mid = Pt((last.x + first.x) / 2.0, (last.y + first.y) / 2.0)
        assertTrue("the loop was left open", covers(tail, mid))
    }

    @Test
    fun `a run covers only its own range`() {
        val points = loop(40)
        val run = OverlayTessellator.lassoRun(points, 0, 10, 1.0, accent, tolerance)
        assertTrue(covers(run, points[0]))
        assertTrue(covers(run, points[9]))
        assertTrue("a run reached past its range", !covers(run, points[20]))
    }

    @Test
    fun `a range of fewer than two points meshes nothing`() {
        val points = loop(10)
        assertTrue(OverlayTessellator.lassoRun(points, 3, 1, 1.0, accent, tolerance).isEmpty())
        assertTrue(OverlayTessellator.lassoRun(points, 3, 0, 1.0, accent, tolerance).isEmpty())
    }

    @Test
    fun `an out of range request meshes nothing rather than throwing`() {
        val points = loop(10)
        assertEquals(emptyList<MeshPart>(), OverlayTessellator.lassoRun(points, 8, 5, 1.0, accent, tolerance))
        assertEquals(emptyList<MeshPart>(), OverlayTessellator.lassoTail(points, 10, 1.0, accent, tolerance))
        assertEquals(emptyList<MeshPart>(), OverlayTessellator.lassoTail(points, -1, 1.0, accent, tolerance))
    }
}
