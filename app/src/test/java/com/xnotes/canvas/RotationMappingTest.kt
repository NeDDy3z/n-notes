package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the display<->page rotation mapping the whole rotated view is built on. */
class RotationMappingTest {

    private fun state(rotation: Int, pages: Int = 3): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            rotationDeg = rotation
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    @Test fun rotatedLayoutSwapsFootprints() {
        val st = state(90)
        val page = st.document.pages[0]
        assertEquals(page.height, st.pageRects[0].w, 1e-9)
        assertEquals(page.width, st.pageRects[0].h, 1e-9)
        assertEquals(page.height, st.displayW(page), 1e-9)
        assertEquals(page.width, st.displayH(page), 1e-9)
    }

    @Test fun displayToPageMapsCornersClockwise() {
        val st = state(90)
        val page = st.document.pages[0]
        val w = page.width
        val h = page.height
        // 90 cw: the page's top-left corner shows at the display's top-right.
        assertPt(Pt(0.0, 0.0), st.displayToPage(page, Pt(h, 0.0)))
        assertPt(Pt(w, 0.0), st.displayToPage(page, Pt(h, w)))
        assertPt(Pt(0.0, h), st.displayToPage(page, Pt(0.0, 0.0)))

        val st180 = state(180)
        assertPt(Pt(0.0, 0.0), st180.displayToPage(page, Pt(w, h)))

        val st270 = state(270)
        // 270 cw: the page's top-left corner shows at the display's bottom-left.
        assertPt(Pt(0.0, 0.0), st270.displayToPage(page, Pt(0.0, w)))
        assertPt(Pt(w, 0.0), st270.displayToPage(page, Pt(0.0, 0.0)))
    }

    @Test fun displayAndPageMapsRoundTrip() {
        for (deg in listOf(0, 90, 180, 270)) {
            val st = state(deg)
            val page = st.document.pages[1]
            for (p in listOf(Pt(0.0, 0.0), Pt(12.5, 300.0), Pt(page.width, page.height))) {
                assertPt(p, st.displayToPage(page, st.pageToDisplay(page, p)))
            }
        }
    }

    @Test fun contentMappingRoundTripsThroughTheLayout() {
        for (deg in listOf(0, 90, 180, 270)) {
            val st = state(deg)
            val p = Pt(101.0, 77.0)
            for (i in st.document.pages.indices) {
                assertPt(p, st.toPageSpace(i, st.fromPageSpace(i, p)))
            }
        }
    }

    @Test fun rectMappingStaysNormalized() {
        for (deg in listOf(90, 180, 270)) {
            val st = state(deg)
            val r = Rect(10.0, 20.0, 100.0, 50.0)
            val mapped = st.fromPageSpaceRect(0, r)
            assertEquals(true, mapped.w > 0 && mapped.h > 0)
            val back = st.toPageSpaceRect(0, mapped)
            assertEquals(r.left, back.left, 1e-9)
            assertEquals(r.top, back.top, 1e-9)
            assertEquals(r.w, back.w, 1e-9)
            assertEquals(r.h, back.h, 1e-9)
        }
    }

    @Test fun vectorMappingMatchesPointMapping() {
        for (deg in listOf(0, 90, 180, 270)) {
            val st = state(deg)
            val a = Pt(300.0, 400.0)
            val v = Pt(17.0, -6.0)
            val expected = st.toPageSpace(0, a + v) - st.toPageSpace(0, a)
            assertPt(expected, st.vectorToPageSpace(v))
        }
    }

    @Test fun affineConjugationCommutesWithTheDisplayMap() {
        for (deg in listOf(0, 90, 180, 270)) {
            val st = state(deg)
            // A content-space rotation about an arbitrary centre (the transform gesture bakes these).
            val world = Affine.rotateAbout(Pt(400.0, 900.0), 0.7).compose(Affine.scaleAbout(Pt(100.0, 100.0), 1.3, 1.3))
            val pageAffine = st.affineToPageSpace(1, world)
            val p = Pt(120.0, 340.0) // page space
            // Applying in page space then mapping out equals mapping out then applying in content space.
            assertPt(world.apply(st.fromPageSpace(1, p)), st.fromPageSpace(1, pageAffine.apply(p)))
        }
    }
}
