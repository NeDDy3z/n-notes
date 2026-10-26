package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowEditorTest {

    private fun para(text: String, style: CharStyle = CharStyle.DEFAULT) =
        Paragraph(if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text, style)))

    @Test
    fun insertIntoEmptyFlowCreatesParagraphs() {
        val flow = TextFlow()
        val (cmd, caret) = FlowEditor(flow).insertText(FlowPos.START, "a\nb")
        assertNotNull(cmd)
        assertEquals("a\nb", flow.plainText())
        assertEquals(FlowPos(1, 1), caret)
        cmd!!.undo()
        assertTrue(flow.paragraphs.isEmpty())
    }

    @Test
    fun aSplitParagraphKeepsItsDirection() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("سلام")), dir = ParaDir.RTL))
        }
        val (cmd, _) = FlowEditor(flow).insertText(FlowPos(0, 4), "\n")
        assertEquals(2, flow.paragraphs.size)
        assertEquals(ParaDir.RTL, flow.paragraphs[1].dir)
        cmd!!.undo()
        assertEquals(1, flow.paragraphs.size)
        assertEquals(ParaDir.RTL, flow.paragraphs[0].dir)
    }

    @Test
    fun typingInheritsThePrecedingCharacterStyle() {
        val flow = TextFlow().apply { paragraphs.add(para("ab", CharStyle(bold = true))) }
        val (cmd, caret) = FlowEditor(flow).insertText(FlowPos(0, 1), "X")
        assertNotNull(cmd)
        assertEquals(FlowPos(0, 2), caret)
        assertEquals(1, flow.paragraphs[0].runs.size)
        assertEquals("aXb", flow.paragraphs[0].runs[0].text)
        assertTrue(flow.paragraphs[0].runs[0].style.bold)
    }

    @Test
    fun enterSplitsAndContinuesTheListUnchecked() {
        val flow = TextFlow().apply {
            paragraphs.add(para("item").apply { list = ListKind.CHECK; checked = true; indent = 2 })
        }
        val (cmd, caret) = FlowEditor(flow).insertText(FlowPos(0, 4), "\n")
        assertNotNull(cmd)
        assertEquals(2, flow.paragraphs.size)
        assertEquals(FlowPos(1, 0), caret)
        assertEquals("item", flow.paragraphs[0].plainText())
        assertTrue(flow.paragraphs[0].checked)
        val cont = flow.paragraphs[1]
        assertEquals(ListKind.CHECK, cont.list)
        assertEquals(2, cont.indent)
        assertFalse(cont.checked)
        assertEquals(0, cont.length)
    }

    @Test
    fun deleteAcrossParagraphsMergesIntoTheFirst() {
        val flow = TextFlow().apply {
            paragraphs.add(para("abc").apply { align = ParaAlign.CENTER })
            paragraphs.add(para("def").apply { list = ListKind.BULLET })
            paragraphs.add(para("keep"))
        }
        val untouched = flow.paragraphs[2]
        val (cmd, caret) = FlowEditor(flow).deleteRange(FlowRange(FlowPos(0, 2), FlowPos(1, 1)))
        assertNotNull(cmd)
        assertEquals(2, flow.paragraphs.size)
        assertEquals("abef", flow.paragraphs[0].plainText())
        assertEquals(ParaAlign.CENTER, flow.paragraphs[0].align)
        assertEquals(ListKind.NONE, flow.paragraphs[0].list)
        assertSame(untouched, flow.paragraphs[1])
        assertEquals(FlowPos(0, 2), caret)
    }

    @Test
    fun forwardDeleteMergeCanAdoptTheBlockLineProperties() {
        val flow = TextFlow().apply {
            paragraphs.add(para("plain"))
            paragraphs.add(para("todo").apply { list = ListKind.CHECK; checked = true; indent = 1 })
        }
        val range = FlowRange(FlowPos(0, 5), FlowPos(1, 0))
        val (cmd, caret) = FlowEditor(flow).replaceRange(range, "", adoptEndProps = true)
        assertNotNull(cmd)
        assertEquals(1, flow.paragraphs.size)
        val merged = flow.paragraphs[0]
        assertEquals("plaintodo", merged.plainText())
        assertEquals(ListKind.CHECK, merged.list)
        assertTrue(merged.checked)
        assertEquals(1, merged.indent)
        assertEquals(FlowPos(0, 5), caret)
        cmd!!.undo()
        assertEquals("plain\ntodo", flow.plainText())
        assertEquals(ListKind.NONE, flow.paragraphs[0].list)
        assertEquals(ListKind.CHECK, flow.paragraphs[1].list)
    }

    @Test
    fun styleRangeSplitsRunsAndUnstyleMergesThemBack() {
        val flow = TextFlow().apply { paragraphs.add(para("hello")) }
        val editor = FlowEditor(flow)
        val range = FlowRange(FlowPos(0, 1), FlowPos(0, 3))
        assertNotNull(editor.setCharStyle(range) { it.copy(bold = true) })
        val runs = flow.paragraphs[0].runs
        assertEquals(listOf("h", "el", "lo"), runs.map { it.text })
        assertTrue(runs[1].style.bold)
        assertNotNull(editor.setCharStyle(range) { it.copy(bold = false) })
        assertEquals(1, flow.paragraphs[0].runs.size)
        assertNull(editor.setCharStyle(range) { it.copy(bold = false) })
    }

    @Test
    fun replaceRangeSpansLinesAndPlacesTheCaret() {
        val flow = TextFlow().apply { paragraphs.add(para("abcd")) }
        val (cmd, caret) = FlowEditor(flow)
            .replaceRange(FlowRange(FlowPos(0, 1), FlowPos(0, 3)), "X\nY")
        assertNotNull(cmd)
        assertEquals("aX\nYd", flow.plainText())
        assertEquals(FlowPos(1, 1), caret)
    }

    @Test
    fun charStyleAtFollowsTheCharacterBeforeTheCaret() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("ab", CharStyle(bold = true)), Run("cd"))))
        }
        val editor = FlowEditor(flow)
        assertTrue(editor.charStyleAt(FlowPos(0, 0)).bold)
        assertTrue(editor.charStyleAt(FlowPos(0, 2)).bold)
        assertFalse(editor.charStyleAt(FlowPos(0, 3)).bold)
        assertEquals(CharStyle.DEFAULT, FlowEditor(TextFlow()).charStyleAt(FlowPos.START))
    }

    @Test
    fun appendEmptyLinesGrowsTheFlowAsOneUndoStep() {
        val flow = TextFlow().apply { paragraphs.add(para("x")) }
        val (cmd, caret) = FlowEditor(flow).appendEmptyLines(3)
        assertEquals(4, flow.paragraphs.size)
        assertEquals(FlowPos(3, 0), caret)
        cmd.undo()
        assertEquals(1, flow.paragraphs.size)
    }

    @Test
    fun toggleCheckedOnlyActsOnCheckItems()  {
        val flow = TextFlow().apply {
            paragraphs.add(para("todo").apply { list = ListKind.CHECK })
            paragraphs.add(para("plain"))
        }
        val editor = FlowEditor(flow)
        assertNotNull(editor.toggleChecked(0))
        assertTrue(flow.paragraphs[0].checked)
        assertNull(editor.toggleChecked(1))
        assertNull(editor.toggleChecked(9))
    }

    @Test
    fun rangeStyleReadsTheFirstSelectedCharacter() {
        val flow = TextFlow().apply {
            paragraphs.add(
                Paragraph(mutableListOf(Run("ab", CharStyle(sizePt = 10.0)), Run("cd", CharStyle(sizePt = 20.0)))),
            )
        }
        val editor = FlowEditor(flow)
        // A selection starting at 'c' reports the 20pt run, not the 10pt char before it.
        assertEquals(20.0, editor.styleAtRangeStart(FlowRange(FlowPos(0, 2), FlowPos(0, 4))).sizePt)
        assertEquals(10.0, editor.charStyleAt(FlowPos(0, 2)).sizePt)
    }

    @Test
    fun anEmptyHeadingTypesAsAHeadingWithNothingArmed() {
        // A caret placed back on an empty heading has no pending style left to type with.
        val flow = TextFlow().apply { paragraphs.add(para("").apply { headingLevel = 2 }) }
        val editor = FlowEditor(flow)
        assertEquals(Paragraph.HEADING_STYLE, editor.charStyleAt(FlowPos.START))
        editor.insertText(FlowPos.START, "Title")
        assertEquals(2, flow.paragraphs[0].headingLevel)
        assertEquals(Paragraph.HEADING_STYLE, flow.paragraphs[0].runs.single().style)
    }

    // --- a heading holds no text sized by hand ---

    /** An h1 over a 12pt base, so its text measures 24pt. */
    private fun headingFlow(vararg after: Paragraph) = TextFlow().apply {
        defaultSizePt = 12.0
        paragraphs.add(para("Title", Paragraph.HEADING_STYLE).apply { headingLevel = 1 })
        paragraphs.addAll(after)
    }

    @Test
    fun sizingPartOfAHeadingByHandMakesItBodyTextThatLooksTheSame() {
        val flow = headingFlow()
        val p = flow.paragraphs[0]
        val cmd = FlowEditor(flow).setCharStyle(FlowRange(FlowPos(0, 0), FlowPos(0, 2))) { it.copy(sizePt = 30.0) }
        assertEquals(0, p.headingLevel)
        assertEquals(listOf("Ti", "tle"), p.runs.map { it.text })
        assertEquals(listOf(30.0, 24.0), p.runs.map { it.style.sizePt })
        assertTrue(p.runs.all { it.style.bold })
        cmd!!.undo()
        assertEquals(1, p.headingLevel)
        assertEquals(Paragraph.HEADING_STYLE, p.runs.single().style)
    }

    @Test
    fun textTypedAtASizeOfItsOwnMakesAHeadingBodyText() {
        val flow = headingFlow()
        FlowEditor(flow).insertText(FlowPos(0, 5), "!", CharStyle(bold = true, sizePt = 30.0))
        val p = flow.paragraphs[0]
        assertEquals(0, p.headingLevel)
        assertEquals(listOf(24.0, 30.0), p.runs.map { it.style.sizePt })
    }

    @Test
    fun aHeadingKeepsItsLevelThroughEditsThatSizeNothing() {
        val flow = headingFlow()
        val editor = FlowEditor(flow)
        editor.insertText(FlowPos(0, 5), "s")
        editor.setCharStyle(FlowRange(FlowPos(0, 0), FlowPos(0, 2))) { it.copy(italic = true) }
        editor.deleteRange(FlowRange(FlowPos(0, 0), FlowPos(0, 1)))
        assertEquals(1, flow.paragraphs[0].headingLevel)
        assertTrue(flow.paragraphs[0].runs.all { it.style.sizePt == null })
    }

    @Test
    fun bodyTextJoinedOntoAHeadingTakesItsSizeUnlessSizedByHand() {
        val plain = headingFlow(para("more"))
        FlowEditor(plain).deleteRange(FlowRange(FlowPos(0, 5), FlowPos(1, 0)))
        val joined = plain.paragraphs.single()
        assertEquals(1, joined.headingLevel)
        assertEquals(24.0, resolveFont(plain, joined, joined.runs.last().style).pointSize, 1e-9)

        val sized = headingFlow(para("more", CharStyle(sizePt = 20.0)))
        FlowEditor(sized).deleteRange(FlowRange(FlowPos(0, 5), FlowPos(1, 0)))
        val kept = sized.paragraphs.single()
        assertEquals(0, kept.headingLevel)
        assertEquals(listOf(24.0, 20.0), kept.runs.map { it.style.sizePt })
    }

    @Test
    fun typingWithExplicitStyleStartsAStyledRun() {
        val flow = TextFlow().apply { paragraphs.add(para("ab")) }
        FlowEditor(flow).insertText(FlowPos(0, 1), "X", CharStyle(italic = true))
        assertEquals(listOf("a", "X", "b"), flow.paragraphs[0].runs.map { it.text })
        assertTrue(flow.paragraphs[0].runs[1].style.italic)
    }
}
