package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

/** A floating toolbar covers an edge of the page view; every page must still be reachable clear of it. */
class ToolbarCoverTest {

    private fun state(pages: Int = 3, verticalScroll: Boolean = true): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            this.verticalScroll = verticalScroll
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test fun theFirstPageOpensBelowATopBar() {
        for (vertical in listOf(true, false)) {
            val st = state(verticalScroll = vertical)
            st.insetTop = 90.0
            st.establishInitialView()
            val top = st.contentToViewport(Pt(0.0, st.pageRects[0].top)).y
            assertEquals(90.0 + CanvasState.TOP_GAP, top, 1e-6)
        }
    }

    @Test fun theLastPageScrollsClearOfABottomBar() {
        val st = state(pages = 6)
        st.insetBottom = 90.0
        st.establishInitialView()
        st.scrollBy(0.0, 1e9)
        val end = st.contentToViewport(Pt(0.0, st.contentH)).y
        assertEquals(st.viewportH - 90.0, end, 1.0)
    }

    @Test fun fitWidthFillsTheWidthBesideASideRail() {
        val st = state()
        st.insetLeft = 90.0
        st.establishInitialView()
        assertEquals(st.clearW, st.contentW * st.zoom, 1e-6)
        assertEquals(90.0, st.contentToViewport(Pt(0.0, 0.0)).x, 1e-6)
    }

    @Test fun aShortDocumentCentresInTheClearArea() {
        val st = state(pages = 1)
        st.insetTop = 200.0
        st.setView(0.1, 0.0, 0.0)
        val o = st.origin()
        val h = st.contentH * st.zoom
        assertEquals(200.0 + (st.clearH - h) / 2.0, o.y, 1e-6)
    }
}
