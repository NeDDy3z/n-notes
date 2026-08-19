package com.xnotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The margin hierarchy (page -> document -> none) and its conversion to content pixels. */
class PageMarginsTest {

    private fun doc(margins: PageMargins = PageMargins()): Document =
        Document(pages = mutableListOf(Page(400.0, 800.0))).also { it.margins = margins }

    @Test fun noOverrideMeansNoInsets() {
        val d = doc()
        assertTrue(d.pages[0].insets(d).isZero)
        assertEquals(PageInsets.NONE, d.pages[0].insets(d))
    }

    @Test fun fractionsMeasureAgainstTheirOwnAxis() {
        val d = doc()
        d.pages[0].margins = PageMargins(left = 0.25, top = 0.5, right = 0.1, bottom = 0.0)
        val i = d.pages[0].insets(d)
        assertEquals(100.0, i.left, 1e-9) // 25% of 400 wide
        assertEquals(400.0, i.top, 1e-9) // 50% of 800 tall
        assertEquals(40.0, i.right, 1e-9)
        assertEquals(0.0, i.bottom, 1e-9)
    }

    @Test fun pageEdgeOverridesTheDocumentEdgeByEdge() {
        val d = doc(PageMargins(left = 0.5, top = 0.5))
        d.pages[0].margins = PageMargins(left = 0.25)
        val i = d.pages[0].insets(d)
        assertEquals(100.0, i.left, 1e-9) // the page's own
        assertEquals(400.0, i.top, 1e-9) // inherited from the document
        assertEquals(0.0, i.right, 1e-9)
    }

    @Test fun anExplicitZeroOptsAPageOutOfTheDocumentMargin() {
        val d = doc(PageMargins(left = 0.5))
        d.pages[0].margins = PageMargins(left = 0.0)
        assertEquals(0.0, d.pages[0].insets(d).left, 1e-9)
    }

    @Test fun fractionsAreClampedToTheOfferedRange() {
        val d = doc()
        d.pages[0].margins = PageMargins(left = 4.0, right = -1.0)
        val i = d.pages[0].insets(d)
        assertEquals(400.0, i.left, 1e-9) // 100% of the width, no more
        assertEquals(0.0, i.right, 1e-9)
    }

    @Test fun withEdgeSetsAndClearsOneEdge() {
        var m = PageMargins()
        assertTrue(m.isEmpty)
        m = m.withEdge(PageEdge.BOTTOM, 0.3)
        assertEquals(0.3, m.edge(PageEdge.BOTTOM)!!, 1e-9)
        assertFalse(m.isEmpty)
        assertEquals(null, m.edge(PageEdge.TOP))
        m = m.withEdge(PageEdge.BOTTOM, null)
        assertTrue(m.isEmpty)
    }

    @Test fun withEdgeClampsWhatItStores() {
        assertEquals(1.0, PageMargins().withEdge(PageEdge.RIGHT, 7.0).right!!, 1e-9)
        assertEquals(0.0, PageMargins().withEdge(PageEdge.RIGHT, -7.0).right!!, 1e-9)
    }

    @Test fun marginStripsCoverTheGrownPaperExactly() {
        val content = com.xnotes.core.geometry.Rect(0.0, 0.0, 400.0, 800.0)
        val cover = com.xnotes.core.geometry.Rect(-100.0, -40.0, 540.0, 880.0)
        val strips = marginStrips(cover, content)
        assertEquals(4, strips.size)
        val area = strips.sumOf { it.w * it.h }
        assertEquals(cover.w * cover.h - content.w * content.h, area, 1e-9)
        // No strip overlaps the page itself.
        for (s in strips) {
            assertTrue(s.right <= content.left + 1e-9 || s.left >= content.right - 1e-9 ||
                s.bottom <= content.top + 1e-9 || s.top >= content.bottom - 1e-9)
        }
    }

    @Test fun anUnmarginedPageHasNoStrips() {
        val content = com.xnotes.core.geometry.Rect(0.0, 0.0, 400.0, 800.0)
        assertTrue(marginStrips(content, content).isEmpty())
    }

    @Test fun aRuledMarginDrawsOnlyOutsideThePage() {
        val content = com.xnotes.core.geometry.Rect(0.0, 0.0, 400.0, 800.0)
        val cover = com.xnotes.core.geometry.Rect(-128.0, -128.0, 528.0, 928.0)
        val r = com.xnotes.core.FakeRenderer()
        paintMarginPattern(r, PagePattern.GRID, Rgba(0, 0, 0, 255), 64.0, cover, content, cover)
        assertTrue(r.segments.isNotEmpty())
        // Every segment lies wholly in a margin strip: never over the page it frames.
        for ((a, b) in r.segments) {
            val outside = a.x <= content.left && b.x <= content.left ||
                a.x >= content.right && b.x >= content.right ||
                a.y <= content.top && b.y <= content.top ||
                a.y >= content.bottom && b.y >= content.bottom
            assertTrue("segment $a..$b crosses the page", outside)
        }
    }

    @Test fun aRuledPageDrawsInsideAndOutside() {
        val content = com.xnotes.core.geometry.Rect(0.0, 0.0, 400.0, 800.0)
        val cover = com.xnotes.core.geometry.Rect(-128.0, 0.0, 528.0, 800.0)
        val r = com.xnotes.core.FakeRenderer()
        paintPagePattern(r, PagePattern.LINES, Rgba(0, 0, 0, 255), 64.0, cover, cover)
        // Lines run the whole paper, and the first one is a full spacing below the top edge.
        assertTrue(r.segments.all { (a, b) -> a.x == cover.left && b.x == cover.right })
        assertEquals(64.0, r.segments.first().first.y, 1e-9)
    }
}
