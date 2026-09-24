package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CanvasState.isDocumentEndVisible] gates the pull-to-add-page elastic. It must stay false at the
 * top of a long document so a stray bottom scroll-clamp (e.g. a transient bad scroll/layout state on
 * first open) can never spuriously offer "pull to add page" while there is still document below.
 */
class DocumentEndVisibleTest {

    private fun opened(pages: Int): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
            establishInitialView() // fit width, pinned to the top of page 0
        }

    @Test fun endHiddenAtTopOfLongDocument() {
        val st = opened(20)
        assertFalse(st.isDocumentEndVisible())
    }

    @Test fun endVisibleWhenScrolledToBottom() {
        val st = opened(20)
        st.scrollY = st.maxScrollY()
        assertTrue(st.isDocumentEndVisible())
    }

    @Test fun endVisibleForShortDocumentThatFits() {
        val st = opened(1) // a single page that fits the viewport: its end is always on screen
        assertTrue(st.isDocumentEndVisible())
    }
}
