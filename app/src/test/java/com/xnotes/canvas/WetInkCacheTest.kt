package com.xnotes.canvas

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What the raster half of the wet cache has to get right: it must stop redrawing ink it has
 * already baked, it must never leave a gap where the baked run meets the live one, and it must
 * hand back the strokes it cannot paint in two pieces so they keep the plain redraw.
 */
class WetInkCacheTest {

    private val cap = 8_000_000L

    private val factory = FakeSurfaceFactory()
    private val cache = WetInkCache(factory)

    private fun sampleAt(i: Int): Sample {
        val u = i * 0.09
        return Sample(
            60.0 + u * 24.0 + sin(u * 2.9) * 8.0,
            80.0 + cos(u * 1.6) * 30.0,
            0.4 + 0.4 * (0.5 + 0.5 * sin(u * 3.1)),
            i * 6.0,
        )
    }

    private fun liveStroke(tool: Tool, count: Int, neon: Boolean = false): Stroke {
        var config = ToolDefaults.configFor(tool)
        if (neon) config = config.copy(neon = true)
        val s = Stroke(tool, config)
        s.finished = false
        for (i in 0 until count) s.addSample(sampleAt(i))
        return s
    }

    /** Draw one frame and hand back what the screen renderer saw. */
    private fun frame(stroke: Stroke, res: Double = 1.0): Pair<Boolean, FakeRenderer> {
        val r = FakeRenderer()
        return cache.paint(r, stroke, res, cap) to r
    }

    /** Ribbon runs painted into the surface the cache is holding now. */
    private fun bakedRuns(): List<Pair<Int, Int>> =
        factory.created.lastOrNull()?.painter?.ribbonRuns.orEmpty()

    @Test fun aShortStrokeIsNotWorthASurface() {
        val (took, _) = frame(liveStroke(Tool.PEN, 12))
        assertFalse("a 12-point stroke should just redraw", took)
        assertTrue("it allocated a surface anyway", factory.created.isEmpty())
    }

    @Test fun aLongStrokeRedrawsOnlyItsMovingTail() {
        val stroke = liveStroke(Tool.PEN, 400)
        val (took, r) = frame(stroke)
        assertTrue("the cache turned a 400-point stroke down", took)
        assertEquals("it should blit exactly one raster", 1, r.rasterDests.size)
        assertEquals(1, r.ribbonRuns.size)
        val (from, count) = r.ribbonRuns[0]
        assertTrue("the live run started at the head", from > 0)
        assertTrue("the live run is not a tail: $count points", count <= 8)
        assertEquals("the live run must reach the nib", stroke.wetRibbon!!.pointCount, from + count)
    }

    @Test fun theTwoRunsOverlapByAPointSoNoGapCanOpen() {
        val stroke = liveStroke(Tool.PEN, 300)
        val (_, screen) = frame(stroke)
        val liveFrom = screen.ribbonRuns[0].first
        // Whatever the baked run ended at, the live one starts one point behind it, so the segment
        // between them is drawn and they share a whole brush disc.
        val last = bakedRuns().last()
        assertEquals(last.first + last.second - 1, liveFrom)
    }

    @Test fun eachPointIsBakedOnceHoweverManyFramesItSurvives() {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        for (i in 0 until 400) {
            stroke.addSample(sampleAt(i))
            repeat(3) { frame(stroke) } // several frames per sample, as a slow hand produces
        }
        // Across every surface the stroke outgrew, since growth carries the pixels over.
        val runs = factory.created.flatMap { it.painter.ribbonRuns }
        val settled = stroke.wetRibbon!!.settledCount
        // Every settled point is painted once, plus the one point each run repeats as its overlap.
        assertEquals(settled + runs.size - 1, runs.sumOf { it.second })
        assertEquals("the bakes must cover the whole settled run", settled, runs.last().let { it.first + it.second })
        assertTrue("almost nothing settled", settled > 300)
    }

    @Test fun theDashPatternCarriesAcrossTheSeam() {
        val stroke = liveStroke(Tool.DASHED, 300)
        val (took, screen) = frame(stroke)
        assertTrue(took)
        val live = screen.dashRuns.single()
        val ribbon = stroke.wetRibbon!!
        // The live run must start the pattern exactly where the baked run's arc left it.
        var arc = 0.0
        for (k in 1..live.first) arc += hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
        assertEquals(arc, live.third, 1e-9)
    }

    @Test fun neonAndTranslucentInkKeepThePlainRedraw() {
        assertFalse("neon must not be baked in pieces", frame(liveStroke(Tool.PEN, 300, neon = true)).first)
        assertFalse("the highlighter must not be baked in pieces", frame(liveStroke(Tool.HIGHLIGHTER, 300)).first)
        assertTrue("neither should have taken a surface", factory.created.isEmpty())
    }

    @Test fun theTaperPenKeepsThePlainRedraw() {
        assertFalse(frame(liveStroke(Tool.TAPER, 300)).first)
        assertTrue(factory.created.isEmpty())
    }

    @Test fun aNewStrokeStartsOverRatherThanInheritingTheLastOnesInk() {
        frame(liveStroke(Tool.PEN, 300))
        val buffers = factory.created.size
        val before = bakedRuns().size
        val (took, _) = frame(liveStroke(Tool.PEN, 300))
        assertTrue(took)
        val runs = bakedRuns()
        assertTrue("the second stroke baked nothing", runs.size > before)
        assertEquals("the second stroke must bake from its own head", 0, runs[before].first)
        assertEquals("a second stroke of the same size should not need a second buffer", buffers, factory.created.size)
    }

    @Test fun aZoomChangeStartsOver() {
        val stroke = liveStroke(Tool.PEN, 300)
        frame(stroke, res = 1.0)
        val before = bakedRuns().size
        frame(stroke, res = 2.0)
        val runs = bakedRuns()
        val fresh = if (runs.size > before) runs[before] else runs.first()
        assertEquals("the rebuild must start from the head", 0, fresh.first)
    }

    @Test fun aSurfaceLargerThanTheCapIsRefused() {
        val r = FakeRenderer()
        assertFalse(cache.paint(r, liveStroke(Tool.PEN, 300), 1.0, maxPixels = 16L))
    }

    @Test fun growthKeepsTheSurfaceOnTheOldPixelGrid() {
        // Every growth blits the old pixels into the new buffer. Anchored off the grid that blit
        // resamples them, and a long stroke would go soft behind the nib one growth at a time.
        val res = 1.7
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        val anchors = ArrayList<Rect>()
        for (i in 0 until 900) {
            stroke.addSample(Sample(20.0 + i * 2.0, 200.0 + sin(i * 0.05) * 40.0, 0.6, i * 5.0))
            // What the cache blits each frame is exactly the page-space rect its surface covers.
            frame(stroke, res).second.rasterDests.lastOrNull()?.let {
                if (anchors.lastOrNull() != it) anchors += it
            }
        }
        assertTrue("the surface never grew", anchors.size > 1)
        for (i in 1 until anchors.size) {
            val dx = (anchors[i - 1].left - anchors[i].left) * res
            val dy = (anchors[i - 1].top - anchors[i].top) * res
            assertEquals("growth $i moved the anchor off the pixel grid in x", Math.round(dx).toDouble(), dx, 1e-9)
            assertEquals("growth $i moved the anchor off the pixel grid in y", Math.round(dy).toDouble(), dy, 1e-9)
            assertTrue("growth $i dropped baked ink off the left", anchors[i].left <= anchors[i - 1].left + 1e-9)
            assertTrue("growth $i dropped baked ink off the top", anchors[i].top <= anchors[i - 1].top + 1e-9)
            assertTrue("growth $i dropped baked ink off the right", anchors[i].right >= anchors[i - 1].right - 1e-9)
            assertTrue("growth $i dropped baked ink off the bottom", anchors[i].bottom >= anchors[i - 1].bottom - 1e-9)
        }
    }

    @Test fun theSurfaceGrowsWithTheStrokeAndCarriesItsPixelsOver() {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        // A long sweep that has to outgrow whatever it was first given.
        for (i in 0 until 900) {
            stroke.addSample(Sample(20.0 + i * 2.0, 200.0 + sin(i * 0.05) * 40.0, 0.6, i * 5.0))
            frame(stroke)
        }
        assertTrue("the surface never grew", factory.created.size > 1)
        for (s in factory.created.dropLast(1)) assertTrue("an outgrown surface was leaked", s.recycled)
        // Growth blits the old pixels in rather than repainting the ink, so the newest surface has
        // only the run that settled after it was made, not the whole stroke.
        assertTrue("growth repainted the ink instead of blitting it", factory.created.last().painter.ops.contains("drawRaster"))
    }
}
