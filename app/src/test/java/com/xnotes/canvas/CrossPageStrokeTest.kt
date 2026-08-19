package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping a stroke uses when the pen walks off one page and onto the next (see
 * `InteractionController.handOverStroke`): the crossing is detected in content space, and the last
 * point drawn is carried into the new page's space so the two segments read as one line.
 */
class CrossPageStrokeTest {

    private fun state(): CanvasState =
        CanvasState(Document.blank(2), FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118))).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test fun theGapBetweenPagesBelongsToNeither() {
        val st = state()
        val gapY = st.pageRects[0].bottom + st.pageGap / 2.0
        val x = st.pageRects[0].left + 20.0
        assertNull(st.pageIndexAtContent(Pt(x, gapY)))
        assertEquals(0, st.pageIndexAtContent(Pt(x, st.pageRects[0].bottom - 1.0)))
        assertEquals(1, st.pageIndexAtContent(Pt(x, st.pageRects[1].top + 1.0)))
    }

    @Test fun theCarriedPointLandsAboveTheNextPage() {
        val st = state()
        // The last sample drawn on page 0, at its very bottom edge.
        val last = Pt(30.0, st.document.pages[0].height)
        val bridge = st.toPageSpace(1, st.fromPageSpace(0, last))
        assertEquals(30.0, bridge.x, 1e-9)
        // Above page 1's content box by the gap, so the ribbon reaches the paper's edge and is
        // clipped there rather than starting inside it.
        assertTrue(bridge.y < 0.0)
        assertEquals(-st.pageGap, bridge.y, 1e-9)
    }

    @Test fun theCarriedPointSurvivesARotatedView() {
        val st = state().apply { rotationDeg = 90; relayout() }
        val page = st.document.pages[1]
        val last = Pt(30.0, st.document.pages[0].height)
        val content = st.fromPageSpace(0, last)
        val bridge = st.toPageSpace(1, content)
        // Whatever the rotation, the carried point is the same place on screen...
        val back = st.fromPageSpace(1, bridge)
        assertEquals(content.x, back.x, 1e-9)
        assertEquals(content.y, back.y, 1e-9)
        // ...and it sits outside the new page's content box, so the join is clipped at the edge.
        assertTrue(bridge.x < 0.0 || bridge.y < 0.0 || bridge.x > page.width || bridge.y > page.height)
    }
}
