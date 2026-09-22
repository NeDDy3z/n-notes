package com.xnotes.core.text

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.pal.FontFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Line breaking against the fake measurer: every char advances 0.6pt, so at the
 * 12pt default each char is 7.2 wide; ascent is 12.0 and descent 3.6.
 */
class FlowLayoutTest {

    private val layout = FlowLayout(FakeTextMeasurer())

    private fun flowWith(para: Paragraph): TextFlow = TextFlow().apply { paragraphs.add(para) }

    private fun para(text: String, style: CharStyle = CharStyle.DEFAULT) =
        Paragraph(if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text, style)))

    @Test
    fun wrapsAtWordBoundariesAndHangsTrailingSpaces() {
        val p = para("aaa bbb ccc")
        val lines = layout.breakLines(flowWith(p), p, 60.0)
        assertEquals(2, lines.size)
        assertEquals(0, lines[0].startChar)
        assertEquals(8, lines[0].endChar)
        assertEquals(50.4, lines[0].width, 1e-9)
        assertEquals(1, lines[0].spaceCount)
        assertFalse(lines[0].hardBroken)
        assertEquals(8, lines[1].startChar)
        assertEquals(11, lines[1].endChar)
        assertEquals(21.6, lines[1].width, 1e-9)
        assertEquals(0, lines[1].spaceCount)
    }

    @Test
    fun hardBreaksWordsWiderThanTheLine() {
        val p = para("aaaaaaaaaa")
        val lines = layout.breakLines(flowWith(p), p, 30.0)
        assertEquals(listOf(0, 4, 8), lines.map { it.startChar })
        assertEquals(listOf(4, 8, 10), lines.map { it.endChar })
        assertEquals(listOf(true, true, false), lines.map { it.hardBroken })
    }

    @Test
    fun oversizeCharactersStillMakeProgress() {
        val p = para("aa", CharStyle(sizePt = 50.0))
        val lines = layout.breakLines(flowWith(p), p, 5.0)
        assertEquals(2, lines.size)
        assertEquals(1, lines[0].endChar)
        assertTrue(lines[0].hardBroken)
    }

    @Test
    fun emptyParagraphIsOneDefaultTallLine() {
        val p = para("")
        val lines = layout.breakLines(flowWith(p), p, 100.0)
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].endChar)
        assertEquals(12.0, lines[0].ascent, 1e-9)
        assertEquals(3.6, lines[0].descent, 1e-9)
    }

    @Test
    fun trailingSpacesStayInTheSpanButNotTheWidth() {
        val p = para("ab   ")
        val lines = layout.breakLines(flowWith(p), p, 100.0)
        assertEquals(1, lines.size)
        assertEquals(5, lines[0].endChar)
        assertEquals(14.4, lines[0].width, 1e-9)
        assertEquals(0, lines[0].spaceCount)
    }

    @Test
    fun lineMetricsFollowTheTallestRun() {
        val p = Paragraph(mutableListOf(Run("aa"), Run("BB", CharStyle(sizePt = 24.0))))
        val lines = layout.breakLines(flowWith(p), p, 100.0)
        assertEquals(1, lines.size)
        assertEquals(43.2, lines[0].width, 1e-9)
        assertEquals(24.0, lines[0].ascent, 1e-9)
        assertEquals(7.2, lines[0].descent, 1e-9)
    }

    @Test
    fun continuationBreaksMatchTheFullBreak() {
        val p = para("aaa bbb ccc ddd eee")
        val flow = flowWith(p)
        val full = layout.breakLines(flow, p, 60.0)
        assertTrue(full.size >= 2)
        val cont = layout.breakLines(flow, p, 60.0, fromChar = full[1].startChar)
        assertEquals(full.drop(1).map { it.startChar }, cont.map { it.startChar })
        assertEquals(full.drop(1).map { it.endChar }, cont.map { it.endChar })
        assertEquals(full.drop(1).map { it.width }, cont.map { it.width })
    }

    @Test
    fun codeAndInlineCodeResolveToMono() {
        val flow = TextFlow().apply { defaultFace = FontFace.SERIF }
        val codePara = Paragraph(codeLang = "c")
        assertEquals(FontFace.MONO, resolveFont(flow, codePara, CharStyle.DEFAULT).face)
        val plain = Paragraph()
        assertEquals(FontFace.MONO, resolveFont(flow, plain, CharStyle(code = true)).face)
        assertEquals(FontFace.SERIF, resolveFont(flow, plain, CharStyle.DEFAULT).face)
        assertTrue(resolveFont(flow, plain, CharStyle(bold = true, italic = true)).bold)
    }

    @Test
    fun runFaceOverridesTheDefaultAndCodeUsesTheMonoFace() {
        val flow = TextFlow().apply {
            defaultFace = FontFace.SERIF
            monoFace = FontFace("JetBrains Mono")
        }
        val plain = Paragraph()
        assertEquals(FontFace("Inter"), resolveFont(flow, plain, CharStyle(face = FontFace("Inter"))).face)
        assertEquals(FontFace("JetBrains Mono"), resolveFont(flow, plain, CharStyle(code = true)).face)
        val codePara = Paragraph(codeLang = "c")
        assertEquals(
            FontFace("JetBrains Mono"),
            resolveFont(flow, codePara, CharStyle(face = FontFace("Inter"))).face,
        )
    }

    @Test
    fun editedParagraphsRemeasure() {
        val p = para("short")
        val flow = flowWith(p)
        assertEquals(1, layout.breakLines(flow, p, 60.0).size)
        FlowEditor(flow).insertText(FlowPos(0, 5), " plus much more text here")
        assertTrue(layout.breakLines(flow, p, 60.0).size > 1)
    }

    @Test
    fun anEmptyHeadingLineStandsAsTallAsItsText() {
        val p = para("").apply { headingLevel = 1 }
        val line = layout.breakLines(flowWith(p), p, 200.0).single()
        assertEquals(24.0, line.ascent, 1e-9)
        assertEquals(31.2, line.height, 1e-9)
    }

    @Test
    fun anEmptyBodyLineKeepsTheDefaultHeight() {
        val p = para("")
        assertEquals(15.6, layout.breakLines(flowWith(p), p, 200.0).single().height, 1e-9)
    }

    @Test
    fun aHeadingDoesNotChangeHeightWhenItsFirstCharacterLands() {
        // The caret previews at the heading size, so the empty line has to be that
        // tall already or the caret sits high until something is typed.
        val empty = para("").apply { headingLevel = 2 }
        val typed = para("T", Paragraph.headingStyle(2, TextFlow.DEFAULT_SIZE_PT)).apply { headingLevel = 2 }
        val before = layout.breakLines(flowWith(empty), empty, 200.0).single()
        val after = layout.breakLines(flowWith(typed), typed, 200.0).single()
        assertEquals(after.height, before.height, 1e-9)
        assertEquals(after.ascent, before.ascent, 1e-9)
    }

}
