package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the paginated (horizontal page-flip) layout, clamping and navigation. */
class PaginatedLayoutTest {

    private fun state(pages: Int, mode: ViewingMode = ViewingMode.SINGLE): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
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

    @Test fun paginatedRenderOriginDoesNotJumpWhenPageCrossesViewportHeight() {
        val st = state(3)
        st.scrollY = 217.0
        val threshold = st.clearH / st.contentH
        val below = st.originFor(st.scrollX, st.scrollY, threshold * (1.0 - 1e-4))
        val above = st.originFor(st.scrollX, st.scrollY, threshold * (1.0 + 1e-4))
        assertEquals(-st.scrollY, below.y, 1e-9)
        assertEquals(-st.scrollY, above.y, 1e-9)
        assertEquals(below.y, above.y, 1e-9)
    }

    @Test fun tallRowKeepsVerticalMovementInsteadOfLockingAtFitHeight() {
        val st = state(4)
        st.paginatedZoomSnap = false
        st.currentRow = 2
        val row = st.rowBounds(st.rowRanges()[st.currentRow])
        st.zoom = (st.clearH + 100.0) / row.h
        assertTrue(row.h * st.zoom > st.clearH)
        val top = st.rowClampedScroll(st.currentRow, st.scrollX, -1e9)
        val bottom = st.rowClampedScroll(st.currentRow, st.scrollX, 1e9)
        assertEquals(row.top * st.zoom - (st.viewportH - st.insetBottom) + minOf(48.0 * st.devicePxPerDp, row.h * st.zoom / 2.0), top.y, 1e-6)
        assertEquals(row.bottom * st.zoom - st.insetTop - minOf(48.0 * st.devicePxPerDp, row.h * st.zoom / 2.0), bottom.y, 1e-6)
        assertTrue("tall pages must remain vertically pannable", bottom.y - top.y > 100.0)
    }

    @Test fun classicFitRestoresOriginalTopEdgeRestrictionAtHighZoom() {
        val st = state(3)
        val row = st.rowBounds(st.rowRanges()[st.currentRow])
        st.zoom = (st.clearH + 100.0) / row.h
        st.paginatedZoomSnap = true

        val restricted = st.rowClampedScroll(st.currentRow, st.scrollX, -1e9)
        assertEquals(-st.insetTop, restricted.y, 1e-6)
        st.paginatedZoomSnap = false
        val free = st.rowClampedScroll(st.currentRow, st.scrollX, -1e9)
        assertTrue(free.y < restricted.y)
    }

    @Test fun verticalClampDoesNotJumpWhenPageCrossesViewportHeight() {
        val st = state(3)
        st.paginatedZoomSnap = false
        val row = st.rowBounds(st.rowRanges()[st.currentRow])
        val threshold = st.clearH / row.h
        val sy = row.centerY * threshold - st.clearCenter().y
        st.zoom = threshold * (1.0 - 1e-4)
        val below = st.rowClampedScroll(st.currentRow, st.scrollX, sy).y
        st.zoom = threshold * (1.0 + 1e-4)
        val above = st.rowClampedScroll(st.currentRow, st.scrollX, sy).y
        assertEquals("vertical scroll clamp must be continuous across fit-height", below, above, 1.0)
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

    @Test fun linkedNeighbourAppearsDuringDragWithoutEnablingHitTests() {
        val st = state(5)
        st.goToPage(2)
        val current = st.drawablePageRange()
        st.flipOffsetX = 380.0

        assertEquals(3, st.linkedNeighbourRow())
        assertEquals(current, st.drawablePageRange())
        assertEquals(null, st.pageIndexAtContent(st.pageRects[3].center))

        st.flipOffsetX = -380.0
        assertEquals(1, st.linkedNeighbourRow())
        st.flipOffsetX = 0.0
        assertEquals(null, st.linkedNeighbourRow())
    }

    @Test fun nextPageStartsAtRightViewportEdgeAtLowZoom() {
        val st = state(4)
        st.zoom = 0.60
        st.goToPage(1)
        val next = st.rowBounds(st.rowRanges()[2])
        val offset = st.linkedNeighbourOffset(2)
        val nextLeft = next.left * st.zoom - st.scrollX + offset.x
        assertEquals(maxOf(st.viewportW.toDouble(), st.rowBounds(st.rowRanges()[1]).right * st.zoom - st.scrollX + 100.0 * st.devicePxPerDp), nextLeft, 1e-6)
        val settledLeft = next.left * st.zoom - st.rowTargetScroll(2).x
        assertEquals(settledLeft, nextLeft - st.linkedPageTravel(2), 1e-6)
    }

    @Test fun previousPageStartsAtLeftViewportEdgeAtLowZoom() {
        val st = state(4)
        st.zoom = 0.60
        st.goToPage(2)
        val previous = st.rowBounds(st.rowRanges()[1])
        val offset = st.linkedNeighbourOffset(1)
        val previousRight = previous.right * st.zoom - st.scrollX + offset.x
        assertEquals(minOf(0.0, st.rowBounds(st.rowRanges()[2]).left * st.zoom - st.scrollX - 100.0 * st.devicePxPerDp), previousRight, 1e-6)
        val settledRight = previous.right * st.zoom - st.rowTargetScroll(1).x
        assertEquals(settledRight, previousRight + st.linkedPageTravel(1), 1e-6)
    }

    @Test fun neighboursStartAtViewportEdgesAtHighZoomEvenWhenPanned() {
        val st = state(4)
        st.zoom = 2.0
        st.goToPage(1)
        // Horizontal pan may change which part of the current page is visible.
        st.scrollX += 165.0
        st.clampScroll()
        val next = st.rowBounds(st.rowRanges()[2])
        val nextLeft = next.left * st.zoom - st.scrollX + st.linkedNeighbourOffset(2).x
        assertEquals(maxOf(st.viewportW.toDouble(), st.rowBounds(st.rowRanges()[1]).right * st.zoom - st.scrollX + 100.0 * st.devicePxPerDp), nextLeft, 1e-6)
        val previous = st.rowBounds(st.rowRanges()[0])
        val previousRight = previous.right * st.zoom - st.scrollX + st.linkedNeighbourOffset(0).x
        assertEquals(minOf(0.0, st.rowBounds(st.rowRanges()[1]).left * st.zoom - st.scrollX - 100.0 * st.devicePxPerDp), previousRight, 1e-6)
    }

    @Test fun linkedRowsNeverOverlapAtHighZoom() {
        val st = state(4)
        st.zoom = 3.0
        st.goToPage(1)
        val current = st.rowBounds(st.rowRanges()[1])
        val next = st.rowBounds(st.rowRanges()[2])
        val nextStart = next.left * st.zoom - st.scrollX + st.linkedNeighbourOffset(2).x
        val currentEnd = current.right * st.zoom - st.scrollX
        assertTrue(nextStart >= st.viewportW)
        assertTrue(nextStart - currentEnd >= 100.0 * st.devicePxPerDp - 1e-6)

        st.goToPage(2)
        val previous = st.rowBounds(st.rowRanges()[1])
        val currentLeft = st.rowBounds(st.rowRanges()[2]).left * st.zoom - st.scrollX
        val previousEnd = previous.right * st.zoom - st.scrollX + st.linkedNeighbourOffset(1).x
        assertTrue(previousEnd <= 0.0)
        assertTrue(currentLeft - previousEnd >= 100.0 * st.devicePxPerDp - 1e-6)
    }

    @Test fun paginatedModeClampsZoomAtSixtyPercentButVerticalDoesNot() {
        val st = state(3)
        st.zoom = 0.9
        st.setZoomAnchored(st.clearCenter(), 0.25)
        assertEquals(0.60, st.zoom, 1e-9)
        st.verticalScroll = true
        st.setZoomAnchored(st.clearCenter(), 0.25)
        assertEquals(0.25, st.zoom, 1e-9)
    }

    @Test fun restoringOldZoomRespectsHorizontalMinimum() {
        val st = state(3)
        st.setView(0.3, 0.0, 0.0)
        assertEquals(0.60, st.zoom, 1e-9)
        st.verticalScroll = true
        st.setView(0.3, 0.0, 0.0)
        assertEquals(0.30, st.zoom, 1e-9)
    }

    @Test fun switchingToHorizontalModeAppliesZoomFloor() {
        val st = state(2)
        st.verticalScroll = true
        st.zoom = 0.3
        st.verticalScroll = false
        st.clampZoomToLimits()
        assertEquals(0.60, st.zoom, 1e-9)
    }

    @Test fun paginatedFloorDoesNotOverrideConfiguredHigherMin() {
        val st = state(2)
        st.minZoom = 0.75
        st.setZoomAnchored(st.clearCenter(), 0.15)
        assertEquals(0.75, st.zoom, 1e-9)
    }

    @Test fun terminalRowHasNoPhantomNeighbour() {
        val st = state(3)
        st.goToPage(2)
        st.flipOffsetX = 1000.0
        assertEquals(null, st.linkedNeighbourRow())
        st.goToPage(0)
        st.flipOffsetX = -1000.0
        assertEquals(null, st.linkedNeighbourRow())
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
