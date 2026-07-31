package com.xnotes.core.infinite

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftTransformTest {

    private fun assertSamePoint(expected: Pt, actual: Pt, tol: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tol)
        assertEquals(expected.y, actual.y, tol)
    }

    @Test fun noDragIsTheIdentity() {
        assertTrue(LiftTransform.NONE.isIdentity)
        assertSamePoint(Pt(3.0, 7.0), LiftTransform.NONE.apply(Pt(3.0, 7.0)))
    }

    @Test fun aShiftMovesEveryPointTheSameWay() {
        val at = LiftTransform.shift(5.0, -2.0)
        assertFalse(at.isIdentity)
        assertTrue("a shift has no linear part", at.isLinearIdentity)
        assertSamePoint(Pt(6.0, -1.0), at.apply(Pt(1.0, 1.0)))
    }

    @Test fun aTurnLeavesItsOwnPivotAlone() {
        val at = LiftTransform.turn(Pt(10.0, 10.0), 0.7)
        assertSamePoint(Pt(10.0, 10.0), at.apply(Pt(10.0, 10.0)))
    }

    @Test fun aQuarterTurnSendsRightToDown() {
        val at = LiftTransform.turn(Pt.ZERO, Math.PI / 2.0)
        assertSamePoint(Pt(0.0, 4.0), at.apply(Pt(4.0, 0.0)))
    }

    @Test fun aTurnIsNotAScale() {
        assertEquals(1.0, LiftTransform.turn(Pt(3.0, 4.0), 1.2).linearScale, 1e-12)
    }

    @Test fun theShiftGoesOnAfterTheTurn() {
        val at = LiftTransform.turn(Pt.ZERO, Math.PI / 2.0).copy(dx = 100.0)
        val p = at.apply(Pt(4.0, 0.0))
        assertEquals("the shift must not be turned as well", 100.0, p.x, 1e-12)
        assertEquals(4.0, p.y, 1e-12)
    }

    @Test fun boundsOfATurnedBoxGrowToHoldIt() {
        val at = LiftTransform.turn(Pt.ZERO, Math.PI / 4.0)
        val b = at.bounds(Rect(-1.0, -1.0, 2.0, 2.0))
        assertEquals(kotlin.math.sqrt(2.0) * 2.0, b.w, 1e-9)
        assertEquals(kotlin.math.sqrt(2.0) * 2.0, b.h, 1e-9)
    }

    @Test fun boundsOfAShiftJustMove() {
        val b = LiftTransform.shift(3.0, 4.0).bounds(Rect(0.0, 0.0, 2.0, 2.0))
        assertEquals(3.0, b.x, 1e-12)
        assertEquals(4.0, b.y, 1e-12)
        assertEquals(2.0, b.w, 1e-12)
    }

    // --- any affine, which is what a resize hands over ---

    /** The renderer must land a handle drag exactly where the model would bake it. */
    @Test fun anAffineIsReExpressedAboutThePivotWithoutMovingAnything() {
        val map = Affine.scaleAlongAxes(Pt(40.0, 90.0), 0.6, 1.7, 0.4)
        val at = LiftTransform.of(map, Pt(500.0, 800.0))
        for (p in listOf(Pt(0.0, 0.0), Pt(40.0, 90.0), Pt(123.0, -45.0), Pt(900.0, 900.0))) {
            assertSamePoint(map.apply(p), at.apply(p), 1e-7)
        }
    }

    @Test fun theScaleFactorIsTheOneTheModelScalesWidthsBy() {
        val map = Affine.scaleAbout(Pt.ZERO, 3.0, 3.0)
        assertEquals(map.linearScale, LiftTransform.of(map, Pt(7.0, 7.0)).linearScale, 1e-12)
        val uneven = Affine.scaleAbout(Pt.ZERO, 4.0, 1.0)
        assertEquals(2.0, LiftTransform.of(uneven, Pt.ZERO).linearScale, 1e-12)
    }

    // --- residuals, which is how the cached halo layer is read back ---

    /** The halo is blurred once and read through the residual, so the two must compose exactly. */
    @Test fun theResidualComposesBackOntoWhatItWasBuiltFrom() {
        val pivot = Pt(2.0, 3.0)
        val built = LiftTransform.turn(pivot, 0.4).copy(dx = 10.0, dy = 5.0)
        val now = LiftTransform.of(Affine.scaleAlongAxes(pivot, 0.9, 1.4, 0.7), pivot)
            .copy(dx = 14.0, dy = 1.0)
        val rest = now.since(built)
        for (p in listOf(Pt(0.0, 0.0), Pt(2.0, 3.0), Pt(-30.0, 12.0), Pt(88.0, 41.0))) {
            assertSamePoint(now.apply(p), rest.apply(built.apply(p)), 1e-7)
        }
    }

    @Test fun aResidualAgainstItselfIsNothing() {
        val at = LiftTransform.turn(Pt(4.0, 4.0), 0.9).copy(dx = 8.0, dy = 2.0)
        val rest = at.since(at)
        assertSamePoint(Pt(11.0, -4.0), rest.apply(Pt(11.0, -4.0)), 1e-9)
    }

    @Test fun theResidualOfTwoShiftsIsTheirDifference() {
        val rest = LiftTransform.shift(14.0, 1.0).since(LiftTransform.shift(10.0, 5.0))
        assertEquals(4.0, rest.dx, 1e-12)
        assertEquals(-4.0, rest.dy, 1e-12)
        assertTrue(rest.isLinearIdentity)
    }

    /** A texture read runs destination back to source, so the inverse has to be the real inverse. */
    @Test fun theInverseLinearUndoesTheLinearPart() {
        val at = LiftTransform.of(Affine.scaleAlongAxes(Pt.ZERO, 0.5, 2.0, 0.5), Pt.ZERO)
        val inv = at.inverseLinear()
        val x = 7.0
        val y = -3.0
        val mx = at.a * x + at.c * y
        val my = at.b * x + at.d * y
        assertEquals(x, inv[0] * mx + inv[2] * my, 1e-9)
        assertEquals(y, inv[1] * mx + inv[3] * my, 1e-9)
    }
}
