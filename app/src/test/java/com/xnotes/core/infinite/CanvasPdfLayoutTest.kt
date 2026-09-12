package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PageSize
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
}
