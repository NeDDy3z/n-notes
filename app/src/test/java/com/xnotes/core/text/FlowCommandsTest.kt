package com.xnotes.core.text

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.AddPageAuto
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowCommandsTest {

    private fun flowOf(vararg lines: String): TextFlow = TextFlow().apply {
        lines.forEach { paragraphs.add(Paragraph(mutableListOf(Run(it)))) }
    }

    @Test
    fun paragraphEditRoundTripsExactly() {
        val flow = flowOf("hello")
        val para = flow.paragraphs[0]
        val cmd = FlowEditor(flow).setCharStyle(FlowRange(FlowPos(0, 0), FlowPos(0, 5))) {
            it.copy(underline = true)
        }!!
        cmd.undo()
        assertSame(para, flow.paragraphs[0])
        assertEquals(1, para.runs.size)
        assertEquals(CharStyle.DEFAULT, para.runs[0].style)
        cmd.redo()
        assertTrue(para.runs[0].style.underline)
        cmd.undo()
        assertEquals("hello", para.plainText())
        assertEquals(CharStyle.DEFAULT, para.runs[0].style)
    }

    @Test
    fun togglingDirectionCyclesThroughAutoAndBothPins() {
        val flow = flowOf("dir")
        val para = flow.paragraphs[0]
        val cmd = FlowEditor(flow).setParaStyle(FlowRange.caret(FlowPos(0, 0))) {
            it.dir = ParaDir.RTL
        }!!
        assertEquals(ParaDir.RTL, para.dir)
        cmd.undo()
        assertEquals(ParaDir.AUTO, para.dir)
        cmd.redo()
        assertEquals(ParaDir.RTL, para.dir)
    }

    @Test
    fun spliceUndoRestoresTheOriginalParagraphObjects() {
        val flow = flowOf("split me")
        val original = flow.paragraphs[0]
        val (cmd, _) = FlowEditor(flow).insertText(FlowPos(0, 5), "\n")
        assertEquals(2, flow.paragraphs.size)
        cmd!!.undo()
        assertEquals(1, flow.paragraphs.size)
        assertSame(original, flow.paragraphs[0])
        assertEquals("split me", original.plainText())
        cmd.redo()
        cmd.redo()
        assertEquals(2, flow.paragraphs.size)
        assertEquals("split\n me", flow.plainText())
    }

    @Test
    fun autoPageUndoRemovesOnlyEmptyPages() {
        val doc = Document(pages = mutableListOf(Page(100.0, 100.0)))
        val auto = Page(100.0, 100.0)
        val cmd = AddPageAuto(doc, auto, 1)
        cmd.redo()
        cmd.redo()
        assertEquals(2, doc.pages.size)

        auto.items.add(TextItem(Pt(0.0, 0.0), measurer = FakeTextMeasurer()))
        cmd.undo()
        assertEquals(2, doc.pages.size)

        auto.items.clear()
        cmd.undo()
        assertEquals(1, doc.pages.size)
        cmd.undo()
        assertEquals(1, doc.pages.size)
    }

    @Test
    fun compositeUndoesTextAfterRemovingTheAutoPage() {
        val doc = Document(pages = mutableListOf(Page(100.0, 100.0)))
        val flow = doc.flow
        val (edit, _) = FlowEditor(flow).insertText(FlowPos.START, "overflowing text")
        val auto = Page(100.0, 100.0)
        val addPage = AddPageAuto(doc, auto, 1).also { it.redo() }
        val composite = CompositeCommand(listOf(edit!!, addPage))
        composite.undo()
        assertTrue(flow.paragraphs.isEmpty())
        assertEquals(1, doc.pages.size)
        composite.redo()
        assertEquals("overflowing text", flow.plainText())
        assertEquals(2, doc.pages.size)
    }
}
