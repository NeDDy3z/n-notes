package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the undo/redo flicker fix: applying an undo/redo must repair the page caches **in place**
 * (keeping the surfaces) rather than dropping them for an async rebuild, which blanked every visible
 * page to bare paper for a frame. The old undo path called [CanvasState.invalidateAllCaches], which
 * drops both layers to 0; [CanvasState.repairAllInkInPlace] must leave them standing. Asserted at the
 * cache-map level via [CanvasState.cacheSnapshot] — mirrors [SelectionCacheRepairTest].
 *
 * [CanvasState.repairInkRegions] holds the same contract for the regions an undo actually disturbed,
 * and must repaint only those: repainting every page on every undo tap is what timed out input.
 */
class UndoCacheRepairTest {

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private fun state(background: Boolean = false): CanvasState {
        val page = Page(200.0, 200.0, mutableListOf(dot(20.0, 20.0), dot(120.0, 120.0)))
        if (background) page.pdfPage = 0 // a real PDF-backed page, so a background cache is built and kept
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118))).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
            if (background) paintPageBackground = { _, _, _, _ -> } // a non-null painter (stands in for a PDF)
        }
    }

    @Test fun undoRepairsInkCacheInPlace() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page) // warm the ink cache, as a draw frame would
        assertEquals(1, st.cacheSnapshot().inkPages)

        // Mimic an undo that removed the last-added stroke, then run the undo repair path.
        page.items.removeAt(page.items.size - 1)
        st.repairAllInkInPlace()

        assertEquals(
            "undo must repair the ink cache in place, not drop it (the blank-frame flicker)",
            1, st.cacheSnapshot().inkPages,
        )
    }

    @Test fun undoLeavesBackgroundCacheIntact() {
        val st = state(background = true)
        val page = st.document.pages[0]
        st.cacheFor(page)
        st.backgroundFor(page)
        assertEquals(1, st.cacheSnapshot().inkPages)
        assertEquals(1, st.cacheSnapshot().bgPages)

        page.items.removeAt(page.items.size - 1)
        st.repairAllInkInPlace()

        // The expensive PDF/template background must survive undo — invalidateAllCaches() used to
        // flush it, which is what flickered the PDF layer on every undo/redo.
        assertEquals("undo must not flush the background/PDF cache", 1, st.cacheSnapshot().bgPages)
        assertEquals(1, st.cacheSnapshot().inkPages)
    }

    @Test fun regionRepairRepaintsOnlyWhatTheRegionCovers() {
        val st = state()
        val page = st.document.pages[0]
        val painter = (st.cacheFor(page).surface as FakeRasterSurface).painter

        // A command that named its region (an undone stroke at 20,20) must not cost the whole page:
        // the dot at 120,120 is nowhere near it and stays as it was rasterized.
        painter.ribbonRuns.clear()
        st.repairInkRegions(listOf(page to Rect(10.0, 10.0, 20.0, 20.0)))
        assertEquals("only the strokes overlapping the repaired region repaint", 1, painter.ribbonRuns.size)

        painter.ribbonRuns.clear()
        st.repairAllInkInPlace()
        assertEquals("the whole-page fallback still repaints everything", 2, painter.ribbonRuns.size)
    }

    @Test fun regionRepairKeepsTheCacheInPlace() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page)

        page.items.removeAt(page.items.size - 1)
        st.repairInkRegions(listOf(page to Rect(100.0, 100.0, 40.0, 40.0)))

        assertEquals(
            "a region repair keeps the surface too, or undo flickers again",
            1, st.cacheSnapshot().inkPages,
        )
    }
}
