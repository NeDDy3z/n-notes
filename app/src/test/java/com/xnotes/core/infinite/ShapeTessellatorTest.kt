package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTessellatorTest {

    private fun shape(
        kind: ShapeKind,
        fill: Rgba? = null,
        dashed: Boolean = false,
    ): ShapeItem = ShapeItem(
        kind, Pt(0.0, 0.0), Pt(100.0, 60.0), Rgba(10, 20, 30, 255), 4.0, fill,
        dashed = dashed, dashLength = 10.0, dashGap = 6.0,
    )

    private fun covers(m: MeshData, px: Double, py: Double): Boolean {
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]
            val b = m.indices[3 * t + 1]
            val c = m.indices[3 * t + 2]
            if (inTriangle(
                    px, py,
                    m.positions[2 * a], m.positions[2 * a + 1],
                    m.positions[2 * b], m.positions[2 * b + 1],
                    m.positions[2 * c], m.positions[2 * c + 1],
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun inTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Boolean {
        fun side(x1: Double, y1: Double, x2: Double, y2: Double) =
            (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
        val d1 = side(ax, ay, bx, by)
        val d2 = side(bx, by, cx, cy)
        val d3 = side(cx, cy, ax, ay)
        return !((d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12) && (d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12))
    }

    private fun area(m: MeshData): Double {
        var sum = 0.0
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]
            val b = m.indices[3 * t + 1]
            val c = m.indices[3 * t + 2]
            sum += kotlin.math.abs(
                (m.positions[2 * b] - m.positions[2 * a]) * (m.positions[2 * c + 1] - m.positions[2 * a + 1]) -
                    (m.positions[2 * c] - m.positions[2 * a]) * (m.positions[2 * b + 1] - m.positions[2 * a + 1]),
            ) / 2.0
        }
        return sum
    }

    // --- parts ---

    @Test fun anUnfilledShapeIsJustItsOutline() {
        val parts = ShapeTessellator.tessellate(shape(ShapeKind.RECTANGLE))
        assertEquals(1, parts.size)
        assertEquals(Rgba(10, 20, 30, 255), parts[0].color)
    }

    @Test fun aFilledShapeDrawsItsFillUnderItsOutline() {
        val fill = Rgba(200, 40, 40, 64)
        val parts = ShapeTessellator.tessellate(shape(ShapeKind.RECTANGLE, fill = fill))
        assertEquals(2, parts.size)
        assertEquals("the fill goes down first", fill, parts[0].color)
        assertEquals(Rgba(10, 20, 30, 255), parts[1].color)
    }

    @Test fun everyPartTakesTheDirectPass() {
        // A fill is a simple polygon and an outline is opaque, so neither needs masking.
        val parts = ShapeTessellator.tessellate(shape(ShapeKind.ELLIPSE, fill = Rgba(1, 2, 3, 64)))
        assertTrue(parts.all { it.pass == InkPass.OPAQUE })
    }

    @Test fun anOpenShapeNeverFills() {
        for (kind in listOf(ShapeKind.LINE, ShapeKind.ARROW)) {
            val parts = ShapeTessellator.tessellate(shape(kind, fill = Rgba(9, 9, 9, 90)))
            assertEquals("$kind should have outline only", 1, parts.size)
        }
    }

    @Test fun everyShapeKindProducesGeometry() {
        for (kind in ShapeKind.DRAW_TOOL_KINDS) {
            val parts = ShapeTessellator.tessellate(shape(kind))
            assertTrue("$kind produced nothing", parts.isNotEmpty() && !parts[0].mesh.isEmpty)
        }
    }

    // --- fills ---

    @Test fun aRectangleFillCoversItsInterior() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.RECTANGLE), 0.01)
        assertTrue(covers(mesh, 50.0, 30.0))
        assertTrue(covers(mesh, 2.0, 2.0))
        assertTrue(!covers(mesh, 120.0, 30.0))
    }

    @Test fun anEllipseFillCoversTheCentreAndNotTheCorner() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.ELLIPSE), 0.01)
        assertTrue(covers(mesh, 50.0, 30.0))
        assertTrue("the box corner is outside an ellipse", !covers(mesh, 1.0, 1.0))
    }

    @Test fun anEllipseFillIsCloseToItsTrueArea() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.ELLIPSE), 0.01)
        val exact = Math.PI * 50.0 * 30.0
        assertEquals(exact, area(mesh), exact * 0.01)
    }

    @Test fun aTriangleFillIsOneTriangle() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.TRIANGLE), 0.01)
        assertEquals(1, mesh.triangleCount)
        assertTrue(covers(mesh, 50.0, 50.0))
    }

    @Test fun aConvexPolygonFillsByEarClipping() {
        val verts = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(120.0, 60.0), Pt(50.0, 100.0), Pt(-20.0, 60.0))
        val poly = ShapeItem.poly(ShapeKind.POLYGON, verts, Rgba(1, 1, 1, 255), 2.0, Rgba(5, 5, 5, 80), false, 0.6, false, 10.0, 8.0)
        val mesh = ShapeTessellator.fillMesh(poly, 0.01)
        assertEquals("n vertices ear-clip into n-2 triangles", verts.size - 2, mesh.triangleCount)
        assertTrue(covers(mesh, 50.0, 50.0))
        assertTrue(!covers(mesh, 300.0, 300.0))
    }

    @Test fun aConcavePolygonDoesNotFillItsNotch() {
        // An arrowhead-shaped polygon: the notch between the barbs must stay empty.
        val verts = listOf(Pt(0.0, 0.0), Pt(100.0, 50.0), Pt(0.0, 100.0), Pt(30.0, 50.0))
        val poly = ShapeItem.poly(ShapeKind.POLYGON, verts, Rgba(1, 1, 1, 255), 2.0, Rgba(5, 5, 5, 80), false, 0.6, false, 10.0, 8.0)
        val mesh = ShapeTessellator.fillMesh(poly, 0.01)
        assertEquals(verts.size - 2, mesh.triangleCount)
        assertTrue("inside the body", covers(mesh, 40.0, 50.0))
        assertTrue("inside the notch", !covers(mesh, 10.0, 50.0))
    }

    // --- outlines ---

    @Test fun anOutlineRunsAlongTheEdgeAndNotThroughTheMiddle() {
        val mesh = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE), 0.01)
        assertTrue("on the top edge", covers(mesh, 50.0, 0.0))
        assertTrue("on the left edge", covers(mesh, 0.0, 30.0))
        assertTrue("the interior is not stroked", !covers(mesh, 50.0, 30.0))
    }

    @Test fun aClosedOutlineHasNoGapAtItsCorners() {
        val mesh = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE), 0.01)
        for (corner in listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 60.0), Pt(0.0, 60.0))) {
            assertTrue("gap at $corner", covers(mesh, corner.x, corner.y))
        }
    }

    @Test fun aLineOutlineIsRoundedPastItsEnds() {
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(1, 1, 1, 255), 8.0)
        val mesh = ShapeTessellator.outlineMesh(line, 0.01)
        assertTrue("round cap past the start", covers(mesh, -2.0, 0.0))
        assertTrue("round cap past the end", covers(mesh, 102.0, 0.0))
        assertTrue(!covers(mesh, -20.0, 0.0))
    }

    @Test fun anArrowAddsAHeadPastItsShaft() {
        val arrow = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(1, 1, 1, 255), 4.0)
        val plain = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(1, 1, 1, 255), 4.0)
        assertTrue(
            "the head must add geometry",
            ShapeTessellator.outlineMesh(arrow, 0.01).triangleCount >
                ShapeTessellator.outlineMesh(plain, 0.01).triangleCount,
        )
    }

    @Test fun aDashedOutlineLeavesGaps() {
        val solid = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE), 0.01)
        val dashed = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE, dashed = true), 0.01)
        assertTrue("dashes must cover less than a solid line", area(dashed) < area(solid))
        assertTrue(!dashed.isEmpty)
    }

    @Test fun everyIndexPointsAtARealVertex() {
        for (kind in ShapeKind.entries) {
            val s = if (kind == ShapeKind.POLYGON || kind == ShapeKind.POLYLINE) {
                ShapeItem.poly(
                    kind, listOf(Pt(0.0, 0.0), Pt(40.0, 5.0), Pt(20.0, 40.0)),
                    Rgba(1, 1, 1, 255), 3.0, Rgba(2, 2, 2, 60), false, 0.6, false, 10.0, 8.0,
                )
            } else {
                shape(kind, fill = Rgba(2, 2, 2, 60))
            }
            for (part in ShapeTessellator.tessellate(s)) {
                assertEquals(0, part.mesh.indices.size % 3)
                for (i in part.mesh.indices) {
                    assertTrue("$kind index $i out of range", i >= 0 && i < part.mesh.vertexCount)
                }
            }
        }
    }

    // --- dash runs ---

    @Test fun dashRunsAlternateAlongThePath() {
        val runs = MeshBuilder.dashRuns(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), 10.0, 10.0, closed = false)
        assertEquals(5, runs.size)
        assertEquals(0.0, runs[0].first().x, 1e-9)
        assertEquals(10.0, runs[0].last().x, 1e-9)
        assertEquals(20.0, runs[1].first().x, 1e-9)
    }

    @Test fun aClosedDashedPathWrapsBackToItsStart() {
        val square = listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 40.0), Pt(0.0, 40.0))
        val runs = MeshBuilder.dashRuns(square, 10.0, 10.0, closed = true)
        assertTrue(runs.isNotEmpty())
        // 160 px of perimeter at a 20 px period is eight dashes.
        assertEquals(8, runs.size)
    }

    @Test fun aDegenerateDashSettingDrawsSolid() {
        val path = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0))
        assertEquals(1, MeshBuilder.dashRuns(path, 0.0, 10.0, closed = false).size)
        assertEquals(1, MeshBuilder.dashRuns(path, 10.0, 0.0, closed = false).size)
    }
}
