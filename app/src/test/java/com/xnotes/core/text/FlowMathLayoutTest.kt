package com.xnotes.core.text

import com.xnotes.core.FakeMathTypesetter
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
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
}
