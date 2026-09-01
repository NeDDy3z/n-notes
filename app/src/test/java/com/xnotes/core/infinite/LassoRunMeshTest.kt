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
 * at the end of a long loop as at its start. It is dashed, and splitting a dashed line into pieces
 * restarts its rhythm at every seam unless each run is handed the arc the runs before it spent, so
 * what these check is that the pieces draw the same dashes an unbroken line would.
 *
 * Coverage is checked as a point-in-triangles test rather than by comparing meshes, since a run
 * puts round ends where the whole line would have had none.
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

    /** Points sampled evenly along the path, so two meshes can be compared where they matter. */
    private fun walk(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 0 until points.size - 1) {
            for (k in 0 until SAMPLES_PER_SEGMENT) {
                val f = (k + 0.5) / SAMPLES_PER_SEGMENT
                out.add(
                    Pt(
                        points[i].x + (points[i + 1].x - points[i].x) * f,
                        points[i].y + (points[i + 1].y - points[i].y) * f,
                    ),
                )
            }
        }
        return out
    }

    /** How many sampled points one mesh paints and the other does not, either way round. */
    private fun disagreements(a: List<MeshPart>, b: List<MeshPart>, at: List<Pt>): Int =
        at.count { covers(a, it) != covers(b, it) }

    private fun coveredFraction(parts: List<MeshPart>, at: List<Pt>): Double =
        if (at.isEmpty()) 0.0 else at.count { covers(parts, it) }.toDouble() / at.size

    private fun arcThrough(points: List<Pt>, from: Int, to: Int): Double {
        var arc = 0.0
        for (k in from + 1..to) arc += points[k].distanceTo(points[k - 1])
        return arc
    }

    private fun split(points: List<Pt>, settled: Int, phase: Double): List<MeshPart> =
        OverlayTessellator.lassoRun(points, 0, settled, 1.0, accent, tolerance, 0.0) +
            OverlayTessellator.lassoTail(points, settled - 1, 1.0, accent, tolerance, phase)

    @Test
    fun `a run and its tail draw the dashes an unbroken line would`() {
        val points = loop(60)
        val settled = 33
        val at = walk(points)
        val whole = OverlayTessellator.lasso(points, 1.0, accent, tolerance)
        val joined = split(points, settled, arcThrough(points, 0, settled - 1))
        // The tail starts with a round cap the unbroken line has no reason to draw, so the seam is
        // allowed to differ by a little; a restarted rhythm differs by far more (below).
        assertTrue(
            "the dashes moved at the seam",
            disagreements(joined, whole, at) <= at.size / 25,
        )
    }

    @Test
    fun `without the phase the rhythm restarts at the seam`() {
        val points = loop(60)
        val settled = 33
        val at = walk(points)
        val whole = OverlayTessellator.lasso(points, 1.0, accent, tolerance)
        val joined = split(points, settled, arcThrough(points, 0, settled - 1))
        val unphased = split(points, settled, 0.0)
        assertTrue(
            "carrying the phase made no difference, so the test above proves nothing",
            disagreements(unphased, whole, at) > disagreements(joined, whole, at) * 3,
        )
    }

    @Test
    fun `the line is dashed, not solid`() {
        val points = loop(60)
        val fraction = coveredFraction(OverlayTessellator.lasso(points, 1.0, accent, tolerance), walk(points))
        assertTrue("nothing was drawn", fraction > 0.2)
        assertTrue("the line came out solid at $fraction", fraction < 0.9)
    }

    @Test
    fun `the loop is left open`() {
        val points = loop(40)
        val whole = OverlayTessellator.lasso(points, 1.0, accent, tolerance)
        // The chord back to the start is an edge the hand never drew, so nothing may be on it.
        val last = points[points.size - 1]
        val first = points[0]
        for (k in 1 until 8) {
            val f = k / 8.0
            val on = Pt(last.x + (first.x - last.x) * f, last.y + (first.y - last.y) * f)
            assertTrue("the loop was closed back to its start", !covers(whole, on))
        }
    }

    @Test
    fun `a run covers only its own range`() {
        val points = loop(40)
        val run = OverlayTessellator.lassoRun(points, 0, 10, 1.0, accent, tolerance, 0.0)
        assertTrue("a run reached past its range", !covers(run, points[20]))
        assertTrue("a run reached past its range", !covers(run, points[30]))
    }

    @Test
    fun `a range of fewer than two points meshes nothing`() {
        val points = loop(10)
        assertTrue(OverlayTessellator.lassoRun(points, 3, 1, 1.0, accent, tolerance, 0.0).isEmpty())
        assertTrue(OverlayTessellator.lassoRun(points, 3, 0, 1.0, accent, tolerance, 0.0).isEmpty())
    }

    @Test
    fun `an out of range request meshes nothing rather than throwing`() {
        val points = loop(10)
        assertEquals(
            emptyList<MeshPart>(),
            OverlayTessellator.lassoRun(points, 8, 5, 1.0, accent, tolerance, 0.0),
        )
        assertEquals(emptyList<MeshPart>(), OverlayTessellator.lassoTail(points, 10, 1.0, accent, tolerance, 0.0))
        assertEquals(emptyList<MeshPart>(), OverlayTessellator.lassoTail(points, -1, 1.0, accent, tolerance, 0.0))
    }

    private companion object {
        const val SAMPLES_PER_SEGMENT = 12
    }
}
