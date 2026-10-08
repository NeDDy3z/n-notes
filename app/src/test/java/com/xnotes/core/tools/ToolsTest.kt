package com.xnotes.core.tools

import com.xnotes.core.model.Rgba
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    @Test fun theMarkupToolHighlightsAtNinetyAndPansAFreeFinger() {
        val c = ToolDefaults.configFor(Tool.MARKUP)
        assertEquals(MarkupMode.HIGHLIGHT, c.markupMode)
        assertEquals(0.9, c.markupIntensity, 1e-9)
        assertTrue(Tool.MARKUP.fingerPansWhenOff)
        assertFalse(Tool.MARKUP.isStroke)
        assertTrue(Tool.MARKUP in Tool.wheelOrder && Tool.MARKUP in ToolDefaults.persistedTools)
        assertEquals(MarkupMode.HIGHLIGHT, MarkupMode.fromId("nonsense"))
        assertEquals(null, MarkupMode.SELECT.type)
        assertEquals(com.xnotes.core.model.MarkupType.SQUIGGLY, MarkupMode.SQUIGGLY.type)
    }

    @Test fun factoryDefaultsMatchSpec() {
        assertEquals(ToolConfig(3.0, true, 0.35, 0.0), ToolDefaults.configFor(Tool.PEN).copy(rgba = ToolConfig().rgba))
        val cal = ToolDefaults.configFor(Tool.CALLIGRAPHY)
        assertEquals(6.0, cal.baseWidth, 1e-9)
        assertTrue(cal.pressureEnabled)
        assertEquals(0.40, cal.pressureMinFactor, 1e-9)
        assertEquals(0.60, cal.directionStrength, 1e-9)
        val hi = ToolDefaults.configFor(Tool.HIGHLIGHTER)
        assertEquals(16.0, hi.baseWidth, 1e-9)
        assertFalse(hi.pressureEnabled)
        assertEquals(0.90, hi.highlighterAlpha, 1e-9)
        assertEquals(1.0, ToolConversions.intensityToHighlighterAlpha(100.0), 1e-9)
        assertEquals(24.0, ToolDefaults.configFor(Tool.ERASER).baseWidth, 1e-9)
        assertEquals(2.0, ToolDefaults.configFor(Tool.LASSO).baseWidth, 1e-9)
        // A tool without an entry uses ToolConfig() = (3.0, on, 0.35, 0.0).
        assertEquals(ToolConfig().copy(rgba = ToolDefaults.configFor(Tool.PAN).rgba), ToolDefaults.configFor(Tool.PAN))
    }

    @Test fun sensitivityRoundTrip() {
        assertEquals(1.0, ToolConversions.sensitivityToMinFactor(0.0), 1e-9)
        assertEquals(0.1, ToolConversions.sensitivityToMinFactor(100.0), 1e-9)
        assertEquals(0.55, ToolConversions.sensitivityToMinFactor(50.0), 1e-9)
        // inverse
        assertEquals(50.0, ToolConversions.minFactorToSensitivity(0.55), 1e-9)
        assertEquals(0.0, ToolConversions.minFactorToSensitivity(1.0), 1e-9)
    }

    @Test fun speedPenSlidersRoundTrip() {
        val speed = ToolDefaults.configFor(Tool.SPEED)
        assertEquals(25.0, ToolConversions.blurToSmoothing(speed.speedSmoothing), 1e-9)
        assertEquals(50.0, ToolConversions.dpPerMsToTopSpeed(speed.speedTop), 1e-9)
        assertEquals(0.2, ToolConversions.topSpeedToDpPerMs(0.0), 1e-9)
        assertEquals(1.8, ToolConversions.topSpeedToDpPerMs(100.0), 1e-9)
        for (x in listOf(0.0, 12.5, 50.0, 87.0, 100.0)) {
            assertEquals(x, ToolConversions.blurToSmoothing(ToolConversions.smoothingToBlur(x)), 1e-9)
            assertEquals(x, ToolConversions.dpPerMsToTopSpeed(ToolConversions.topSpeedToDpPerMs(x)), 1e-9)
        }
    }

    @Test fun stabilizationSlidersMapToTheirSettings() {
        val pen = ToolDefaults.configFor(Tool.PEN)
        assertEquals(false, pen.stabilize)
        assertEquals(50.0, ToolConversions.trailToStrength(pen.stabilizeTrail), 1e-9)
        assertEquals(50.0, ToolConversions.dpPerSToCrossover(pen.stabilizeCrossover), 1e-9)
        assertEquals(24.0, ToolConversions.strengthToTrail(100.0), 1e-9)
        assertEquals(0.0, ToolConversions.strengthToTrail(-5.0), 1e-9)
        assertEquals(pen.stabilizeCrossover / 3.0, ToolConversions.crossoverToDpPerS(0.0), 1e-9)
        assertEquals(pen.stabilizeCrossover * 3.0, ToolConversions.crossoverToDpPerS(100.0), 1e-9)
        for (x in listOf(0.0, 12.5, 50.0, 87.0, 100.0)) {
            assertEquals(x, ToolConversions.trailToStrength(ToolConversions.strengthToTrail(x)), 1e-9)
            assertEquals(x, ToolConversions.dpPerSToCrossover(ToolConversions.crossoverToDpPerS(x)), 1e-9)
        }
    }

    @Test fun multiplierRoundTrip() {
        // M = 4.0  <->  ds = 0.60 (calligraphy default)
        assertEquals(0.60, ToolConversions.multiplierToDirectionStrength(4.0), 1e-9)
        assertEquals(4.0, ToolConversions.directionStrengthToMultiplier(0.60), 1e-9)
        // clamp at 0.95
        assertEquals(0.95, ToolConversions.multiplierToDirectionStrength(1000.0), 1e-9)
        assertEquals(0.0, ToolConversions.multiplierToDirectionStrength(1.0), 1e-9)
    }

    @Test fun widthRanges() {
        assertEquals(4.0..120.0, ToolConversions.widthRange(Tool.HIGHLIGHTER))
        assertEquals(1.0..20.0, ToolConversions.widthRange(Tool.PEN))
    }

    @Test fun strokeToolsAndAlpha() {
        assertTrue(Tool.CALLIGRAPHY.isStroke)
        assertTrue(Tool.DASHED.isStroke)
        assertFalse(Tool.SELECT.isStroke)
        assertEquals(0.35, Tool.HIGHLIGHTER.alphaScale, 1e-9)
        assertEquals(1.0, Tool.PEN.alphaScale, 1e-9)
        assertEquals(Tool.PAN, Tool.DEFAULT)
    }

    @Test fun shapeKinds() {
        assertTrue(ShapeKind.RECTANGLE.isClosed)
        assertTrue(ShapeKind.LINE.isOpen)
        assertTrue(ShapeKind.ARROW.isOpen)
        assertEquals(ShapeKind.RECTANGLE, ShapeKind.fromId("nonsense"))
        assertEquals(ShapeKind.TRIANGLE, ShapeKind.fromId("triangle"))
    }

    @Test fun theShapeToolsFillReachesClosedKindsOnly() {
        val ink = Rgba(10, 20, 30)
        val on = ShapeConfig(fill = true, fillAlpha = 0.5)
        assertEquals(Rgba(10, 20, 30, 127), on.fillFor(ShapeKind.POLYGON, ink))
        assertEquals(Rgba(10, 20, 30, 127), on.fillFor(ShapeKind.ELLIPSE, ink))
        assertNull(on.fillFor(ShapeKind.POLYLINE, ink))
        assertNull(on.fillFor(ShapeKind.CURVE, ink))
        assertNull(on.fillFor(ShapeKind.LINE, ink))
        assertNull(ShapeConfig(fill = false).fillFor(ShapeKind.RECTANGLE, ink))
    }

    @Test fun toolIdRoundTrip() {
        for (t in Tool.entries) assertEquals(t, Tool.fromId(t.id))
        assertEquals(16, Tool.wheelOrder.size)
    }

    /**
     * The pan's axis locks, which decide how a drag reaches the view on both canvases.
     *
     * A locked axis contributes nothing while the other one still moves, which is the whole point of
     * two separate locks rather than one "panning off" preset: a vertical-only scroll has to be
     * reachable without also giving up the horizontal one.
     */
    @Test fun aLockedAxisContributesNothingWhileTheOtherStillMoves() {
        val both = ToolConfig()
        assertEquals(3.0, both.panDx(3.0), 1e-9)
        assertEquals(4.0, both.panDy(4.0), 1e-9)
        assertTrue(both.panCanMove)

        val lockedX = both.copy(lockPanX = true)
        assertEquals(0.0, lockedX.panDx(3.0), 1e-9)
        assertEquals(4.0, lockedX.panDy(4.0), 1e-9)
        assertTrue(lockedX.panCanMove)

        val lockedY = both.copy(lockPanY = true)
        assertEquals(3.0, lockedY.panDx(3.0), 1e-9)
        assertEquals(0.0, lockedY.panDy(4.0), 1e-9)
        assertTrue(lockedY.panCanMove)

        // Both held is the one setting where a pan cannot move the view at all, so the gesture is
        // never begun rather than begun and thrown away every sample.
        val neither = lockedX.copy(lockPanY = true)
        assertEquals(0.0, neither.panDx(3.0), 1e-9)
        assertEquals(0.0, neither.panDy(4.0), 1e-9)
        assertFalse(neither.panCanMove)
    }

    /**
     * The glide that follows a lifted drag is part of the pan, so it obeys the same locks.
     *
     * This was the one path that did not: the drag masked its delta, then handed the raw finger
     * velocity to the fling, so a locked axis held still for exactly as long as the finger was down
     * and coasted afterwards. A flick along a locked axis alone is now below the start threshold, so
     * it opens no glide either.
     */
    @Test fun aLockedAxisDoesNotCoastAfterTheFingerIsUp() {
        val lockedX = ToolConfig().copy(lockPanX = true)
        val glided = lockedX.panVelocity(900.0, 0.0)
        assertEquals(0.0, glided.x, 1e-9)
        assertEquals(0.0, glided.length(), 1e-9)

        val both = lockedX.panVelocity(900.0, -400.0)
        assertEquals(0.0, both.x, 1e-9)
        assertEquals(-400.0, both.y, 1e-9)

        // Nothing held: the glide is the finger's own velocity, untouched.
        val free = ToolConfig().panVelocity(900.0, -400.0)
        assertEquals(900.0, free.x, 1e-9)
        assertEquals(-400.0, free.y, 1e-9)
    }

    /**
     * Sensitivity scales what a hand asks for, and it scales it the same in both directions.
     *
     * The zoom factor is applied to the pinch's ratio rather than to the resulting scale, which is
     * what makes "twice as fast" mean twice as far in either direction instead of only ever making
     * zooming-in faster and zooming-out slower. Zero is the floor, so a slider can hold an axis
     * still — which is why an axis lock says so in words instead of leaving it to a slider.
     */
    @Test fun sensitivityScalesTheGestureRatherThanTheResult() {
        val pan = ToolConfig()
        assertEquals(1.0, pan.panSpeedX, 1e-9)
        assertEquals(1.0, pan.panSpeedY, 1e-9)
        assertEquals(1.0, pan.zoomSensitivity, 1e-9)

        val fast = pan.copy(panSpeedX = 2.0, panSpeedY = 0.5)
        assertEquals(20.0, fast.panDx(10.0), 1e-9)
        assertEquals(5.0, fast.panDy(10.0), 1e-9)

        // A pinch spread to 1.4x, at twice the rate and at none.
        val quick = pan.copy(zoomSensitivity = 2.0)
        assertEquals(1.0 + 0.4 * 2.0, quick.zoomRatio(1.4), 1e-9)
        assertEquals(1.0 - 0.4 * 2.0, quick.zoomRatio(0.6), 1e-9)
        assertEquals(1.4, pan.zoomRatio(1.4), 1e-9)
        assertEquals(1.0, pan.copy(zoomSensitivity = 0.0).zoomRatio(1.4), 1e-9)

        // A held axis is still held at any rate, and a fast locked axis still coasts nowhere.
        val lockedX = fast.copy(lockPanX = true)
        assertEquals(0.0, lockedX.panDx(10.0), 1e-9)
        assertEquals(0.0, lockedX.panVelocity(900.0, 0.0).x, 1e-9)
    }

    /**
 * The glide's length is stored as a length and reads back as a reciprocal friction.
 *
 * A glide is an exponential decay, so how long it lasts is set by how fast the speed falls and not
 * by a duration. That makes length and friction inverse rather than proportional, and it is why the
 * slider says "how far" and not "how long": halving the friction does not halve the glide, it
 * doubles it, because the slower decay is what lets the same starting speed carry further.
 */
@Test fun theGlideIsStoredAsALengthAndReadsBackAsItsReciprocal() {
        val pan = ToolConfig()
        assertEquals(1.0, pan.panGlide, 1e-9)
        assertTrue(pan.panGlides())
        assertEquals(ToolConfig.DEFAULT_GLIDE_FRICTION, pan.panFriction(), 1e-9)

        // Twice the glide, half the friction; four times, a quarter of it.
        assertEquals(ToolConfig.DEFAULT_GLIDE_FRICTION / 2.0, pan.copy(panGlide = 2.0).panFriction(), 1e-9)
        assertEquals(ToolConfig.DEFAULT_GLIDE_FRICTION / 4.0, pan.copy(panGlide = 4.0).panFriction(), 1e-9)

        // No glide at all: the view stops where the finger did, which is a refusal to start one
        // rather than a very fast decay that would still move a frame's worth.
        val none = pan.copy(panGlide = 0.0)
        assertFalse(none.panGlides())
        assertTrue(none.panFriction() > ToolConfig.DEFAULT_GLIDE_FRICTION)
    }

    /** The locks are the pan's, so they ship off and are remembered like any other tool setting. */
    @Test fun thePanLocksNothingByDefaultAndArePersisted() {
        val pan = ToolDefaults.configFor(Tool.PAN)
        assertFalse(pan.lockPanX)
        assertFalse(pan.lockPanY)
        assertTrue(Tool.PAN in ToolDefaults.persistedTools)
    }
}
