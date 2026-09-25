package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class AxesRotationTest {

    private fun axes() = ShapeItem(ShapeKind.COORD_AXES, Pt(0.0, 0.0), Pt(200.0, 100.0), Rgba(0, 0, 0, 255), 2.0)

    @Test fun rotatingKeepsTheAxesLengthsAndTurnsThem() {
        val a = axes()
        a.applyTransform(Affine.rotateAbout(Pt(100.0, 50.0), PI / 2.0))
        assertEquals(200.0, a.box.w, 1e-9)
        assertEquals(100.0, a.box.h, 1e-9)
        val (x, y) = a.axesSegments()
        // The X axis now runs top to bottom through the centre, the Y axis left to right.
        assertEquals(200.0, x[0].distanceTo(x[1]), 1e-9)
        assertEquals(100.0, x[0].x, 1e-9)
        assertEquals(x[0].x, x[1].x, 1e-9)
        assertEquals(y[0].y, y[1].y, 1e-9)
        assertTrue(a.contains(Pt(100.0, 140.0)))
    }

    @Test fun undoRestoresTheTurn() {
        val a = axes()
        val before = a.snapshotGeometry()
        a.applyTransform(Affine.rotateAbout(Pt(100.0, 50.0), 0.3))
        assertEquals(0.3, a.angle, 1e-9)
        a.restoreGeometry(before)
        assertEquals(0.0, a.angle, 0.0)
    }
}
