package com.xnotes.core.text

import com.xnotes.core.FakeMathTypesetter
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inline math against the fake typesetter: a formula sets twice as wide as the
 * same characters would as text (so 12pt "x^2" is 43.2 drawn against 21.6 read)
 * and three times as tall, which is what lets these tests tell the two apart.
 */
class FlowMathLayoutTest {

    private val math = FakeMathTypesetter()

    private fun layout(t: FakeMathTypesetter? = math) = FlowLayout(FakeTextMeasurer(), t)

    private fun flowWith(para: Paragraph): TextFlow = TextFlow().apply { paragraphs.add(para) }

    private fun mathPara(latex: String) =
        Paragraph(mutableListOf(Run(latex, CharStyle(math = true))))

    /** The decorations on the first line, which carry how a formula is being shown. */
    private fun decosOf(flow: TextFlow, l: FlowLayout): List<Deco> {
        flow.margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        return l.layout(flow, listOf(PageBox(400.0, 200.0)), 150).pages[0].lines[0].decos
    }

    private fun paint(flow: TextFlow, l: FlowLayout): List<String> {
        flow.margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        val frame = l.layout(flow, listOf(PageBox(400.0, 200.0)), 150)
        val r = FakeRenderer()
        FlowPainter.paintPage(r, frame, 0, Rect(0.0, 0.0, 400.0, 200.0))
        return r.ops
    }

    @Test
    fun aDrawnFormulaGoesToThePainterWholeAndAsLatex() {
        val p = mathPara("a + b")
        val l = layout()
        val ops = paint(flowWith(p), l)
        // One op for the whole formula, not one per word of its source.
        assertEquals(listOf("drawMath:a + b@0.0,36.0"), ops.filter { it.startsWith("drawMath:") })
        assertTrue(ops.none { it.startsWith("drawTextRun:") })
    }

    @Test
    fun aRevealedFormulaIsPaintedAsTheTextItIs() {
        val p = mathPara("a + b")
        val l = layout()
        l.revealedMath = { 0 }
        val ops = paint(flowWith(p), l)
        assertTrue(ops.none { it.startsWith("drawMath:") })
        assertTrue(ops.any { it.startsWith("drawTextRun:a@") })
        assertTrue(ops.any { it.startsWith("drawTextRun:b@") })
    }

    @Test
    fun aFormulaMeasuresAsOneBoxNotAsItsCharacters() {
        val p = mathPara("x^2")
        val lines = layout().breakLines(flowWith(p), p, 1000.0)
        assertEquals(1, lines.size)
        assertEquals(43.2, lines[0].width, 1e-9)
    }

    @Test
    fun aFormulaNeverBreaksApartEvenWithSpacesInIt() {
        val p = mathPara("a + b")
        // Far too wide for the line: it has to overflow rather than split, because
        // half a formula is not a formula.
        val lines = layout().breakLines(flowWith(p), p, 40.0)
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].startChar)
        assertEquals(5, lines[0].endChar)
        assertEquals(72.0, lines[0].width, 1e-9)
    }

    @Test
    fun aFormulaStillLetsTheTextAroundItWrap() {
        val p = Paragraph(mutableListOf(Run("aaa ", CharStyle.DEFAULT), Run("x^2", CharStyle(math = true))))
        val lines = layout().breakLines(flowWith(p), p, 50.0)
        assertEquals(2, lines.size)
        assertEquals(4, lines[1].startChar)
        assertEquals(43.2, lines[1].width, 1e-9)
    }

    @Test
    fun aTallFormulaRaisesTheLineItSitsOn() {
        val p = mathPara("x^2")
        val lines = layout().breakLines(flowWith(p), p, 1000.0)
        assertEquals(36.0, lines[0].ascent, 1e-9)
        assertTrue(lines[0].ascent > FakeTextMeasurer().metrics(resolveFont(TextFlow(), p, CharStyle.DEFAULT)).ascent)
    }

    @Test
    fun theCaretInsideAFormulaBringsItsSourceBack() {
        val p = mathPara("a + b")
        val l = layout()
        l.revealedMath = { 0 }
        val lines = l.breakLines(flowWith(p), p, 1000.0)
        assertEquals(36.0, lines[0].width, 1e-9)
        assertEquals(2, lines[0].spaceCount)
    }

    @Test
    fun revealingAndHidingReshapesTheSameParagraph() {
        val p = mathPara("a + b")
        val l = layout()
        var revealed = false
        l.revealedMath = { if (revealed) 0 else -1 }
        val flow = flowWith(p)
        assertEquals(72.0, l.breakLines(flow, p, 1000.0)[0].width, 1e-9)
        revealed = true
        // The paragraph has not changed, so only the reveal can invalidate the cache.
        assertEquals(36.0, l.breakLines(flow, p, 1000.0)[0].width, 1e-9)
        revealed = false
        assertEquals(72.0, l.breakLines(flow, p, 1000.0)[0].width, 1e-9)
    }

    @Test
    fun aFormulaTheTypesetterRejectsStaysReadableAsItsSource() {
        val p = mathPara("\\nope")
        val lines = FlowLayout(FakeTextMeasurer(), FakeMathTypesetter(rejects = setOf("\\nope")))
            .breakLines(flowWith(p), p, 1000.0)
        assertEquals(36.0, lines[0].width, 1e-9)
    }

    @Test
    fun withNoTypesetterAtAllMathRunsAreOrdinaryText() {
        val p = mathPara("x^2")
        val lines = FlowLayout(FakeTextMeasurer()).breakLines(flowWith(p), p, 1000.0)
        assertEquals(21.6, lines[0].width, 1e-9)
    }
    @Test
    fun aDisplayEquationIsSetLargerThanTheInlineOne() {
        val inline = mathPara("x^2")
        val display = Paragraph(mutableListOf(Run("x^2", CharStyle(math = true, mathDisplay = true))))
        val a = layout().breakLines(flowWith(inline), inline, 1000.0)[0]
        val b = layout().breakLines(flowWith(display), display, 1000.0)[0]
        assertTrue(b.width > a.width)
        assertTrue(b.ascent > a.ascent)
    }

    @Test
    fun thePainterIsToldWhichFormItIs() {
        val display = Paragraph(mutableListOf(Run("x^2", CharStyle(math = true, mathDisplay = true))))
        val ops = paint(flowWith(display), layout())
        assertTrue(ops.any { it.startsWith("drawMathDisplay:x^2@") })
    }

    // --- telling a formula apart from the prose around it ---

    @Test
    fun aDrawnFormulaNeedsNoChipBecauseItLooksLikeAFormula() {
        val p = mathPara("x^2")
        assertTrue(decosOf(flowWith(p), layout()).isEmpty())
    }

    @Test
    fun aRevealedFormulaIsChippedSoItCannotReadAsProse() {
        val p = mathPara("x^2")
        val l = layout()
        l.revealedMath = { 0 }
        val deco = decosOf(flowWith(p), l).single()
        assertEquals(MathShow.SOURCE, deco.math)
    }

    @Test
    fun latexTheTypesetterRefusesIsMarkedBrokenNotMerelyOpened() {
        val p = mathPara("\\frac{")
        val l = FlowLayout(FakeTextMeasurer(), FakeMathTypesetter(rejects = setOf("\\frac{")))
        val deco = decosOf(flowWith(p), l).single()
        assertEquals(MathShow.ERROR, deco.math)
    }

    @Test
    fun aTypesetterThatIsNotReadyYetBreaksNothing() {
        // Before the host hands over a renderer everything measures as null, and
        // that must not paint the whole document as broken LaTeX.
        val p = mathPara("x^2")
        val l = FlowLayout(FakeTextMeasurer(), FakeMathTypesetter(ready = false))
        assertEquals(MathShow.SOURCE, decosOf(flowWith(p), l).single().math)
    }

    @Test
    fun revealedSourceSetsInTheMonoFace() {
        val flow = TextFlow()
        val p = mathPara("x^2")
        assertEquals(flow.monoFace, resolveFont(flow, p, CharStyle(math = true)).face)
        assertEquals(flow.defaultFace, resolveFont(flow, p, CharStyle.DEFAULT).face)
    }

    // --- where a formula ends and the text after it begins ---
    //
    // The offset just past a formula is equally its closing edge and the spot
    // before whatever follows, and the caret lands there every time one is typed.
    // Treating it as inside meant an equation reopened the moment anything put
    // the caret back on that boundary, which typing a character and deleting it
    // does. Both the reveal and the insert style now stop short of it.

    /** Which run index the caret at [offset] would type into, by its style. */
    private fun styleTypedAt(para: Paragraph, offset: Int): CharStyle {
        val flow = flowWith(para)
        FlowEditor(flow).replaceRange(FlowRange.caret(FlowPos(0, offset)), "Z")
        return flow.paragraphs[0].runs.first { "Z" in it.text }.style
    }

    private fun revealAt(para: Paragraph, offset: Int): Int {
        var at = 0
        for (run in para.runs) {
            val end = at + run.text.length
            if (run.style.math && offset > at && offset < end) return at
            at = end
        }
        return -1
    }

    @Test
    fun theOffsetJustPastAFormulaIsOutsideIt() {
        val p = mathPara("x^2")
        assertEquals(-1, revealAt(p, 3))
        assertFalse(styleTypedAt(mathPara("x^2"), 3).math)
    }

    @Test
    fun typingACharacterAndDeletingItLeavesTheFormulaDrawn() {
        val flow = TextFlow()
        val ed = FlowEditor(flow)
        ed.replaceRange(FlowRange.caret(FlowPos.START), "x^2", CharStyle(math = true))
        // Type one character past the formula, then take it back out again.
        ed.replaceRange(FlowRange.caret(FlowPos(0, 3)), "c")
        assertEquals(2, flow.paragraphs[0].runs.size)
        ed.deleteRange(FlowRange(FlowPos(0, 3), FlowPos(0, 4)))
        val p = flow.paragraphs[0]
        assertEquals("x^2", p.runs.single().text)
        // Back exactly where typing the formula left it, and still not inside it.
        assertEquals(-1, revealAt(p, 3))
    }

    @Test
    fun theOffsetInsideAFormulaIsInsideIt() {
        val p = mathPara("x^2")
        assertEquals(0, revealAt(p, 2))
        assertTrue(styleTypedAt(mathPara("x^2"), 2).math)
    }

    @Test
    fun theOpeningEdgeOfAFormulaBelongsToWhatComesBefore() {
        val p = mathPara("x^2")
        assertEquals(-1, revealAt(p, 0))
        assertFalse(styleTypedAt(mathPara("x^2"), 0).math)
    }

    @Test
    fun aTapAnywhereInsideADrawnFormulaOpensIt() {
        val p = mathPara("x^2")
        flowWith(p).let { flow ->
            flow.margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
            val line = layout().layout(flow, listOf(PageBox(400.0, 200.0)), 150).pages[0].lines[0]
            // The box runs 0..43.2 with every interior boundary at its right edge,
            // so without the nudge the left half would snap to 0, in front of it.
            assertEquals(1, line.offsetAt(5.0))
            assertEquals(1, line.offsetAt(40.0))
            assertEquals(0, line.offsetAt(0.0))
        }
    }

    @Test
    fun revealingChangesTheWholeLaidOutFrameNotJustTheLineBreaks() {
        // The host re-lays-out when the caret moves into a formula, so the frame
        // that comes back has to reflect the reveal, not only breakLines.
        val p = mathPara("a + b")
        val l = layout()
        val flow = flowWith(p)
        flow.margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        val boxes = listOf(PageBox(400.0, 200.0))
        var revealed = false
        l.revealedMath = { if (revealed) 0 else -1 }

        val drawn = l.layout(flow, boxes, 150).pages[0].lines[0]
        assertEquals(1, drawn.segs.count { it.math })
        assertTrue(drawn.decos.isEmpty())

        revealed = true
        val opened = l.layout(flow, boxes, 150).pages[0].lines[0]
        assertTrue(opened.segs.none { it.math })
        assertEquals(MathShow.SOURCE, opened.decos.single().math)
        // The caret geometry moves with it, which is the visible half of the change.
        assertTrue(opened.xs.last() < drawn.xs.last())
    }

}
