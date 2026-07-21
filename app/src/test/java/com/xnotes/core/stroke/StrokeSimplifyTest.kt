package com.xnotes.core.stroke

import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSimplifyTest {

    private fun flatWidths(n: Int) = FloatArray(n) { 1.5f }

    private fun line(n: Int, spacing: Double) =
        (0 until n).map { Sample(it * spacing, 5.0, 1.0, it * 3.0) }

    /** Min distance from [s] to the polyline through [pts]. */
    private fun distToPolyline(s: Sample, pts: List<Sample>): Double {
        var best = Double.MAX_VALUE
        for (i in 1 until pts.size) {
            val ax = pts[i - 1].x
            val ay = pts[i - 1].y
            val dx = pts[i].x - ax
            val dy = pts[i].y - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq < 1e-12) 0.0 else (((s.x - ax) * dx + (s.y - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
            val d = hypot(s.x - (ax + t * dx), s.y - (ay + t * dy))
            if (d < best) best = d
        }
        return best
    }

    @Test fun collinearRunKeepsEndsAndGapSpacing() {
        val samples = line(100, 0.5) // 49.5 px of straight ink
        val out = StrokeSimplify.simplify(samples, flatWidths(100), 0.1)

        assertTrue("straight runs should reduce hard", out.size < 30)
        // The protected end runs survive verbatim.
        for (i in 0 until StrokeSimplify.END_KEEP) {
            assertSame(samples[i], out[i])
            assertSame(samples[100 - 1 - i], out[out.size - 1 - i])
        }
        // No kept-to-kept arc opens past MAX_GAP (+ one original spacing of slack).
        for (i in 1 until out.size) {
            val gap = hypot(out[i].x - out[i - 1].x, out[i].y - out[i - 1].y)
            assertTrue("gap $gap too wide", gap <= StrokeSimplify.MAX_GAP + 0.5 + 1e-9)
        }
    }

    @Test fun everyDroppedSampleStaysWithinEps() {
        val samples = (0 until 200).map { Sample(it * 0.5, sin(it * 0.5 / 5.0) * 3.0, 1.0) }
        val eps = 0.1
        val out = StrokeSimplify.simplify(samples, flatWidths(200), eps)
        assertTrue(out.size < samples.size)
        for (s in samples) {
            assertTrue("sample strayed past eps", distToPolyline(s, out) <= eps + 1e-9)
        }
    }

    @Test fun widthSpikeSurvivesPositionSimplification() {
        val samples = line(100, 0.5)
        val hw = flatWidths(100)
        hw[50] = 2.5f // a pressure bulge mid-line: invisible to position RDP, kept by the width channel
        val out = StrokeSimplify.simplify(samples, hw, 0.1)
        assertTrue(out.size < 100)
        assertTrue("the width spike's sample must survive", out.any { it === samples[50] })
    }

    @Test fun sharpCornerKeepsItsOriginalNeighborhood() {
        val right = (0 until 60).map { Sample(it * 0.5, 0.0, 1.0) }
        val up = (1 until 60).map { Sample(29.5, it * 0.5, 1.0) }
        val samples = right + up
        val out = StrokeSimplify.simplify(samples, flatWidths(samples.size), 0.1)
        assertTrue(out.size < samples.size)
        // Every original sample within CORNER_KEEP_ARC of the apex survives, so the EMA
        // re-smooths the corner at its original density.
        val apex = 59
        var cum = 0.0
        for (i in apex + 1 until samples.size) {
            cum += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
            if (cum > StrokeSimplify.CORNER_KEEP_ARC) break
            assertTrue("post-apex sample $i must survive", out.any { it === samples[i] })
        }
        cum = 0.0
        for (i in apex - 1 downTo 0) {
            cum += hypot(samples[i + 1].x - samples[i].x, samples[i + 1].y - samples[i].y)
            if (cum > StrokeSimplify.CORNER_KEEP_ARC) break
            assertTrue("pre-apex sample $i must survive", out.any { it === samples[i] })
        }
    }

    @Test fun nearDotStrokesAreLeftAlone() {
        // Arc 8 px <= MIN_ARC: the calligraphy dot rule judges the smoothed arc, so the
        // density (and thus the smoothed arc) of near-dot strokes must not move.
        val samples = line(41, 0.2) // arc 8.0
        val out = StrokeSimplify.simplify(samples, flatWidths(41), 0.1)
        assertSame(samples, out)
    }

    @Test fun tinyAndMismatchedInputsAreLeftAlone() {
        val few = line(8, 2.0)
        assertSame(few, StrokeSimplify.simplify(few, flatWidths(8), 0.1))
        val samples = line(100, 0.5)
        assertSame(samples, StrokeSimplify.simplify(samples, flatWidths(99), 0.1)) // defensive
    }

    @Test fun rePassStaysWithinCombinedTolerance() {
        // The writer gate keeps ink from being re-simplified in practice, but a re-pass must
        // still degrade gracefully: each pass adds at most its own eps of deviation (never a
        // collapse), so two passes stay within 2·eps of the original ink, and the ends and gap
        // cap still hold.
        val samples = (0 until 200).map { Sample(it * 0.5, sin(it * 0.5 / 5.0) * 3.0, 1.0) }
        val once = StrokeSimplify.simplify(samples, flatWidths(200), 0.1)
        val twice = StrokeSimplify.simplify(once, FloatArray(once.size) { 1.5f }, 0.1)
        for (s in samples) {
            assertTrue("re-pass strayed past 2·eps", distToPolyline(s, twice) <= 0.2 + 1e-9)
        }
        for (i in 1 until twice.size) {
            val gap = hypot(twice[i].x - twice[i - 1].x, twice[i].y - twice[i - 1].y)
            assertTrue(gap <= StrokeSimplify.MAX_GAP + 3.0 + 1e-9) // pass-1 spacing is the new raw spacing
        }
        assertSame(samples[0], twice.first())
        assertSame(samples[199], twice.last())
    }
}
