package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.WetRibbon
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The infinite canvas draws a wet stroke as runs: the settled ones meshed once each, the moving
 * one every frame. That is only sound if the runs together cover exactly what one mesh of the
 * whole stroke covers — a hole at a seam would show as the ink breaking behind the nib.
 *
 * Coverage is checked geometrically rather than by comparing triangle lists, since a run puts its
 * own round ends where the whole stroke would have had none, so the two meshes are deliberately
 * not identical.
 */
class WetRunMeshTest {

    private fun liveStroke(tool: Tool, count: Int): Stroke {
        val s = Stroke(tool, ToolDefaults.configFor(tool))
        s.finished = false
        for (i in 0 until count) {
            val u = i * 0.08
            s.addSample(
                Sample(
                    120.0 + u * 22.0 + sin(u * 3.3) * 12.0,
                    140.0 + cos(u * 1.8) * 36.0,
                    0.35 + 0.45 * (0.5 + 0.5 * sin(u * 2.6)),
                    i * 6.0,
                ),
            )
        }
        return s
    }

    /** The runs a wet stroke of [n] points is meshed in, overlapping by a point at each seam. */
    private fun runsOf(n: Int, step: Int): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var at = 0
        while (at < n - 1) {
            val from = (at - 1).coerceAtLeast(0)
            val to = minOf(at + step, n)
            runs += from to (to - from)
            at = to
        }
        return runs
    }

    private fun meshRuns(stroke: Stroke, ribbon: WetRibbon, step: Int): List<MeshData> {
        var arc = 0.0
        val out = ArrayList<MeshData>()
        for ((from, count) in runsOf(ribbon.pointCount, step)) {
            ItemMesher.meshRun(stroke, ribbon, from, count, arc)?.let { out += it.mesh }
            for (k in from + 1 until from + count) {
                arc += hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
            }
        }
        return out
    }

    private fun inTriangle(mesh: MeshData, t: Int, px: Double, py: Double): Boolean {
        val ia = mesh.indices[3 * t]
        val ib = mesh.indices[3 * t + 1]
        val ic = mesh.indices[3 * t + 2]
        val ax = mesh.positions[2 * ia]
        val ay = mesh.positions[2 * ia + 1]
        val bx = mesh.positions[2 * ib]
        val by = mesh.positions[2 * ib + 1]
        val cx = mesh.positions[2 * ic]
        val cy = mesh.positions[2 * ic + 1]
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun covers(meshes: List<MeshData>, p: Pt): Boolean {
        for (mesh in meshes) {
            for (t in 0 until mesh.triangleCount) if (inTriangle(mesh, t, p.x, p.y)) return true
        }
        return false
    }

    @Test fun awholeStrokeMeshedAsRunsHasNoHoleAtAnySeam() {
        for (tool in listOf(Tool.PEN, Tool.CALLIGRAPHY, Tool.SPEED)) {
            val stroke = liveStroke(tool, 300)
            val ribbon = stroke.wetRibbon!!
            val whole = listOf(ItemMesher.meshRun(stroke, ribbon, 0, ribbon.pointCount, 0.0)!!.mesh)
            // Runs deliberately short, so the test crosses many more seams than a real stroke does.
            val runs = meshRuns(stroke, ribbon, step = 11)
            var probed = 0
            for (i in 0 until ribbon.pointCount) {
                // The centreline, and a point most of the way out to each rail.
                val probes = listOf(
                    Pt(ribbon.cx(i), ribbon.cy(i)),
                    Pt(
                        ribbon.cx(i) + (ribbon.leftX(i) - ribbon.cx(i)) * 0.8,
                        ribbon.cy(i) + (ribbon.leftY(i) - ribbon.cy(i)) * 0.8,
                    ),
                    Pt(
                        ribbon.cx(i) + (ribbon.rightX(i) - ribbon.cx(i)) * 0.8,
                        ribbon.cy(i) + (ribbon.rightY(i) - ribbon.cy(i)) * 0.8,
                    ),
                )
                for (p in probes) {
                    if (!covers(whole, p)) continue // the whole mesh does not claim it either
                    probed++
                    assertTrue("$tool: the runs left a hole at point $i, $p", covers(runs, p))
                }
            }
            assertTrue("$tool: nothing was actually probed", probed > 500)
        }
    }

    @Test fun meshingAWholeRunEqualsMeshingTheWholeRibbon() {
        val stroke = liveStroke(Tool.PEN, 120)
        val ribbon = stroke.wetRibbon!!
        val g = ribbon.geometry()
        val viaRun = StrokeTessellator.tessellate(ribbon, 0, ribbon.pointCount, StrokeTessellator.DEFAULT_TOLERANCE)
        val viaGeometry = StrokeTessellator.tessellate(g, StrokeTessellator.DEFAULT_TOLERANCE)
        assertEquals(viaGeometry.vertexCount, viaRun.vertexCount)
        assertEquals(viaGeometry.indices.size, viaRun.indices.size)
        for (i in viaGeometry.positions.indices) {
            assertEquals("positions[$i]", viaGeometry.positions[i], viaRun.positions[i], 0.0)
        }
    }

    @Test fun everyRunOfEveryPenProducesTriangles() {
        for (tool in listOf(Tool.PEN, Tool.CALLIGRAPHY, Tool.SPEED, Tool.DASHED)) {
            val stroke = liveStroke(tool, 300)
            val ribbon = stroke.wetRibbon!!
            val part = ItemMesher.meshRun(stroke, ribbon, 100, 60, 0.0)
            assertNotNull("$tool produced no run mesh", part)
            assertTrue("$tool produced an empty run mesh", part!!.mesh.triangleCount > 0)
        }
    }

    @Test fun theDashPatternIsTheSameWhetherItIsCutIntoRunsOrNot() {
        val path = (0 until 60).map { Pt(20.0 + it * 4.0, 50.0 + sin(it * 0.2) * 12.0) }
        val on = 10.0
        val gap = 8.0
        val whole = MeshBuilder.dashRuns(path, on, gap, closed = false)

        // Cut the path in two and dash the second half from where the first left off.
        val cut = 25
        val head = path.subList(0, cut + 1)
        val tail = path.subList(cut, path.size)
        var arc = 0.0
        for (i in 1..cut) arc += hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
        val pieces = MeshBuilder.dashRuns(head, on, gap, closed = false) +
            MeshBuilder.dashRuns(tail, on, gap, closed = false, phase = arc)

        // The same amount of line is painted either way, give or take the dash the cut fell inside.
        assertEquals(inkLength(whole), inkLength(pieces), 1e-6)
    }

    @Test fun aDashPhaseOfAWholePeriodIsNoPhaseAtAll() {
        val path = (0 until 40).map { Pt(10.0 + it * 5.0, 30.0) }
        val plain = MeshBuilder.dashRuns(path, 10.0, 8.0, closed = false)
        val wrapped = MeshBuilder.dashRuns(path, 10.0, 8.0, closed = false, phase = 18.0 * 3)
        assertEquals(inkLength(plain), inkLength(wrapped), 1e-9)
        assertEquals(plain.size, wrapped.size)
    }

    @Test fun aPhaseInsideTheGapStartsTheLineLate() {
        val path = (0 until 40).map { Pt(0.0 + it * 5.0, 0.0) }
        // 14 units into an on-10/off-8 pattern is 4 units into the gap, so the first dash begins
        // 4 units along rather than at the head.
        val runs = MeshBuilder.dashRuns(path, 10.0, 8.0, closed = false, phase = 14.0)
        assertTrue(runs.isNotEmpty())
        assertEquals(4.0, runs.first().first().x, 1e-9)
    }

    private fun inkLength(runs: List<List<Pt>>): Double {
        var total = 0.0
        for (run in runs) for (i in 1 until run.size) {
            total += hypot(run[i].x - run[i - 1].x, run[i].y - run[i - 1].y)
        }
        return total
    }

    @Test fun theTailAStrokeRemeshesEachFrameStaysBounded() {
        // What the whole split is for: the run rebuilt per frame must not grow with the stroke.
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        var meshed = 0
        var worst = 0
        for (i in 0 until 1200) {
            val u = i * 0.05
            stroke.addSample(Sample(50.0 + u * 18.0, 90.0 + sin(u) * 40.0, 0.6, i * 5.0))
            val ribbon = stroke.wetRibbon!!
            if (ribbon.settledCount - meshed >= 96) meshed = ribbon.settledCount
            worst = maxOf(worst, ribbon.pointCount - (meshed - 1).coerceAtLeast(0))
        }
        assertTrue("the per-frame tail reached $worst points", worst <= 96 + 8)
        assertTrue("nothing was ever handed over as settled", meshed > 1000)
    }

    @Test fun theSettledRunsAreFewEnoughToStayOneDrawCall() {
        // Each settled run is a buffer slice; too many and the saving goes back out as draw calls.
        val runs = runsOf(4000, 96)
        assertTrue("a 4000-point stroke left ${runs.size} runs", runs.size <= 48)
        // And they tile the stroke exactly: every segment belongs to one run.
        var covered = 0
        for ((from, count) in runs) covered += count - 1
        assertEquals(4000 - 1, covered)
        assertTrue(abs(runs.first().first) == 0)
    }
}
