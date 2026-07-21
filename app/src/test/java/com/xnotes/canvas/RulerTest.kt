package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class RulerTest {

    private fun ruler(angle: Double = 0.0) = Ruler().apply {
        center = Pt(100.0, 100.0)
        angleRad = angle
        thicknessPx = 20.0   // ±10 across
        visible = true
    }

    @Test fun signedAcrossAndAlong() {
        val r = ruler()
        // Horizontal: normal is (0,1), direction (1,0).
        assertEquals(8.0, r.signedAcross(Pt(140.0, 108.0)), 1e-9)   // 8 below the centre line
        assertEquals(-5.0, r.signedAcross(Pt(140.0, 95.0)), 1e-9)
        assertEquals(40.0, r.along(Pt(140.0, 108.0)), 1e-9)         // 40 along from centre
    }

    @Test fun bodyContainsIgnoresLength() {
        val r = ruler()
        assertTrue(r.bodyContains(Pt(100.0, 100.0)))      // centre
        assertTrue(r.bodyContains(Pt(99999.0, 109.0)))    // far along but within thickness -> still on body
        assertFalse(r.bodyContains(Pt(100.0, 115.0)))     // beyond thickness
    }

    @Test fun projectToEdgeKeepsAlongClampsAcross() {
        val r = ruler()
        // Top edge is across = +10 (y = 110); project keeps x, sets y to 110.
        val top = r.projectToEdge(Pt(140.0, 130.0), topSide = true)
        assertEquals(140.0, top.x, 1e-9)
        assertEquals(110.0, top.y, 1e-9)
        val bot = r.projectToEdge(Pt(70.0, 50.0), topSide = false)
        assertEquals(70.0, bot.x, 1e-9)
        assertEquals(90.0, bot.y, 1e-9)
    }

    @Test fun projectToEdgeRotated90() {
        // direction (0,1), normal (-1,0): the +normal edge is at x = 90.
        val r = ruler(PI / 2)
        val top = r.projectToEdge(Pt(40.0, 160.0), topSide = true)
        assertEquals(90.0, top.x, 1e-9)
        assertEquals(160.0, top.y, 1e-9)   // along (y) preserved
    }

    @Test fun bodyQuadSpan() {
        val q = ruler().bodyQuad(-30.0, 50.0)
        assertEquals(Pt(70.0, 110.0), q[0])
        assertEquals(Pt(150.0, 110.0), q[1])
        assertEquals(Pt(150.0, 90.0), q[2])
        assertEquals(Pt(70.0, 90.0), q[3])
    }

    @Test fun handles() {
        val r = ruler() // centre (100,100), horizontal, thickness 20 -> handleRadius 4
        val hs = r.handleCenters(50.0)
        assertEquals(Pt(150.0, 100.0), hs[0]) // +direction
        assertEquals(Pt(50.0, 100.0), hs[1])  // −direction
        assertEquals(0, r.hitHandle(Pt(150.0, 100.0), 50.0, r.handleRadiusPx()))
        assertEquals(1, r.hitHandle(Pt(50.0, 100.0), 50.0, r.handleRadiusPx()))
        assertNull(r.hitHandle(Pt(100.0, 100.0), 50.0, r.handleRadiusPx())) // centre misses both
    }

    @Test fun hitButton() {
        val r = ruler()
        val centers = r.buttonCenters()
        assertEquals(2, centers.size)
        val (btn, at) = centers[0]
        assertEquals(btn, r.hitButton(at, r.buttonRadiusPx()))
        assertNull(r.hitButton(Pt(100.0, 200.0), r.buttonRadiusPx()))
    }

    @Test fun snapToAxesPullsNearbyAngles() {
        val snap = Math.toRadians(Ruler.AXIS_SNAP_DEG)
        assertEquals(0.0, Ruler.snapToAxes(snap * 0.9), 1e-9)
        assertEquals(0.0, Ruler.snapToAxes(-snap * 0.9), 1e-9)
        assertEquals(PI / 2, Ruler.snapToAxes(PI / 2 - snap * 0.5), 1e-9)
        assertEquals(PI, Ruler.snapToAxes(PI + snap * 0.9), 1e-9)
        assertEquals(-PI / 2, Ruler.snapToAxes(-PI / 2 + snap * 0.9), 1e-9)
    }

    @Test fun snapToAxesLeavesFarAnglesAlone() {
        val off = Math.toRadians(10.0)
        assertEquals(off, Ruler.snapToAxes(off), 1e-9)
        assertEquals(PI / 4, Ruler.snapToAxes(PI / 4), 1e-9)          // 45 never snaps
        assertEquals(PI / 2 + off, Ruler.snapToAxes(PI / 2 + off), 1e-9)
    }

    @Test fun snapToAxesHandlesUnwoundAngles() {
        // Multi-turn angles from the two-finger rotate accumulate past 2*PI.
        val snap = Math.toRadians(Ruler.AXIS_SNAP_DEG)
        assertEquals(5 * PI / 2, Ruler.snapToAxes(5 * PI / 2 + snap * 0.5), 1e-9)
        assertEquals(-3 * PI, Ruler.snapToAxes(-3 * PI - snap * 0.5), 1e-9)
    }

    @Test fun pxToCm() {
        // 150 px at 150 dpi = 1 inch = 2.54 cm.
        assertEquals(2.54, RulerMath.contentPxToCm(150.0, 150), 1e-9)
    }

    @Test fun viewportLenToCmScalesWithZoom() {
        assertEquals(2.54, RulerMath.viewportLenToCm(150.0, 1.0, 150), 1e-9)
        assertEquals(1.27, RulerMath.viewportLenToCm(150.0, 2.0, 150), 1e-9)
    }
}
