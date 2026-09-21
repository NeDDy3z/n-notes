package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the page the infinite canvas's PDF export cuts for itself. */
class CanvasPdfLayoutTest {

    private val dpi = PageSize.DEFAULT_DPI
    private val base = 72.0 / dpi

    @Test fun contentKeepsTheOneToOneMapping() {
        val l = CanvasPdfLayout.of(Rect(100.0, 50.0, 600.0, 400.0), dpi)
        assertEquals(base, l.scale, 1e-12)
        assertEquals(1.0, l.zoomEquivalent(dpi), 1e-12)
    }

    @Test fun marginSurroundsTheContentEvenly() {
        val body = Rect(100.0, 50.0, 600.0, 400.0)
        val cover = CanvasPdfLayout.of(body, dpi).cover
        val left = body.left - cover.left
        assertTrue("margin is positive", left > 0.0)
        assertEquals(left, body.top - cover.top, 1e-9)
        assertEquals(left, cover.right - body.right, 1e-9)
        assertEquals(left, cover.bottom - body.bottom, 1e-9)
    }

    @Test fun aTinyDoodleStillGetsAVisibleMargin() {
        // 2% of 4px would be sub-pixel; the floor is what makes the edge show at all.
        val cover = CanvasPdfLayout.of(Rect(0.0, 0.0, 4.0, 4.0), dpi).cover
        assertTrue("margin at least the floor", -cover.left >= 0.08 * dpi - 1e-9)
    }

    @Test fun aHugeCanvasGetsACappedMarginNotAProportionalOne() {
        val cover = CanvasPdfLayout.of(Rect(0.0, 0.0, 500_000.0, 1000.0), dpi).cover
        assertEquals(0.5 * dpi, -cover.left, 1e-9)
    }

    @Test fun aCanvasPastThePdfCeilingShrinksInsteadOfCropping() {
        // 60000 content px at 150 dpi is 28800 pt — twice what a PDF page may be.
        val l = CanvasPdfLayout.of(Rect(0.0, 0.0, 60_000.0, 6_000.0), dpi)
        assertTrue("width fits the ceiling", l.widthPoints <= CanvasPdfLayout.MAX_PAGE_POINTS + 1e-6)
        assertTrue("height fits the ceiling", l.heightPoints <= CanvasPdfLayout.MAX_PAGE_POINTS + 1e-6)
        assertEquals("longest side pinned to the ceiling", CanvasPdfLayout.MAX_PAGE_POINTS, l.widthPoints, 1e-6)
        assertTrue("shrunk below 1:1", l.scale < base)
        // Aspect ratio survives: the whole page shrinks, it is not squashed on one axis.
        assertEquals(l.cover.w / l.cover.h, l.widthPoints / l.heightPoints, 1e-9)
        // And the ruling coarsens as it would on screen at that zoom.
        assertTrue("reads as zoomed out", l.zoomEquivalent(dpi) < 1.0)
    }

    @Test fun aTallCanvasIsCappedOnItsOwnAxis() {
        val l = CanvasPdfLayout.of(Rect(0.0, 0.0, 6_000.0, 60_000.0), dpi)
        assertEquals(CanvasPdfLayout.MAX_PAGE_POINTS, l.heightPoints, 1e-6)
        assertTrue(l.widthPoints < CanvasPdfLayout.MAX_PAGE_POINTS)
    }

    @Test fun anEmptyCanvasExportsOneBlankA4() {
        val (w, h) = PageSize.A4.pixels(com.xnotes.core.model.Orientation.PORTRAIT, dpi)
        for (empty in listOf(null, Rect(0.0, 0.0, 0.0, 0.0), Rect(5.0, 5.0, 10.0, 0.0))) {
            val l = CanvasPdfLayout.of(empty, dpi)
            assertEquals(w * base, l.widthPoints, 1e-9)
            assertEquals(h * base, l.heightPoints, 1e-9)
        }
    }

    @Test fun nonFiniteBoundsFallBackRatherThanProducingANanPage() {
        val l = CanvasPdfLayout.of(Rect(0.0, 0.0, Double.NaN, 100.0), dpi)
        assertTrue(l.widthPoints.isFinite() && l.heightPoints.isFinite())
        assertTrue(l.widthPoints > 0.0 && l.heightPoints > 0.0)
    }

    @Test fun aBadDpiFallsBackToTheDefault() {
        assertEquals(
            CanvasPdfLayout.of(Rect(0.0, 0.0, 600.0, 400.0), dpi).scale,
            CanvasPdfLayout.of(Rect(0.0, 0.0, 600.0, 400.0), 0).scale,
            1e-12,
        )
    }

    // The invariant the export stands on: the page is cut from contentBounds, so every item has to
    // land inside it. Real items rather than bare rectangles, because a stroke's paint bounds include
    // its ribbon width and a shape's its stroke weight — the outsets that a cover cut too tight would
    // shave off the edge of the page.
    @Test fun everyItemLandsInsideTheExportedPage() {
        val doc = InfiniteDocument(dpi = dpi)
        doc.add(stroke(-4_000.0, -2_500.0, -3_900.0, -2_400.0))
        doc.add(stroke(0.0, 0.0, 12.0, 9.0))
        doc.add(stroke(7_800.0, 6_100.0, 8_000.0, 6_200.0))
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(-50.0, 900.0), Pt(400.0, 1_200.0), Rgba(1, 2, 3), 24.0))

        val cover = CanvasPdfLayout.of(doc.contentBounds(), dpi).cover
        for (item in doc.items) {
            val b = item.paintBounds()
            assertTrue("${item::class.simpleName} left edge inside the page", b.left >= cover.left)
            assertTrue("${item::class.simpleName} top edge inside the page", b.top >= cover.top)
            assertTrue("${item::class.simpleName} right edge inside the page", b.right <= cover.right)
            assertTrue("${item::class.simpleName} bottom edge inside the page", b.bottom <= cover.bottom)
        }
    }

    // A single dot has almost no extent, and must still give a page cut to the dot rather than
    // tripping the degenerate-bounds check and falling back to a blank A4 with nothing on it.
    @Test fun aSingleDotGetsATinyPageNotTheBlankFallback() {
        val doc = InfiniteDocument(dpi = dpi)
        doc.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(500.0, 500.0, 1.0))))
        val nib = doc.contentBounds()!!
        val l = CanvasPdfLayout.of(nib, dpi)
        assertEquals((nib.w + 2 * 0.08 * dpi) * base, l.widthPoints, 1e-9) // the nib plus the margin floor
        assertEquals(base, l.scale, 1e-12)
        assertTrue("not the A4 fallback", l.widthPoints < 100.0)
    }

    private fun stroke(x0: Double, y0: Double, x1: Double, y1: Double) = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        mutableListOf(Sample(x0, y0, 1.0), Sample(x1, y1, 1.0)),
    )
}
