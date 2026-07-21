package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeEraseTest {

    private val red = Rgba(255, 0, 0, 255)

    private fun line(dashed: Boolean = false) = ShapeItem(
        ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), red, 4.0, null,
        dashed = dashed, dashLength = 12.0, dashGap = 5.0,
    )

    @Test fun untouchedLineReturnsNull() {
        assertNull(line().erasedBy(50.0, 30.0, 10.0))
        assertNull(line().erasedBy(-40.0, 0.0, 10.0))
    }

    @Test fun midLineCutSplitsAtTheCircleBoundary() {
        val frags = line().erasedBy(50.0, 0.0, 10.0)!!
        assertEquals(2, frags.size)
        val left = frags[0].vertices()!!
        val right = frags[1].vertices()!!
        assertEquals(0.0, left.first().x, 1e-9)
        assertEquals(40.0, left.last().x, 1e-9)
        assertEquals(60.0, right.first().x, 1e-9)
        assertEquals(100.0, right.last().x, 1e-9)
        frags.forEach { assertEquals(ShapeKind.POLYLINE, it.shape) }
    }

    @Test fun coveredLineErasesWhole() {
        assertEquals(0, line().erasedBy(50.0, 0.0, 200.0)!!.size)
    }

    @Test fun endTrimLeavesOneFragment() {
        val frags = line().erasedBy(0.0, 0.0, 10.0)!!
        assertEquals(1, frags.size)
        val pts = frags[0].vertices()!!
        assertEquals(10.0, pts.first().x, 1e-9)
        assertEquals(100.0, pts.last().x, 1e-9)
    }

    @Test fun fragmentsKeepTheOutlineStyle() {
        val frags = line(dashed = true).erasedBy(50.0, 0.0, 10.0)!!
        frags.forEach {
            assertTrue(it.dashed)
            assertEquals(12.0, it.dashLength, 1e-9)
            assertEquals(5.0, it.dashGap, 1e-9)
            assertEquals(red, it.strokeRgba)
            assertEquals(4.0, it.strokeWidth, 1e-9)
        }
    }

    @Test fun rectEdgeCutMergesAcrossTheSeam() {
        val rect = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, null)
        val frags = rect.erasedBy(50.0, 0.0, 10.0)!!
        // One cut in the top edge: the rest of the outline survives as a single open run
        // from (60,0) clockwise around through the corners back to (40,0).
        assertEquals(1, frags.size)
        val pts = frags[0].vertices()!!
        assertEquals(Pt(60.0, 0.0), approx(pts.first()))
        assertEquals(Pt(40.0, 0.0), approx(pts.last()))
        assertTrue(pts.any { it.distanceTo(Pt(100.0, 100.0)) < 1e-6 })
        assertTrue(pts.any { it.distanceTo(Pt(0.0, 100.0)) < 1e-6 })
    }

    @Test fun cornerCutSplitsTheRectOnce() {
        val rect = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, null)
        val frags = rect.erasedBy(100.0, 0.0, 10.0)!!
        assertEquals(1, frags.size)
        val pts = frags[0].vertices()!!
        assertEquals(Pt(90.0, 0.0), approx(pts.last()))
        assertEquals(Pt(100.0, 10.0), approx(pts.first()))
    }

    @Test fun filledShapesEraseWholeOnAnyHit() {
        val filled = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, Rgba(255, 0, 0, 64))
        assertEquals(0, filled.erasedBy(50.0, 0.0, 10.0)!!.size)   // edge hit
        assertEquals(0, filled.erasedBy(50.0, 50.0, 10.0)!!.size)  // interior hit
        assertNull(filled.erasedBy(200.0, 50.0, 10.0))             // clean miss
    }

    @Test fun ellipseCutLeavesAnArcPolyline() {
        val ell = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(100.0, 100.0), red, 3.0, null)
        val frags = ell.erasedBy(100.0, 50.0, 15.0)!! // bite at the right extreme
        assertEquals(1, frags.size)
        val pts = frags[0].vertices()!!
        assertTrue(pts.size > 10)
        // Every surviving vertex stays outside the eraser circle.
        pts.forEach { assertTrue(it.distanceTo(Pt(100.0, 50.0)) >= 15.0 - 1e-6) }
    }

    @Test fun polylineCutSplitsIntoRuns() {
        val zig = ShapeItem.poly(
            ShapeKind.POLYLINE, listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 50.0), Pt(100.0, 50.0)),
            red, 2.0,
        )
        val frags = zig.erasedBy(50.0, 25.0, 10.0)!! // bite the vertical middle
        assertEquals(2, frags.size)
        assertEquals(Pt(50.0, 15.0), approx(frags[0].vertices()!!.last()))
        assertEquals(Pt(50.0, 35.0), approx(frags[1].vertices()!!.first()))
    }

    private fun approx(p: Pt) = Pt(Math.round(p.x * 1e6) / 1e6, Math.round(p.y * 1e6) / 1e6)
}
