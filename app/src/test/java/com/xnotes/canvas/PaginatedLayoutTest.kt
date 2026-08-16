package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the paginated (horizontal page-flip) layout, clamping and navigation. */
class PaginatedLayoutTest {

    private fun state(pages: Int, mode: ViewingMode = ViewingMode.SINGLE): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118))).apply {
            viewingMode = mode
            verticalScroll = false
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test fun rowsRunLeftToRight() {
        val st = state(3)
        assertEquals(st.pageRects[0].top, st.pageRects[1].top, 1e-9)
        assertTrue(st.pageRects[1].left > st.pageRects[0].right)
        assertTrue(st.pageRects[2].left > st.pageRects[1].right)
        val h = st.document.pages[0].height
        assertEquals(h + 2 * CanvasState.MARGIN, st.contentH, 1e-9)
    }

    @Test fun doubleSpreadsFlipAsOneUnit() {
        val st = state(4, ViewingMode.DOUBLE)
        // Within a spread and between spreads alike, pages sit one page-gap apart.
        assertEquals(st.pageRects[0].right + st.pageGap, st.pageRects[1].left, 1e-9)
        assertEquals(st.pageRects[1].right + st.pageGap, st.pageRects[2].left, 1e-9)
    }

    @Test fun clampConfinesScrollToTheCurrentRow() {
        val st = state(3)
        st.zoom = 1.0
        st.currentRow = 0
        st.scrollX = 1e9
        st.clampScroll()
        val rb0 = st.rowBounds(st.rowRanges()[0])
        assertEquals((rb0.right + st.sideMargin) * st.zoom - st.viewportW, st.scrollX, 1e-6)

        st.currentRow = 1
        st.scrollX = -1e9
        st.clampScroll()
        val rb1 = st.rowBounds(st.rowRanges()[1])
        assertEquals((rb1.left - st.sideMargin) * st.zoom, st.scrollX, 1e-6)
    }

    @Test fun goToPageJumpsTheWindowToTheRow() {
        val st = state(5)
        st.zoom = 1.0
        st.goToPage(3)
        assertEquals(3, st.currentRow)
        val t = st.rowTargetScroll(3)
        assertEquals(t.x, st.scrollX, 1e-6)
        assertEquals(t.y, st.scrollY, 1e-6)
        assertEquals(3, st.currentPageIndex())
    }

    @Test fun fitWidthFitsTheCurrentRow() {
        val st = state(3)
        st.currentRow = 1
        val rowW = st.rowBounds(st.rowRanges()[1]).w + 2 * st.sideMargin
        assertEquals((st.viewportW / rowW).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM), st.fitWidthZoom(), 1e-9)
    }

    @Test fun setViewRestoresTheRowFromScroll() {
        val st = state(6)
        val t = st.rowTargetScroll(4)
        st.setView(1.0, t.x, t.y)
        assertEquals(4, st.currentRow)
    }

    @Test fun atRowEdgeDetectsTheBoundaries() {
        val st = state(3)
        st.zoom = 1.0
        st.currentRow = 1
        st.scrollX = -1e9
        st.clampScroll()
        assertTrue(st.atRowEdge(next = false))
        st.scrollX = 1e9
        st.clampScroll()
        assertTrue(st.atRowEdge(next = true))
    }

    @Test fun flipOffsetShiftsTheOrigin() {
        val st = state(2)
        st.zoom = 1.0
        st.clampScroll()
        val before = st.origin().x
        st.flipOffsetX = 120.0
        assertEquals(before - 120.0, st.origin().x, 1e-9)
    }

    @Test fun settledViewShowsOnlyTheCurrentRow() {
        val st = state(5)
        // Zoom far out so every neighbour's rect would reach into the viewport.
        st.zoom = 0.15
        st.goToPage(2)
        assertEquals(2..2, st.drawablePageRange())
        assertEquals(2..2, st.visiblePageRange())
        // A point inside a hidden neighbour's rect neither draws nor hit-tests.
        val neighbour = st.pageRects[3].center
        assertEquals(null, st.pageIndexAtContent(neighbour))
        assertEquals(3, st.rowIndexOf(3)) // the rect itself is still laid out, just not shown
    }

    @Test fun settledDoubleShowsTheWholeSpread() {
        val st = state(6, ViewingMode.DOUBLE)
        st.zoom = 0.15
        st.goToPage(2)
        assertEquals(2..3, st.drawablePageRange())
    }

    @Test fun edgePullNeverRevealsTheNeighbour() {
        val st = state(5)
        st.zoom = 0.5
        st.goToPage(2)
        // The pull slides the current page against empty background; no neighbour joins.
        st.flipOffsetX = 60.0
        assertEquals(2..2, st.drawablePageRange())
        st.flipOffsetX = -60.0
        assertEquals(2..2, st.drawablePageRange())
    }

    @Test fun flipIsInstantTheNewRowJustAppears() {
        val st = state(5)
        st.zoom = 0.5
        st.goToPage(2)
        // A flip retargets the window in one step; the new row is drawable immediately.
        st.goToPage(3)
        assertEquals(3..3, st.drawablePageRange())
        assertEquals(3, st.currentRow)
    }

    @Test fun goToPageDropsAStalePull() {
        val st = state(5)
        st.flipOffsetX = 50.0
        st.goToPage(3)
        assertEquals(0.0, st.flipOffsetX, 1e-9)
        assertEquals(3..3, st.drawablePageRange())
    }

    @Test fun verticalModeDrawsEverything() {
        val st = state(4).apply { verticalScroll = true; relayout() }
        assertEquals(0..3, st.drawablePageRange())
    }

    @Test fun twoPageNoteCentresEachRowAtFitHeight() {
        // Exactly two pages fit-to-height: the whole strip is narrower than a wide viewport,
        // but each row must still be centred, not the strip.
        val st = state(2).apply {
            viewportW = 1600
            viewportH = 1000
            relayout()
        }
        st.fitHeight()
        val c0 = st.contentToViewport(st.rowBounds(st.rowRanges()[0]).center)
        assertEquals(st.viewportW / 2.0, c0.x, 1e-6)
        st.goToPage(1)
        val c1 = st.contentToViewport(st.rowBounds(st.rowRanges()[1]).center)
        assertEquals(st.viewportW / 2.0, c1.x, 1e-6)
    }

    @Test fun twoPageNoteCentresEachRowWhenZoomedOut() {
        val st = state(2)
        st.zoom = 0.3
        st.clampScroll()
        val c0 = st.contentToViewport(st.rowBounds(st.rowRanges()[0]).center)
        assertEquals(st.viewportW / 2.0, c0.x, 1e-6)
        st.goToPage(1)
        val c1 = st.contentToViewport(st.rowBounds(st.rowRanges()[1]).center)
        assertEquals(st.viewportW / 2.0, c1.x, 1e-6)
    }
}
