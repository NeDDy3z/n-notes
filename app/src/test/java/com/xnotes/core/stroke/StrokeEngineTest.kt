package com.xnotes.core.stroke

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Conformance vectors from spec 03 §6. `width` below means `2 × half_width`. Geometry is stored
 * as packed floats, so assertions on built geometry use 1e-6 (past any render precision); the
 * pure-double helpers keep their exact tolerances.
 */
class StrokeEngineTest {

    private fun width(
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        pressure: Double,
        ty: Double,
    ) = 2.0 * StrokeEngine.halfWidth(baseWidth, pressureEnabled, m, ds, pressure, ty)

    // --- EMA low-pass ---
    @Test fun emaFirstSamplePassesThrough() {
        assertEquals(5.0, StrokeEngine.ema(listOf(5.0, 9.0, 1.0))[0], 1e-12)
    }

    @Test fun emaTwoSamples() {
        assertEquals(listOf(0.0, 5.0), StrokeEngine.ema(listOf(0.0, 10.0)))
    }

    @Test fun emaEmpty() {
        assertEquals(emptyList<Double>(), StrokeEngine.ema(emptyList()))
    }

    // --- Smoothing is measured in travel, not in samples ---
    @Test fun smoothedCurveSurvivesPenUpSampleReduction() {
        // A hand-sized curve at the spacing a stylus actually reports. What gets committed is the
        // reduced stroke, so its smoothed centreline has to land on the drawn one's at every
        // sample they share. Under a per-sample low-pass it would not: the lag there is one
        // spacing, so thinning the samples pulls the curve in and the ink visibly changes at
        // pen-up. Here the lag is a distance, and the reduction cannot move it.
        val drawnSamples = ArrayList<Sample>()
        var arc = 0.0
        while (arc <= 120.0) {
            drawnSamples.add(Sample(40.0 * sin(arc / 40.0), 40.0 * (1 - cos(arc / 40.0)), 1.0, arc * 5.0))
            arc += 1.2
        }
        val drawn = StrokeEngine.build(drawnSamples, 3.0, false, 1.0, 0.0)
        val kept = StrokeSimplify.simplify(drawnSamples, drawn.halfWidths, StrokeSimplify.LEGACY_EPS)
        assertTrue("the reduction has to actually drop samples", kept.size < drawnSamples.size)
        val committed = StrokeEngine.build(kept, 3.0, false, 1.0, 0.0)

        var worst = 0.0
        var j = 0
        for (i in drawnSamples.indices) {
            if (j >= kept.size || drawnSamples[i] !== kept[j]) continue
            val dx = drawn.cx(i) - committed.cx(j)
            val dy = drawn.cy(i) - committed.cy(j)
            worst = maxOf(worst, hypot(dx, dy))
            j++
        }
        assertEquals("every kept sample should have been matched", kept.size, j)
        // Measured at 0.008 px; the bound leaves room for tuning without letting a regression past.
        assertTrue("centreline moved $worst content px under reduction", worst < 0.05)
    }

    @Test fun smoothingLagIsADistanceNotASampleCount() {
        // The same straight ramp at two sample densities settles to the same trailing distance.
        val dense = (0..60).map { Sample(it * 1.0, 0.0, 1.0) }
        val sparse = (0..20).map { Sample(it * 3.0, 0.0, 1.0) }
        val gd = StrokeEngine.build(dense, 3.0, false, 1.0, 0.0)
        val gs = StrokeEngine.build(sparse, 3.0, false, 1.0, 0.0)
        // Both end at x = 60; the smoothed tip trails it by SMOOTH_LEN either way.
        assertEquals(60.0 - StrokeEngine.SMOOTH_LEN, gd.cx(gd.pointCount - 1), 1e-6)
        assertEquals(60.0 - StrokeEngine.SMOOTH_LEN, gs.cx(gs.pointCount - 1), 1e-6)
    }

    @Test fun calligraphyNibWidthSurvivesPenUpSampleReduction() {
        // A nib stroke that runs thin, turns through the nib's edge, then runs broad: the shape the
        // thick/thin decision is actually made on, sampled densely enough that the reduction can
        // bite (the gap cap alone floors it at ordinary spacing). That decision is a window minimum,
        // not a filter, so it does not care how far the reduction moves the curve, only whether the
        // sample holding a window down is still there. Unguarded this same stroke drifts 0.59 px on
        // a nib that only spans 1.7 to 4.3, which reads as the stroke changing weight at pen-up.
        val ds = 0.6
        val step = 0.3
        val pts = (0..160).map { Sample(it * step, -it * step, 1.0) } +
            (1..160).map { Sample(48.0 + it * step, -48.0 + it * step, 1.0) }
        val drawn = StrokeEngine.build(pts, 6.0, false, 1.0, ds)
        val kept = StrokeSimplify.simplify(
            pts, drawn.halfWidths, StrokeSimplify.LEGACY_EPS, StrokeSimplify.dirArcFor(ds, 1.0),
        )
        assertTrue("the reduction has to actually drop samples", kept.size < pts.size)
        val committed = StrokeEngine.build(kept, 6.0, false, 1.0, ds)

        var worst = 0.0
        var j = 0
        for (i in pts.indices) {
            if (j >= kept.size || pts[i] !== kept[j]) continue
            worst = maxOf(worst, abs(drawn.hw(i) - committed.hw(j)))
            j++
        }
        assertEquals("every kept sample should have been matched", kept.size, j)
        // Exact but for the packed-float geometry: what is left is a few ulps of the stored width.
        assertEquals("nib half-width moved under reduction", 0.0, worst, 1e-5)
    }

    @Test fun theSameGestureAtAnyZoomIsAScaledCopy() {
        // Drawing at 4x lays the same hand movement onto a quarter as much page, so the ink has to
        // come out a quarter the size and otherwise identical. It did not: the nib's confirm window
        // was quoted in page pixels, so at 4x the pen had to be dragged four times as far across the
        // glass before it would thicken. Every arc constant now scales with the draw zoom, and this
        // pins the whole width pipeline to it, confirm window and dot rule included.
        val ds = 0.6
        val k = 0.25
        // A thin lead-in across the nib's edge, then a long broad downstroke.
        val gesture = (0..14).map { Sample(it * 1.4, -it * 1.4, 1.0) } +
            (1..30).map { Sample(19.6 + it * 1.4, -19.6 + it * 1.4, 1.0) }
        val full = StrokeEngine.build(gesture, 6.0, false, 1.0, ds)
        val zoomed = StrokeEngine.build(
            gesture.map { Sample(it.x * k, it.y * k, it.pressure, it.t) },
            6.0 * k, false, 1.0, ds, smoothScale = k,
        )
        assertEquals(full.pointCount, zoomed.pointCount)
        for (i in 0 until full.pointCount) {
            assertEquals("half-width at $i", full.hw(i) * k, zoomed.hw(i), 1e-6)
            assertEquals("x at $i", full.cx(i) * k, zoomed.cx(i), 1e-6)
            assertEquals("y at $i", full.cy(i) * k, zoomed.cy(i), 1e-6)
        }
        // And the stroke really does thicken, so the check is not passing on two thin ribbons.
        assertTrue(full.halfWidths.max() > 1.8f * full.halfWidths.min())
    }

    // --- Width formula ---
    @Test fun penWidth() {
        // base 3, pressure on, m=0.35, ds=0
        assertEquals(1.05, width(3.0, true, 0.35, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(3.0, width(3.0, true, 0.35, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun calligraphyWidth() {
        // base 6, pressure on, m=0.40, ds=0.60
        assertEquals(0.96, width(6.0, true, 0.40, 0.60, 0.0, -1.0), 1e-9) // thinnest
        assertEquals(9.6, width(6.0, true, 0.40, 0.60, 1.0, 1.0), 1e-9)   // thickest
        val ratio = width(6.0, true, 0.40, 0.60, 1.0, 1.0) / width(6.0, true, 0.40, 0.60, 1.0, -1.0)
        assertEquals(4.0, ratio, 1e-9) // direction-only ratio
    }

    @Test fun pressureDisabledIsFullWidth() {
        // base 4, off, m=0.1, ds=0 -> full width regardless of pressure
        assertEquals(4.0, width(4.0, false, 0.1, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(4.0, width(4.0, false, 0.1, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun directionFloorClamps() {
        // base 10, off, m=1.0, ds=0.95 -> would-be-negative factor clamps to 0.1
        assertEquals(1.0, width(10.0, false, 1.0, 0.95, 1.0, -1.0), 1e-9)
    }

    // --- Geometry ---
    @Test fun singleSampleIsOneDiscNoOutline() {
        val g = StrokeEngine.build(listOf(Sample(10.0, 20.0, 1.0)), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertEquals(1, g.pointCount)
        assertEquals(10.0, g.cx(0), 1e-6)
        assertEquals(20.0, g.cy(0), 1e-6)
        assertEquals(1.5, g.hw(0), 1e-6) // full-pressure half-width = the swept dot's radius
    }

    @Test fun threeCollinearSamples() {
        val g = StrokeEngine.build(
            listOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
            3.0, true, 0.35, 0.0,
        )
        assertEquals(6, g.outlineCount)       // 3 left edge + 3 right edge
        assertEquals(3, g.pointCount)
    }

    @Test fun emptyInput() {
        val g = StrokeEngine.build(emptyList(), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertTrue(g.centerline.isEmpty())
    }

    // --- Taper pen (§1.1) ---
    @Test fun taperSpansTheWholeStrokeFromHeadToTip() {
        // The taper eases across the entire stroke now: full width at the head, down to the tip at
        // the end (a point when tip width is 0). Pressure off, m=1 ⇒ a flat 2.0 before the taper.
        val pts = (0..10).map { Sample(it * 10.0, 0.0, 1.0) }  // total 100 px
        val plain = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0)
        val tapered = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)

        assertEquals(2.0, plain.hw(0), 1e-6)
        assertEquals(2.0, tapered.hw(0), 1e-6)   // head: full width
        assertEquals(1.0, tapered.hw(5), 1e-6)   // halfway: edge 0.5 ⇒ half of full
        assertEquals(0.0, tapered.hw(10), 1e-6)  // tail: a point

        // Width eases monotonically from the head all the way to the tip.
        for (i in 0 until tapered.pointCount - 1) assertTrue(tapered.hw(i) > tapered.hw(i + 1))
    }

    @Test fun taperIgnoresVeryShortStrokes() {
        // Total arc length < 8 px ⇒ left un-tapered (a quick tick shouldn't vanish).
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(3.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true)
        assertEquals(2.0, g.hw(0), 1e-6)
    }

    @Test fun taperProfileScalesWithStrokeLength() {
        // The taper spans the whole stroke, so two different-length strokes share the same width
        // at the same fractional position (it is proportional now, not a fixed arc length).
        val a = (0..10).map { Sample(it * 10.0, 0.0, 1.0) }  // total 100
        val b = (0..20).map { Sample(it * 10.0, 0.0, 1.0) }  // total 200
        val ga = StrokeEngine.build(a, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)
        val gb = StrokeEngine.build(b, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)
        assertEquals(ga.hw(5), gb.hw(10), 1e-9)  // both 50% along the stroke
        assertEquals(ga.hw(0), gb.hw(0), 1e-9)   // both full at the head
        assertEquals(ga.hw(10), gb.hw(20), 1e-9) // both a point at the tail
    }

    @Test fun taperBottomsOutAtTheTipWidthFloor() {
        // A non-zero tip width stops the tail at that fraction of full width instead of a point.
        val pts = (0..10).map { Sample(it * 10.0, 0.0, 1.0) } // total 100 px
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true, taperMinFactor = 0.25, smooth = false)
        assertEquals(2.0, g.hw(0), 1e-6)    // head: full width
        assertEquals(0.5, g.hw(10), 1e-6)   // tail: 0.25 of the 2.0 full half-width
        assertEquals(1.25, g.hw(5), 1e-6)   // halfway: 0.25 + 0.75·0.5 of full
    }

    // --- Speed pen (§1.1) ---
    @Test fun speedThinsFastStrokes() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) } // ~0.1 px/ms
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }   // ~2 px/ms
        val gSlow = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0, speedStrength = 0.8)
        val gFast = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0, speedStrength = 0.8)

        assertTrue(gSlow.halfWidths.max() > 1.8)          // slow: near full width
        assertTrue(gFast.halfWidths.max() < 2.0)          // fast: thinned even at its widest
        assertTrue(gFast.halfWidths.min() < 1.0)          // and clearly thin where fastest
    }

    @Test fun speedOffIgnoresTiming() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) }
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }
        // speedStrength defaults to 0 ⇒ identical geometry regardless of timing.
        val a = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0)
        val b = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0)
        assertArrayEquals(a.halfWidths, b.halfWidths, 0f)
    }

    @Test fun speedScaleScalesPerceivedSpeed() {
        // Same gesture and timing; a larger content->dp scale (e.g. zoomed in) reads as
        // faster, so the line thins more than the unscaled one (which stays near full width).
        val pts = (0..5).map { Sample(it * 10.0, 0.0, 1.0, it * 100.0) }
        val unscaled = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 1.0)
        val scaled = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 5.0)
        assertTrue(unscaled.halfWidths.max() > 1.8)
        assertTrue(scaled.halfWidths.max() < unscaled.halfWidths.max())
    }

    @Test fun speedKeepsAnEvenWidthThroughAConstantSpeedCorner() {
        // Right then up at a steady sample rate: the nib's path length per unit time never
        // changes, so a speed-thinned line must keep an even width through the corner. Reading the
        // raw sample motion over a time window (not the corner-cutting smoothed centerline, nor a
        // sample-count window that collapses onto the bunched corner) is what keeps the corner from
        // reading a false slow-down and ballooning into a blob. speedScale puts it mid-band so any
        // dip would show.
        val right = (0..10).map { Sample(it * 8.0, 0.0, 1.0, it * 8.0) }
        val up = (1..10).map { Sample(80.0, -it * 8.0, 1.0, (10 + it) * 8.0) }
        val g = StrokeEngine.build(right + up, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 0.5)
        val maxHw = g.halfWidths.max().toDouble()
        val minHw = g.halfWidths.min().toDouble()
        assertTrue("partially thinned, not saturated", maxHw < 1.9 && minHw > 0.5)
        assertEquals("even width through the corner", maxHw, minHw, 1e-5)
    }

    // --- Direction smoothing & end-width hold ---
    @Test fun calligraphyRibbonIsThinnedByDirection() {
        // An upward calligraphy ribbon is thinned by the direction term; the swept disc rounds its
        // ends regardless, so only the half-widths are interesting here.
        val pts = (0..6).map { Sample(0.0, -it * 10.0, 1.0) } // straight up
        val g = StrokeEngine.build(pts, 6.0, true, 0.40, 0.60)
        assertTrue("upward calligraphy ribbon is thinned by the direction term",
            g.hw(0) < 1.5)
    }

    @Test fun calligraphyStrayLiftOffSampleDoesNotSwellTheEnd() {
        // Travel in the thin (nib-edge) direction, then a single stray sample jumps the other way as
        // the pen lifts. The direction-confirm window still sees the thin ink just before it, so the
        // last half-width stays at the thin body width instead of ballooning into a fat end dot.
        val ds = 0.6
        val up = (0..20).map { Sample(0.0, -it * 4.0, 1.0) }   // travel -y: thin (1 - ds)
        val stray = Sample(0.0, -20 * 4.0 + 3.0, 1.0)          // one sample back down (+y): thick
        val g = StrokeEngine.build(up + stray, 6.0, false, 1.0, ds, smooth = false)
        var body = 0.0
        for (i in 0 until g.pointCount - 1) if (g.hw(i) > body) body = g.hw(i)
        assertTrue("a stray lift-off sample must not swell past the body width",
            g.hw(g.pointCount - 1) <= body + 1e-6)
    }

    @Test fun calligraphyStrayPenDownSampleDoesNotSwellTheStart() {
        // The mirror of the lift-off case: a stray first move in the broad (thick) direction at
        // pen-down, then the real stroke travels thin. The start is confirmed just like the end, so
        // the lone thick pen-down move is dropped and the first half-width stays at the thin body
        // width instead of opening with a fat dot.
        val ds = 0.6
        val stray = Sample(0.0, -3.0, 1.0)                     // first move jumps +y: thick
        val up = (0..20).map { Sample(0.0, -it * 4.0, 1.0) }   // then travel -y: thin (1 - ds)
        val g = StrokeEngine.build(listOf(stray) + up, 6.0, false, 1.0, ds, smooth = false)
        var body = 0.0
        for (i in 1 until g.pointCount) if (g.hw(i) > body) body = g.hw(i)
        assertTrue("a stray pen-down sample must not swell past the body width",
            g.hw(0) <= body + 1e-6)
    }

    @Test fun calligraphySustainedThickStrokeReachesFullWidth() {
        // The confirmation only delays the onset of thickening, it does not cap it: a long stroke in
        // the broad direction still reaches full thick width by the end.
        val ds = 0.6
        val pts = (0..40).map { Sample(0.0, it * 4.0, 1.0) }   // travel +y (thick) for 160 px
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, ds, smooth = false)
        assertEquals(3.0 * (1.0 + ds), g.hw(g.pointCount - 1), 1e-6) // half = 3 · direction, thick = 1 + ds
    }

    @Test fun calligraphyConfirmedThickeningFillsTheLeadIn() {
        // A mid (horizontal) lead-in, then a long sustained turn into the broad (thick, +y) face.
        // Once the heading is confirmed the opening grows the thick width back over the lead-in, so
        // the start of the downstroke is already thick rather than thin for the first few px. A few
        // px into the run the half-width is near full thick (≈4.8 here); without the lead-in fill it
        // would still sit at the mid width (3.0).
        val horizontal = (0..8).map { Sample(it.toDouble(), 0.0, 1.0) }   // travel +x: mid
        val down = (1..40).map { Sample(8.0, it.toDouble(), 1.0) }        // travel +y: thick
        val g = StrokeEngine.build(horizontal + down, 6.0, false, 1.0, 0.6, smooth = false)
        assertTrue("the start of a confirmed downstroke must be thick, not a thin lead-in",
            g.hw(15) > 4.0)   // index 15 is the 7th downstroke sample, ~6 px past the corner
    }

    @Test fun highlighterEndsHeldToBodyWidth() {
        // The highlighter holds its ends, so the swept end discs round the line at full body width;
        // with pressure off every half-width is already the full 8.0.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val g = StrokeEngine.build(pts, 16.0, false, 1.0, 0.0, holdEnds = true)
        assertEquals(8.0, g.hw(0), 1e-6) // 16 × 1.0 / 2
        assertEquals(8.0, g.hw(g.pointCount - 1), 1e-6)
    }

    @Test fun penEndsHeldToBodyWidth() {
        // The regular pen holds its ends to the body half-width (ds = 0 ⇒ the pure-pressure value),
        // so the swept end discs round the line at full width.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, holdEnds = true)
        assertEquals(1.5, g.hw(0), 1e-6) // 3.0 × 1.0 / 2
        assertEquals(1.5, g.hw(g.pointCount - 1), 1e-6)
    }

    @Test fun penEndsDoNotPinchAtLightPenDownAndUp() {
        // Pen-down and pen-up samples arrive light; without the end-width hold the end disc would
        // shrink to ~0.62 (the 0.1-pressure tip) against a ~1.5 body. The hold lifts the ends to the
        // settled body pressure so each end disc meets the line at nearly full width (no pinch), and
        // never overshoots it (no bulge past the ribbon).
        val pts = (0..11).map { i -> Sample(i * 10.0, 0.0, if (i == 0 || i == 11) 0.1 else 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, holdEnds = true)
        val body = g.halfWidths.max().toDouble()
        assertTrue("head should not pinch to the light tip", g.hw(0) > 1.4)
        assertTrue("tail should not pinch to the light tip", g.hw(g.pointCount - 1) > 1.4)
        assertTrue("ends never exceed the body width", g.hw(0) <= body + 1e-6)
        assertTrue(g.hw(g.pointCount - 1) <= body + 1e-6)
    }

    @Test fun penHoldOnlyRaisesTheEndsNeverThinsTheMiddle() {
        // The hold only lifts the end samples up to the inner body width; a mid-stroke pressure dip
        // is left alone, so the ribbon still narrows in the middle where the pen was pressed lighter.
        val pts = (0..11).map { i -> Sample(i * 10.0, 0.0, if (i in 5..6) 0.2 else 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, holdEnds = true)
        assertTrue("ends held to the body width", g.hw(0) > 1.4 && g.hw(g.pointCount - 1) > 1.4)
        assertTrue("mid-stroke dip survives", g.halfWidths.min() < 1.2)
    }

    @Test fun calligraphyDotTakesTheDotWidth() {
        // A finished dot-sized stroke (4 px, under DOT_MAX_LEN) can never confirm a thick heading,
        // so it takes the dot width whole: every half-width is 3 · (1 + ds · DOT_DIR_Y), slightly
        // past the broad face, not the near-invisible thin extreme the confirm window would
        // otherwise pin it to.
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(2.0, 0.0, 1.0), Sample(4.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6, smooth = false)
        for (i in 0 until g.pointCount) assertEquals(3.0 * 1.9, g.hw(i), 1e-6)
    }

    @Test fun calligraphyDotStaysThinWhileThePenIsDown() {
        // The same dot-sized stroke mid-draw (finished = false) keeps the confirmed-thin width, so
        // the live preview never opens thick at pen-down and snaps back once the stroke grows.
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(2.0, 0.0, 1.0), Sample(4.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6, smooth = false, finished = false)
        for (i in 0 until g.pointCount) assertEquals(3.0 * 0.4, g.hw(i), 1e-6)
    }

    @Test fun calligraphySingleSampleDotTakesTheDotWidth() {
        // A single-sample calligraphy tap is the extreme dot: its one swept disc takes the dot
        // width too, instead of the mid (ty = 0) width.
        val g = StrokeEngine.build(listOf(Sample(0.0, 0.0, 1.0)), 6.0, false, 1.0, 0.6)
        assertEquals(3.0 * 1.9, g.hw(0), 1e-6)
    }

    @Test fun calligraphyShortTickPastTheDotLengthStaysThin() {
        // Just past DOT_MAX_LEN the dot rule no longer applies: a 6 px tick is still shorter than
        // the confirm window, so it keeps the unconfirmed thin width as before.
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(0.0, 3.0, 1.0), Sample(0.0, 6.0, 1.0))
        val g = StrokeEngine.build(pts, 6.0, false, 1.0, 0.6, smooth = false)
        for (i in 0 until g.pointCount) assertEquals(3.0 * 0.4, g.hw(i), 1e-6)
    }

    @Test fun calligraphyWidthGlidesAcrossADirectionChange() {
        // The nib width is low-passed, so when an L-stroke turns from a long rightward run
        // (thick horizontal regime) into a long upward run (thin vertical regime), the width
        // keeps easing down for several samples past the corner instead of snapping the
        // instant the tangent flips. Without the direction low-pass the upward samples would
        // all sit at the thin regime immediately.
        val pts = (0..9).map { Sample(it * 10.0, 0.0, 1.0) } +
            (1..14).map { Sample(90.0, -it * 10.0, 1.0) }
        val g = StrokeEngine.build(pts, 6.0, true, 0.40, 0.60)
        val corner = 10 // first sample of the upward run
        val settled = g.hw(g.pointCount - 1)
        assertTrue("width should still be mid-transition just past the corner",
            g.hw(corner + 1) > settled + 1e-6)
        assertTrue("width should still be easing several samples past the corner",
            g.hw(corner + 3) > settled + 1e-6)
        assertTrue("and the transition is monotone (no snap-back)",
            g.hw(corner + 1) >= g.hw(corner + 3) - 1e-6)
        assertTrue("ends thinner than it started", settled < g.hw(0))
    }
}
