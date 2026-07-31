package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftTransformTest {

    @Test fun noDragIsTheIdentity() {
        assertTrue(LiftTransform.NONE.isIdentity)
        val p = LiftTransform.NONE.apply(Pt(3.0, 7.0))
        assertEquals(3.0, p.x, 1e-12)
        assertEquals(7.0, p.y, 1e-12)
    }

    @Test fun aShiftMovesEveryPointTheSameWay() {
        val at = LiftTransform(dx = 5.0, dy = -2.0)
        assertFalse(at.isIdentity)
        assertFalse(at.turns)
        val p = at.apply(Pt(1.0, 1.0))
        assertEquals(6.0, p.x, 1e-12)
        assertEquals(-1.0, p.y, 1e-12)
    }

    @Test fun aTurnLeavesItsOwnPivotAlone() {
        val at = LiftTransform(pivot = Pt(10.0, 10.0), angle = 0.7)
        val p = at.apply(Pt(10.0, 10.0))
        assertEquals(10.0, p.x, 1e-12)
        assertEquals(10.0, p.y, 1e-12)
    }

    @Test fun aQuarterTurnSendsRightToDown() {
        val at = LiftTransform(pivot = Pt.ZERO, angle = Math.PI / 2.0)
        val p = at.apply(Pt(4.0, 0.0))
        assertEquals(0.0, p.x, 1e-12)
        assertEquals(4.0, p.y, 1e-12)
    }

    @Test fun theShiftGoesOnAfterTheTurn() {
        val at = LiftTransform(dx = 100.0, dy = 0.0, pivot = Pt.ZERO, angle = Math.PI / 2.0)
        val p = at.apply(Pt(4.0, 0.0))
        assertEquals("the shift must not be turned as well", 100.0, p.x, 1e-12)
        assertEquals(4.0, p.y, 1e-12)
    }

    @Test fun boundsOfATurnedBoxGrowToHoldIt() {
        val square = Rect(-1.0, -1.0, 2.0, 2.0)
        val at = LiftTransform(pivot = Pt.ZERO, angle = Math.PI / 4.0)
        val b = at.bounds(square)
        assertEquals(kotlin.math.sqrt(2.0) * 2.0, b.w, 1e-9)
        assertEquals(kotlin.math.sqrt(2.0) * 2.0, b.h, 1e-9)
    }

    @Test fun boundsOfAShiftJustMove() {
        val b = LiftTransform(dx = 3.0, dy = 4.0).bounds(Rect(0.0, 0.0, 2.0, 2.0))
        assertEquals(3.0, b.x, 1e-12)
        assertEquals(4.0, b.y, 1e-12)
        assertEquals(2.0, b.w, 1e-12)
    }

    /** The cached halo layer is composited at the difference, so that difference has to be right. */
    @Test fun theResidualIsWhatIsLeftSinceTheCacheWasBuilt() {
        val built = LiftTransform(dx = 10.0, dy = 5.0, pivot = Pt(2.0, 3.0), angle = 0.4)
        val now = LiftTransform(dx = 14.0, dy = 1.0, pivot = Pt(2.0, 3.0), angle = 1.1)
        val rest = now.since(built)
        assertEquals(4.0, rest.dx, 1e-12)
        assertEquals(-4.0, rest.dy, 1e-12)
        assertEquals(0.7, rest.angle, 1e-12)
        assertEquals(2.0, rest.pivot.x, 1e-12)
    }

    @Test fun aResidualAgainstItselfIsNothing() {
        val at = LiftTransform(dx = 8.0, dy = 2.0, pivot = Pt(4.0, 4.0), angle = 0.9)
        assertTrue(at.since(at).isIdentity)
    }
}
