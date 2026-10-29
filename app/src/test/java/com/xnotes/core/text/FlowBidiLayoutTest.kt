package com.xnotes.core.text

import com.xnotes.core.FakeTextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mixed-direction lines in a right-to-left paragraph. The direction runs tile the line
 * right to left, and the logical slot where two runs of opposite direction meet stands
 * for two geometric edges — the tests pin the left-to-right run's own edges there, since
 * a run's anchor is what its words draw from and what the caret reads.
 */
class FlowBidiLayoutTest {

    private fun lineOf(text: String, dir: ParaDir): PlacedLine {
        val layout = FlowLayout(FakeTextMeasurer(), com.xnotes.platform.MathRendering.Blocking)
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run(text)), dir = dir)) }
        return layout.layout(flow, listOf(PageBox(800.0, 400.0)), 150).pages[0].lines.single()
    }

    @Test
    fun latinWordsInsideAnRtlLineDoNotLandOnThePersianOnes() {
        val line = lineOf("سلام hello دنیا", ParaDir.RTL)
        val a = line.xs[0] - line.xs[1] // one character's advance
        val segs = line.segs.associateBy { it.start }
        val salam = segs.getValue(0)
        val hello = segs.getValue(5)
        val donia = segs.getValue(11)
        // The visual order runs right to left: سلام rightmost, then hello, then دنیا, and
        // every word keeps to the box its run placed — no word inside another's ink.
        assertEquals(6 * a, salam.x - hello.x, 1e-9)
        assertEquals(5 * a, hello.x - donia.x, 1e-9)
        // The Latin run's far end is its own: the slot where it meets the Persian run
        // holds hello's right edge, not the Persian run's left edge.
        assertEquals(hello.x + 5 * a, line.xs[10], 1e-9)
        assertEquals(hello.x, line.xs[5], 1e-9)
    }

    @Test
    fun aPureRtlLineStillRunsRightToLeft() {
        val line = lineOf("سلام دنیا", ParaDir.RTL)
        val a = line.xs[0] - line.xs[1]
        val segs = line.segs.associateBy { it.start }
        assertTrue(segs.getValue(0).x > segs.getValue(5).x)
        assertEquals(line.xs[9], segs.getValue(5).x, 1e-9)
        assertEquals(4 * a, line.xs[0] - segs.getValue(0).x, 1e-9)
    }

    @Test
    fun aTrailingSpaceHangsPastTheLinesLeftEnd() {
        val line = lineOf("سلام hello ", ParaDir.RTL)
        assertTrue("trailing space boundary left of the words", line.xs.last() < line.segs.minOf { it.x })
    }
}
