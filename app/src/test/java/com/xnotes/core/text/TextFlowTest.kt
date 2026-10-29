package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFlowTest {

    private fun flowOf(vararg lines: String): TextFlow = TextFlow().apply {
        lines.forEach { paragraphs.add(Paragraph(mutableListOf(Run(it)))) }
    }

    @Test
    fun plainTextJoinsParagraphsWithNewlines() {
        assertEquals("one\ntwo\n", flowOf("one", "two", "").plainText())
        assertEquals("", TextFlow().plainText())
    }

    @Test
    fun globalOffsetRoundTripsThroughEveryPosition() {
        val flow = flowOf("ab", "", "cde")
        val text = flow.plainText()
        for (g in 0..text.length) {
            val pos = flow.posAtGlobal(g)
            assertEquals("global $g", g, flow.globalOffset(pos))
        }
        assertEquals(FlowPos(0, 0), flow.posAtGlobal(0))
        assertEquals(FlowPos(1, 0), flow.posAtGlobal(3))
        assertEquals(FlowPos(2, 3), flow.posAtGlobal(999))
        assertEquals(FlowPos(2, 3), flow.endPos())
    }

    @Test
    fun emptinessIgnoresASingleDefaultBlankParagraph() {
        assertTrue(TextFlow().isEmpty)
        assertTrue(flowOf("").isEmpty)
        assertFalse(flowOf("x").isEmpty)
        assertFalse(flowOf("", "").isEmpty)
        val styledBlank = TextFlow().apply {
            paragraphs.add(Paragraph(list = ListKind.BULLET))
        }
        assertFalse(styledBlank.isEmpty)
    }

    @Test
    fun deepCopyIsIndependent() {
        val flow = flowOf("hello")
        flow.paragraphs[0].runs[0].style = CharStyle(bold = true, color = Rgba(1, 2, 3, 255))
        flow.paragraphs[0].align = ParaAlign.CENTER
        val copy = flow.deepCopy()
        assertNotSame(flow.paragraphs[0], copy.paragraphs[0])
        assertNotSame(flow.paragraphs[0].runs[0], copy.paragraphs[0].runs[0])
        copy.paragraphs[0].runs[0].text = "changed"
        copy.paragraphs.add(Paragraph())
        assertEquals("hello", flow.paragraphs[0].plainText())
        assertEquals(1, flow.paragraphs.size)
        assertEquals(ParaAlign.CENTER, copy.paragraphs[0].align)
        assertEquals(CharStyle(bold = true, color = Rgba(1, 2, 3, 255)), copy.paragraphs[0].runs[0].style)
    }

    @Test
    fun wordBoundaryWalksForwardAcrossWordsPunctuationAndParagraphs() {
        val flow = flowOf("foo bar.baz", "next")
        fun fwd(g: Int) = flow.globalOffset(wordBoundary(flow, flow.posAtGlobal(g), forward = true))
        assertEquals(3, fwd(0))   // over "foo", stop before the space
        assertEquals(7, fwd(3))   // skip space, over "bar", stop before '.'
        assertEquals(8, fwd(7))   // over "."
        assertEquals(11, fwd(8))  // over "baz" to the paragraph end
        assertEquals(16, fwd(11)) // skip newline, over "next"
        assertEquals(16, fwd(16)) // already at the end: no move
    }

    @Test
    fun wordBoundaryWalksBackwardAcrossWordsPunctuationAndParagraphs() {
        val flow = flowOf("foo bar.baz", "next")
        fun back(g: Int) = flow.globalOffset(wordBoundary(flow, flow.posAtGlobal(g), forward = false))
        assertEquals(12, back(16)) // over "next"
        assertEquals(8, back(12))  // skip newline, over "baz"
        assertEquals(7, back(8))   // over "."
        assertEquals(4, back(7))   // over "bar"
        assertEquals(0, back(4))   // skip space, over "foo"
        assertEquals(0, back(0))   // already at the start: no move
    }

    @Test
    fun rangeNormalizationOrdersEndpoints() {
        val a = FlowPos(0, 2)
        val b = FlowPos(1, 0)
        assertEquals(FlowRange(a, b), FlowRange(b, a).normalized())
        assertTrue(FlowRange.caret(a).collapsed)
        assertTrue(a < b)
    }

    @Test
    fun everyHeadingLevelIsBiggerThanTheNextAndBiggerThanBody() {
        // h5 and h6 both sat at body size once, which made three levels look alike.
        val body = TextFlow.DEFAULT_SIZE_PT
        val sizes = (1..Paragraph.MAX_HEADING).map { Paragraph.headingSizePt(it, body) }
        for (i in 0 until sizes.size - 1) {
            assertTrue("h${i + 1} vs h${i + 2}", sizes[i] > sizes[i + 1])
        }
        assertTrue(sizes.last() > body)
        assertEquals(2.0 * body, sizes.first(), 1e-9)
    }

    @Test
    fun factorySizeIsInverselyProportionalToTheScreensShorterSide() {
        // A Galaxy Tab S9 FE (1440px at 280dpi) and a Galaxy A14 (1080px at 420dpi).
        assertEquals(12.0, FlowDefaults.sizeForScreen(1440 * 160 / 280.0), 0.0)
        assertEquals(24.0, FlowDefaults.sizeForScreen(1080 * 160 / 420.0), 0.0)
        assertEquals(8.0, FlowDefaults.sizeForScreen(823.0 * 1.5), 0.0)
        assertEquals(6.0, FlowDefaults.sizeForScreen(5000.0), 0.0)
        assertEquals(36.0, FlowDefaults.sizeForScreen(100.0), 0.0)
        assertEquals(TextFlow.DEFAULT_SIZE_PT, FlowDefaults.sizeForScreen(0.0), 0.0)
    }

    @Test
    fun headingsAreBoldAndScaleWithTheFlowsBaseSize() {
        val flow = TextFlow().apply { defaultSizePt = 10.0 }
        val heading = Paragraph(headingLevel = 3)
        val small = heading.textSizePt(flow)
        flow.defaultSizePt = 20.0
        assertEquals(2.0, heading.textSizePt(flow) / small, 1e-9)
        assertEquals(20.0, Paragraph().textSizePt(flow), 0.0)
        // The level sizes a heading's text, so its own style leaves the size unset.
        assertTrue(Paragraph.HEADING_STYLE.bold)
        assertNull(Paragraph.HEADING_STYLE.sizePt)
    }

    // --- automatic direction ---

    @Test
    fun autoReadsTheFirstStrongLetterOfTheLine() {
        fun dirOf(text: String) = Paragraph(mutableListOf(Run(text))).effectiveDir()
        assertEquals(ParaDir.RTL, dirOf("سلام دوست من"))
        assertEquals(ParaDir.RTL, dirOf("  — سلام"))
        assertEquals(ParaDir.LTR, dirOf("hello there"))
        assertEquals(ParaDir.LTR, dirOf("123 hello"))
        assertEquals(ParaDir.LTR, dirOf(""))
        assertEquals(ParaDir.LTR, dirOf(" ... "))
    }

    @Test
    fun anExplicitDirectionWinsOverAuto() {
        val rtl = Paragraph(mutableListOf(Run("hello")), dir = ParaDir.RTL)
        assertEquals(ParaDir.RTL, rtl.effectiveDir())
        val ltr = Paragraph(mutableListOf(Run("سلام")), dir = ParaDir.LTR)
        assertEquals(ParaDir.LTR, ltr.effectiveDir())
    }

    @Test
    fun firstStrongSkipsWeakCharactersToTheLetter() {
        assertEquals(ParaDir.RTL, ParaDir.firstStrongOf("۱۲۳ سلام"))
        assertEquals(ParaDir.RTL, ParaDir.firstStrongOf("\"سلام\" in quotes"))
        assertEquals(ParaDir.LTR, ParaDir.firstStrongOf("\"hello\" said the man"))
        assertEquals(ParaDir.LTR, ParaDir.firstStrongOf("", fallback = ParaDir.LTR))
    }
}
