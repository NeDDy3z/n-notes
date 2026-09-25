package com.xnotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageStyleTemplateTest {

    private fun doc(docStyle: PageStyle, pageStyle: PageStyle): Pair<Document, Page> {
        val d = Document()
        d.style = docStyle
        val p = Page(100.0, 100.0)
        p.style = pageStyle
        d.pages.add(p)
        return d to p
    }

    @Test
    fun spacingInheritsAcrossTheBuiltInRulings() {
        val (d, p) = doc(PageStyle(template = "grid", spacing = 80.0), PageStyle(template = "dots"))
        assertEquals("dots", p.resolvedTemplate(d))
        assertEquals(80.0, p.resolvedSpacing(d)!!, 0.0)
    }

    @Test
    fun parametersDoNotLeakIntoADifferentTemplate() {
        val (d, p) = doc(
            PageStyle(template = "lines", spacing = 80.0, params = mapOf("gap" to 3.0)),
            PageStyle(template = "abcdef0123456789"),
        )
        assertNull(p.resolvedSpacing(d))
        assertEquals(emptyMap<String, Double>(), p.resolvedParams(d))
    }

    @Test
    fun aPageWithoutItsOwnTemplateTakesTheDocumentsParameters() {
        val (d, p) = doc(
            PageStyle(template = "abcdef0123456789", params = mapOf("gap" to 3.0, "rows" to 5.0)),
            PageStyle(params = mapOf("gap" to 2.0)),
        )
        assertEquals(mapOf("gap" to 2.0, "rows" to 5.0), p.resolvedParams(d))
    }

    @Test
    fun switchingTemplatesDropsTheOldParameters() {
        val s = PageStyle(template = "lines", spacing = 40.0, params = mapOf("a" to 1.0), accentColor = Rgba(1, 2, 3))
        assertEquals(40.0, s.withTemplate("grid", PageTemplates.NONE).spacing!!, 0.0)
        val other = s.withTemplate("abcdef0123456789", PageTemplates.NONE)
        assertNull(other.spacing)
        assertNull(other.params)
        assertEquals(Rgba(1, 2, 3), other.accentColor)
        // Default on a page that inherits a compatible ruling keeps the spacing.
        assertEquals(40.0, s.withTemplate(null, "dots").spacing!!, 0.0)
    }
}
