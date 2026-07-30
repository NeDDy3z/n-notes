package com.xnotes.core.infinite

import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPatternTest {

    private fun viewport(zoom: Double, sx: Double = 0.0, sy: Double = 0.0): CanvasViewport =
        CanvasViewport().apply {
            widthPx = 1000
            heightPx = 800
            this.zoom = zoom
            scrollX = sx
            scrollY = sy
        }

    // --- level selection ---

    @Test fun theChosenPeriodAlwaysLandsInItsBand() {
        val spacing = 64.0
        var zoom = CanvasViewport.MIN_ZOOM
        while (zoom <= CanvasViewport.MAX_ZOOM) {
            val periodPx = spacing * BackgroundPattern.levelMultiplier(spacing, zoom) * zoom
            assertTrue(
                "period $periodPx px at zoom $zoom is below the floor",
                periodPx >= BackgroundPattern.MIN_PERIOD_PX - 1e-9,
            )
            assertTrue(
                "period $periodPx px at zoom $zoom should have halved",
                periodPx < 2 * BackgroundPattern.MIN_PERIOD_PX + 1e-9,
            )
            zoom *= 1.09
        }
    }

    @Test fun theMultiplierIsAlwaysAPowerOfTwo() {
        for (zoom in listOf(0.02, 0.13, 0.5, 1.0, 3.3, 17.0, 64.0)) {
            val k = BackgroundPattern.levelMultiplier(64.0, zoom)
            assertTrue("multiplier $k is not a power of two", isPowerOfTwo(k))
        }
    }

    @Test fun zoomingOutCoarsensAndZoomingInSubdivides() {
        val far = BackgroundPattern.levelMultiplier(64.0, 0.02)
        val mid = BackgroundPattern.levelMultiplier(64.0, 1.0)
        val near = BackgroundPattern.levelMultiplier(64.0, 64.0)
        assertTrue(far > mid)
        assertTrue(near < mid)
    }

    @Test fun theMultiplierIsMonotonicInZoom() {
        var last = BackgroundPattern.levelMultiplier(64.0, 0.01)
        var zoom = 0.01
        while (zoom < 200.0) {
            val k = BackgroundPattern.levelMultiplier(64.0, zoom)
            assertTrue("level got coarser while zooming in (z=$zoom)", k <= last)
            last = k
            zoom *= 1.13
        }
    }

    @Test fun degenerateInputsFallBackToTheUnscaledSpacing() {
        assertEquals(1.0, BackgroundPattern.levelMultiplier(0.0, 1.0), 0.0)
        assertEquals(1.0, BackgroundPattern.levelMultiplier(64.0, 0.0), 0.0)
        assertEquals(1.0, BackgroundPattern.levelMultiplier(Double.NaN, 1.0), 0.0)
        assertEquals(1.0, BackgroundPattern.levelMultiplier(64.0, Double.NaN), 0.0)
    }

    @Test fun anExtremeZoomTerminatesRatherThanSpinning() {
        assertTrue(BackgroundPattern.levelMultiplier(64.0, 1e-30).isFinite())
        assertTrue(BackgroundPattern.levelMultiplier(64.0, 1e30) > 0.0)
    }

    // --- phase ---

    @Test fun phaseStaysInsideThePeriod() {
        for (scroll in listOf(0.0, 7.0, 64.0, -3.0, -1_000_000.5, 987_654_321.25)) {
            val p = BackgroundPattern.phase(scroll, 64.0)
            assertTrue("phase $p out of range for scroll $scroll", p >= 0.0 && p < 64.0)
        }
    }

    @Test fun phaseIsExactAtAMillionPixelsOut() {
        // The whole reason phase is computed in double: a float scroll would have lost this.
        assertEquals(0.0, BackgroundPattern.phase(64.0 * 15625.0, 64.0), 1e-12)
        assertEquals(0.25, BackgroundPattern.phase(64.0 * 15625.0 + 0.25, 64.0), 1e-12)
    }

    @Test fun aNegativeScrollWrapsForward() {
        assertEquals(63.0, BackgroundPattern.phase(-1.0, 64.0), 1e-12)
        assertEquals(0.0, BackgroundPattern.phase(-64.0, 64.0), 1e-12)
    }

    @Test fun degeneratePhaseInputsAreSafe() {
        assertEquals(0.0, BackgroundPattern.phase(5.0, 0.0), 0.0)
        assertEquals(0.0, BackgroundPattern.phase(Double.NaN, 64.0), 0.0)
    }

    // --- subdivision fade ---

    @Test fun theSubdivisionFadesInAcrossTheBand() {
        assertEquals(0.0, BackgroundPattern.subdivisionAlpha(BackgroundPattern.MIN_PERIOD_PX), 1e-12)
        assertEquals(1.0, BackgroundPattern.subdivisionAlpha(2 * BackgroundPattern.MIN_PERIOD_PX), 1e-12)
        val mid = BackgroundPattern.subdivisionAlpha(BackgroundPattern.MIN_PERIOD_PX * 1.5)
        assertTrue(mid > 0.4 && mid < 0.6)
    }

    @Test fun theSubdivisionIsFullExactlyWhereTheLevelFlips() {
        // At the flip the subdivision becomes the new base level; anything short of full would pop.
        val spacing = 64.0
        var zoom = 0.02
        var lastK = BackgroundPattern.levelMultiplier(spacing, zoom)
        while (zoom < 64.0) {
            zoom *= 1.001
            val k = BackgroundPattern.levelMultiplier(spacing, zoom)
            if (k != lastK) {
                val periodJustBefore = spacing * lastK * (zoom / 1.001)
                assertEquals(
                    "the finer level must be fully in as it takes over",
                    1.0,
                    BackgroundPattern.subdivisionAlpha(periodJustBefore),
                    0.02,
                )
                lastK = k
            }
        }
    }

    // --- resolve ---

    @Test fun resolveGivesTheShaderOnlySmallNumbers() {
        val bg = CanvasBackground(PagePattern.GRID, Rgba(0, 0, 0, 64), 64.0)
        val r = BackgroundPattern.resolve(bg, viewport(3.0, 12_345_678.9, -9_876_543.2))
        assertTrue(r.periodPx >= BackgroundPattern.MIN_PERIOD_PX)
        assertTrue(r.phaseXPx >= 0.0 && r.phaseXPx < r.periodPx)
        assertTrue(r.phaseYPx >= 0.0 && r.phaseYPx < r.periodPx)
        assertEquals(r.periodPx / 2.0, r.subPeriodPx, 1e-9)
        assertTrue(r.subPhaseXPx >= 0.0 && r.subPhaseXPx < r.subPeriodPx + 1e-9)
    }

    @Test fun resolveHonoursTheSpacingClamp() {
        val tooTight = CanvasBackground(PagePattern.GRID, Rgba(0, 0, 0, 64), spacing = 1.0)
        val r = BackgroundPattern.resolve(tooTight, viewport(1.0))
        // A 1 px spacing clamps up to the ruling minimum before the level is chosen.
        assertTrue(r.periodPx >= BackgroundPattern.MIN_PERIOD_PX)
        assertEquals(16.0, tooTight.clampedSpacing, 1e-9)
    }

    @Test fun theSnapshotOverloadMatchesTheViewportOne() {
        val bg = CanvasBackground()
        val v = viewport(2.5, 111.0, 222.0)
        assertEquals(BackgroundPattern.resolve(bg, v), BackgroundPattern.resolve(bg, 2.5, 111.0, 222.0))
    }

    // --- the distance function the shader mirrors ---

    @Test fun aLineSitsWhereTheContentCoordinateIsAWholeMultiple() {
        val spacing = 64.0
        val zoom = 2.0
        val scroll = 100.0
        val r = BackgroundPattern.resolve(CanvasBackground(spacing = spacing), viewport(zoom, scroll, scroll))
        val period = spacing * BackgroundPattern.levelMultiplier(spacing, zoom)
        // Device x of the first grid line at or past the viewport's left edge.
        val firstLine = (Math.ceil(scroll / period) * period - scroll) * zoom
        assertEquals(0.0, BackgroundPattern.distanceToLine(firstLine, r.periodPx, r.phaseXPx), 1e-6)
        val halfway = firstLine + r.periodPx / 2.0
        assertEquals(r.periodPx / 2.0, BackgroundPattern.distanceToLine(halfway, r.periodPx, r.phaseXPx), 1e-6)
    }

    @Test fun theRulingHoldsItsAlignmentFarFromTheOrigin() {
        val spacing = 64.0
        val zoom = 1.0
        val scroll = 5_000_000.0
        val r = BackgroundPattern.resolve(CanvasBackground(spacing = spacing), viewport(zoom, scroll, scroll))
        val period = spacing * BackgroundPattern.levelMultiplier(spacing, zoom)
        val firstLine = (Math.ceil(scroll / period) * period - scroll) * zoom
        assertEquals(0.0, BackgroundPattern.distanceToLine(firstLine, r.periodPx, r.phaseXPx), 1e-6)
    }

    @Test fun distanceIsNeverNegativeAndNeverPastAHalfPeriod() {
        val r = BackgroundPattern.resolve(CanvasBackground(), viewport(1.0, 33.0, 44.0))
        var x = -500.0
        while (x < 500.0) {
            val d = BackgroundPattern.distanceToLine(x, r.periodPx, r.phaseXPx)
            assertTrue(d >= 0.0 && d <= r.periodPx / 2.0 + 1e-9)
            x += 0.37
        }
    }

    private fun isPowerOfTwo(v: Double): Boolean {
        if (v <= 0.0) return false
        var x = v
        while (x < 1.0) x *= 2.0
        while (x > 1.0) x /= 2.0
        return kotlin.math.abs(x - 1.0) < 1e-9
    }
}
