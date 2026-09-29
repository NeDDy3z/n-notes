package com.xnotes.core.text

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Renderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPainterTest {

    private val layout = FlowLayout(FakeTextMeasurer())

    private fun frameOf(flow: TextFlow): FlowFrame {
        flow.margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        return layout.layout(flow, listOf(PageBox(200.0, 100.0)), 150)
    }

    private fun paint(frame: FlowFrame, region: Rect = Rect(0.0, 0.0, 200.0, 100.0)): FakeRenderer {
        val r = FakeRenderer()
        FlowPainter.paintPage(r, frame, 0, region)
        return r
    }

    /** The recorded (x, baseline) of the drawTextRun op for [text], parsed tolerance-friendly. */
    private fun runAt(ops: List<String>, text: String): Pair<Double, Double> {
        val op = ops.first { it.startsWith("drawTextRun:$text@") }
        val (x, y) = op.substringAfter('@').split(',')
        return x.toDouble() to y.toDouble()
    }

    @Test
    fun drawsWordsAtTheirAdvancePositions() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("ab cd")))) }
        val ops = paint(frameOf(flow)).ops
        val (abX, abY) = runAt(ops, "ab")
        assertEquals(0.0, abX, 1e-9)
        assertEquals(12.0, abY, 1e-9)
        val (cdX, cdY) = runAt(ops, "cd")
        assertEquals(21.6, cdX, 1e-9)
        assertEquals(12.0, cdY, 1e-9)
    }

    @Test
    fun highlightPaintsUnderTextAndUnderlineOverIt() {
        val flow = TextFlow().apply {
            paragraphs.add(
                Paragraph(
                    mutableListOf(Run("hi", CharStyle(underline = true, highlight = Rgba(255, 255, 0, 255)))),
                ),
            )
        }
        val ops = paint(frameOf(flow)).ops
        assertEquals(listOf("fillRect", "drawTextRun:hi@0.0,12.0", "fillRect"), ops)
    }

    @Test
    fun codeLinesGetABackgroundChipFirst() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("x = 1")), codeLang = "python"))
        }
        val ops = paint(frameOf(flow)).ops
        assertEquals("fillRect", ops.first())
        assertTrue(ops.any { it.startsWith("drawTextRun:x@") })
    }

    @Test
    fun aCodeBlockPaintsOneSeamlessChip() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("a")), codeLang = "python"))
            paragraphs.add(Paragraph(mutableListOf(Run("b")), codeLang = "python"))
            paragraphs.add(Paragraph(mutableListOf(Run("plain"))))
            paragraphs.add(Paragraph(mutableListOf(Run("c")), codeLang = "python"))
        }
        val ops = paint(frameOf(flow)).ops
        // One rect for the two-line block, one for the separate single-line block.
        assertEquals(2, ops.count { it == "fillRect" })
    }

    @Test
    fun orderedMarkersDrawTheirMeasuredLabel() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("x")), list = ListKind.ORDERED))
        }
        val ops = paint(frameOf(flow)).ops
        assertEquals(15.6, runAt(ops, "1.").first, 1e-9)
        assertEquals(40.0, runAt(ops, "x").first, 1e-9)
    }

    @Test
    fun checkboxesStrokeTheBoxAndFillWhenChecked() {
        val unchecked = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("todo")), list = ListKind.CHECK))
        }
        val ops1 = paint(frameOf(unchecked)).ops
        assertTrue(ops1.contains("strokeRect"))
        assertFalse(ops1.contains("fillRect"))

        val checked = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("done")), list = ListKind.CHECK, checked = true))
        }
        val ops2 = paint(frameOf(checked)).ops
        assertTrue(ops2.contains("strokeRect"))
        assertTrue(ops2.contains("fillRect"))
    }

    @Test
    fun regionCullingSkipsLinesOutsideIt() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("one"))))
            paragraphs.add(Paragraph(mutableListOf(Run("two"))))
        }
        val ops = paint(frameOf(flow), Rect(0.0, 0.0, 200.0, 10.0)).ops
        assertTrue(ops.any { it.startsWith("drawTextRun:one@") })
        assertFalse(ops.any { it.startsWith("drawTextRun:two@") })
    }

    /** A backend that writes real text, recording like [FakeRenderer]. */
    private class TextWriter(val inner: FakeRenderer = FakeRenderer()) : Renderer by inner {
        override val writesText: Boolean get() = true
    }

    private fun write(frame: FlowFrame): List<String> {
        val r = TextWriter()
        FlowPainter.paintPage(r, frame, 0, Rect(0.0, 0.0, 200.0, 100.0))
        return r.inner.ops.filter { it.startsWith("drawTextRun:") }
    }

    @Test
    fun aTextWriterGetsEachSpaceInReadingOrder() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("ab  cd")))) }
        val runs = write(frameOf(flow))
        assertEquals(listOf("ab", " ", " ", "cd"), runs.map { it.substringAfter(':').substringBefore('@') })
        val xs = runs.map { it.substringAfter('@').substringBefore(',').toDouble() }
        listOf(0.0, 14.4, 21.6, 28.8).forEachIndexed { i, x -> assertEquals(x, xs[i], 1e-9) }
    }

    @Test
    fun theScreenNeverSeesSpaces() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("ab  cd")))) }
        assertFalse(paint(frameOf(flow)).ops.any { it.startsWith("drawTextRun: @") })
    }

    @Test
    fun codeIndentationAndBlankLinesReachATextWriter() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("  x")), codeLang = ""))
            paragraphs.add(Paragraph(mutableListOf(Run("")), codeLang = ""))
            paragraphs.add(Paragraph(mutableListOf(Run("y")), codeLang = ""))
        }
        val runs = write(frameOf(flow)).map { it.substringBefore('@') }
        assertEquals(listOf("drawTextRun: ", "drawTextRun: ", "drawTextRun:x", "drawTextRun: ", "drawTextRun:y"), runs)
    }
}
