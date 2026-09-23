package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRulesTest {

    /** Type [text] a character at a time, firing rules exactly as a caret session does. */
    private fun type(flow: TextFlow, text: String, from: FlowPos = FlowPos.START): FlowPos {
        var pos = from
        var pending: CharStyle? = null
        for (ch in text) {
            val s = ch.toString()
            val enter = if (s == "\n") InputRules.forEnter(flow, pos) else null
            if (enter != null) {
                val r = InputRules.apply(flow, pos, enter)
                pos = r.caret
                pending = r.pending
                continue
            }
            val (_, caret) = FlowEditor(flow).replaceRange(FlowRange.caret(pos), s, pending)
            pending = null
            pos = caret
            InputRules.forTyped(flow, pos, s)?.let { rule ->
                val r = InputRules.apply(flow, pos, rule)
                pos = r.caret
                pending = r.pending
            }
        }
        return pos
    }

    private fun typed(text: String): Paragraph {
        val flow = TextFlow()
        type(flow, text)
        return flow.paragraphs[0]
    }

    private fun para(text: String, style: CharStyle = CharStyle.DEFAULT) =
        Paragraph(if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text, style)))

    // --- block markers ---

    @Test
    fun hashAndSpaceMakesAHeadingAndTakesTheMarkerAway() {
        val p = typed("# Title")
        assertEquals("Title", p.plainText())
        assertEquals(1, p.headingLevel)
        assertEquals(1, p.runs.size)
        assertTrue(p.runs[0].style.bold)
        assertEquals(TextFlow.DEFAULT_SIZE_PT * 2.0, p.runs[0].style.sizePt!!, 1e-9)
    }

    @Test
    fun eachHeadingLevelTakesItsOwnSize() {
        assertEquals(2, typed("## Sub").headingLevel)
        assertEquals(3, typed("### Sub").headingLevel)
        assertEquals(4, typed("#### Sub").headingLevel)
        // Levels 5 and 6 scale to 1.0, so they would be invisible: left as literal text.
        assertEquals(0, typed("##### Sub").headingLevel)
        assertEquals("##### Sub", typed("##### Sub").plainText())
    }

    @Test
    fun theListMarkersConvertTheParagraph() {
        assertEquals(ListKind.BULLET, typed("- a").list)
        assertEquals(ListKind.BULLET, typed("* a").list)
        assertEquals(ListKind.BULLET, typed("+ a").list)
        assertEquals(ListKind.ORDERED, typed("1. a").list)
        assertEquals(ListKind.ORDERED, typed("1) a").list)
        assertEquals("a", typed("- a").plainText())
    }

    @Test
    fun bracketsMakeACheckItemAndXChecksIt() {
        assertEquals(ListKind.CHECK, typed("[] a").list)
        assertFalse(typed("[] a").checked)
        assertTrue(typed("[x] a").checked)
        assertTrue(typed("[X] a").checked)
        assertFalse(typed("[ ] a").checked)
        assertEquals("a", typed("[x] a").plainText())
    }

    @Test
    fun aBulletThenBracketsBecomesACheckItem() {
        val p = typed("- [] a")
        assertEquals(ListKind.CHECK, p.list)
        assertEquals("a", p.plainText())
    }

    @Test
    fun angleBracketsIndentAndStack() {
        assertEquals(1, typed("> a").indent)
        assertEquals(2, typed(">> a").indent)
    }

    @Test
    fun leadingSpacesNestAListButAnUnindentedMarkerKeepsTheLevel() {
        assertEquals(1, typed("  - a").indent)
        assertEquals(2, typed("    - a").indent)
        val flow = TextFlow().apply { paragraphs.add(para("").apply { indent = 3 }) }
        type(flow, "- a")
        assertEquals(3, flow.paragraphs[0].indent)
    }

    @Test
    fun aBlockMarkerOnlyFiresAtTheStartOfTheLine() {
        assertEquals("a # ", typed("a # ").plainText())
        assertEquals(0, typed("a # ").headingLevel)
        assertEquals("a - ", typed("a - ").plainText())
    }

    @Test
    fun noRuleFiresOnACodeLine() {
        val flow = TextFlow().apply { paragraphs.add(para("").apply { codeLang = "kotlin" }) }
        type(flow, "# not a heading")
        assertEquals("# not a heading", flow.paragraphs[0].plainText())
        assertEquals(0, flow.paragraphs[0].headingLevel)
    }

    @Test
    fun noBlockRuleFiresInsideATableCell() {
        val table = FlowTable(listOf(1.0), listOf(0.0))
        val flow = TextFlow().apply {
            paragraphs.add(para("").apply { this.table = table; cellStart = true })
        }
        type(flow, "- a")
        assertEquals("- a", flow.paragraphs[0].plainText())
        assertEquals(ListKind.NONE, flow.paragraphs[0].list)
    }

    // --- inline emphasis ---

    @Test
    fun theEmphasisMarkersConvertTheirSpanAndVanish() {
        typed("**bold**").let {
            assertEquals("bold", it.plainText())
            assertTrue(it.runs[0].style.bold)
        }
        typed("__bold__").let {
            assertEquals("bold", it.plainText())
            assertTrue(it.runs[0].style.bold)
        }
        typed("*it*").let {
            assertEquals("it", it.plainText())
            assertTrue(it.runs[0].style.italic)
        }
        typed("_it_").let {
            assertEquals("it", it.plainText())
            assertTrue(it.runs[0].style.italic)
        }
        typed("~~no~~").let {
            assertEquals("no", it.plainText())
            assertTrue(it.runs[0].style.strike)
        }
        typed("`x()`").let {
            assertEquals("x()", it.plainText())
            assertTrue(it.runs[0].style.code)
        }
    }

    @Test
    fun aDoubleMarkerNeverFiresItalicOnItsWayThrough() {
        // "**bold*" is a bold run in progress, not an italic one that just closed.
        val flow = TextFlow()
        type(flow, "**bold*")
        assertEquals("**bold*", flow.paragraphs[0].plainText())
        assertFalse(flow.paragraphs[0].runs[0].style.italic)
    }

    @Test
    fun intrawordUnderscoresAreNotEmphasis() {
        assertEquals("snake_case_name", typed("snake_case_name").plainText())
        assertFalse(typed("snake_case_name").runs[0].style.italic)
    }

    @Test
    fun emphasisNeverOpensOrClosesOnWhitespace() {
        assertEquals("2 * 3 * 4", typed("2 * 3 * 4").plainText())
        assertEquals("a *b *", typed("a *b *").plainText())
    }

    @Test
    fun anEmptyMarkerPairIsLeftAlone() {
        assertEquals("****", typed("****").plainText())
        assertEquals("``", typed("``").plainText())
    }

    @Test
    fun typingOnPastAConvertedRunIsNotEmphasized() {
        val p = typed("**bold** and more")
        assertEquals("bold and more", p.plainText())
        assertEquals(2, p.runs.size)
        assertTrue(p.runs[0].style.bold)
        assertFalse(p.runs[1].style.bold)
        assertEquals(" and more", p.runs[1].text)
    }

    @Test
    fun emphasisNestsInsideAHeading() {
        val p = typed("# A **B**")
        assertEquals(1, p.headingLevel)
        assertEquals("A B", p.plainText())
        assertEquals(TextFlow.DEFAULT_SIZE_PT * 2.0, p.runs.last().style.sizePt!!, 1e-9)
        assertTrue(p.runs.last().style.bold)
    }

    // --- fences and Enter ---

    @Test
    fun aFenceAndEnterStartsACodeLineInItsLanguage() {
        val flow = TextFlow()
        type(flow, "```kotlin\n")
        assertEquals(1, flow.paragraphs.size)
        assertEquals("kotlin", flow.paragraphs[0].codeLang)
        assertEquals("", flow.paragraphs[0].plainText())
    }

    @Test
    fun aBareFenceStartsAnUnhighlightedCodeLine() {
        val flow = TextFlow()
        type(flow, "```\n")
        assertEquals("", flow.paragraphs[0].codeLang)
    }

    @Test
    fun aFenceAliasResolvesToTheBundledGrammar() {
        val flow = TextFlow()
        type(flow, "```py\n")
        assertEquals("python", flow.paragraphs[0].codeLang)
    }

    @Test
    fun enterOnAnEmptyListItemUnnestsThenLeavesTheList() {
        val flow = TextFlow().apply {
            paragraphs.add(para("").apply { list = ListKind.BULLET; indent = 2 })
        }
        type(flow, "\n")
        assertEquals(1, flow.paragraphs.size)
        assertEquals(1, flow.paragraphs[0].indent)
        type(flow, "\n")
        assertEquals(0, flow.paragraphs[0].indent)
        type(flow, "\n")
        assertEquals(ListKind.NONE, flow.paragraphs[0].list)
        assertEquals(1, flow.paragraphs.size)
    }

    @Test
    fun enterOnAListItemWithTextStillMakesAnotherItem() {
        val flow = TextFlow()
        type(flow, "- one\n")
        assertEquals(2, flow.paragraphs.size)
        assertEquals(ListKind.BULLET, flow.paragraphs[1].list)
    }

    // --- backspace ---

    @Test
    fun backspaceAtTheStartStripsOneBlockPropertyAtATime() {
        val flow = TextFlow().apply {
            paragraphs.add(para("a").apply { list = ListKind.CHECK; checked = true; indent = 1 })
        }
        val list = InputRules.forBackspace(flow, FlowPos(0, 0))
        assertNotNull(list)
        InputRules.apply(flow, FlowPos(0, 0), list!!)
        assertEquals(ListKind.NONE, flow.paragraphs[0].list)
        assertFalse(flow.paragraphs[0].checked)
        assertEquals(1, flow.paragraphs[0].indent)

        val indent = InputRules.forBackspace(flow, FlowPos(0, 0))
        assertNotNull(indent)
        InputRules.apply(flow, FlowPos(0, 0), indent!!)
        assertEquals(0, flow.paragraphs[0].indent)
        assertNull(InputRules.forBackspace(flow, FlowPos(0, 0)))
    }

    @Test
    fun backspaceOnAHeadingClearsTheLevelAndItsRunStyle() {
        val flow = TextFlow()
        type(flow, "# Title")
        val rule = InputRules.forBackspace(flow, FlowPos(0, 0))
        assertNotNull(rule)
        InputRules.apply(flow, FlowPos(0, 0), rule!!)
        val p = flow.paragraphs[0]
        assertEquals(0, p.headingLevel)
        assertEquals("Title", p.plainText())
        assertFalse(p.runs[0].style.bold)
        assertNull(p.runs[0].style.sizePt)
    }

    @Test
    fun backspaceOnlyStripsAnEmptyCodeLine() {
        val flow = TextFlow().apply { paragraphs.add(para("x").apply { codeLang = "" }) }
        assertNull(InputRules.forBackspace(flow, FlowPos(0, 0)))
        flow.paragraphs[0].runs.clear()
        val rule = InputRules.forBackspace(flow, FlowPos(0, 0))
        assertNotNull(rule)
        InputRules.apply(flow, FlowPos(0, 0), rule!!)
        assertNull(flow.paragraphs[0].codeLang)
    }

    // --- undo and parity ---

    @Test
    fun undoingAConversionLeavesTheMarkersAsTypedText() {
        val flow = TextFlow()
        val (_, caret) = FlowEditor(flow).insertText(FlowPos.START, "# ")
        val rule = InputRules.forTyped(flow, caret, " ")
        assertNotNull(rule)
        val result = InputRules.apply(flow, caret, rule!!)
        assertEquals(1, flow.paragraphs[0].headingLevel)
        result.command!!.undo()
        assertEquals("# ", flow.paragraphs[0].plainText())
        assertEquals(0, flow.paragraphs[0].headingLevel)
    }

    @Test
    fun typingAMarkerLandsTheSameParagraphPastingItWould() {
        for (md in listOf("# Title", "## Sub", "- item", "1. item", "> quote", "**b** x", "a `c` b")) {
            val flow = TextFlow()
            type(flow, md)
            val pasted = MarkdownParser.parse(md, TextFlow.DEFAULT_SIZE_PT).single()
            val mine = flow.paragraphs.single()
            assertEquals(md, pasted.plainText(), mine.plainText())
            assertEquals(md, pasted.list, mine.list)
            assertEquals(md, pasted.indent, mine.indent)
            assertEquals(md, pasted.checked, mine.checked)
            assertEquals(md, pasted.headingLevel, mine.headingLevel)
            assertEquals(md, pasted.runs.size, mine.runs.size)
            for (i in pasted.runs.indices) {
                assertEquals(md, pasted.runs[i].text, mine.runs[i].text)
                assertEquals(md, pasted.runs[i].style, mine.runs[i].style)
            }
        }
    }

    // --- heading continuation (the model side of leaving a heading) ---

    @Test
    fun enterAtTheEndOfAHeadingStartsABodyParagraph() {
        val flow = TextFlow()
        type(flow, "# Title")
        FlowEditor(flow).insertText(FlowPos(0, 5), "\n")
        assertEquals(1, flow.paragraphs[0].headingLevel)
        assertEquals(0, flow.paragraphs[1].headingLevel)
    }

    @Test
    fun enterInsideAHeadingKeepsBothHalvesOfIt() {
        val flow = TextFlow()
        type(flow, "# Title")
        FlowEditor(flow).insertText(FlowPos(0, 2), "\n")
        assertEquals(1, flow.paragraphs[0].headingLevel)
        assertEquals(1, flow.paragraphs[1].headingLevel)
    }

    // --- the Markdown shortcuts toggle ---

    @Test
    fun markdownOffLeavesAFenceAsPlainText() {
        val flow = TextFlow().apply { paragraphs.add(para("```kotlin")) }
        assertNull(InputRules.forEnter(flow, FlowPos(0, 9), markdown = false))
    }

    @Test
    fun markdownOffStillLetsEnterLeaveAList() {
        // Leaving a list is list editing, not syntax: a list made from the format
        // bar would be a trap without it.
        val flow = TextFlow().apply { paragraphs.add(para("").apply { list = ListKind.BULLET }) }
        val rule = InputRules.forEnter(flow, FlowPos(0, 0), markdown = false)
        assertNotNull(rule)
        InputRules.apply(flow, FlowPos(0, 0), rule!!)
        assertEquals(ListKind.NONE, flow.paragraphs[0].list)
    }

    @Test
    fun markdownOffStillStripsBlocksOnBackspace() {
        val flow = TextFlow().apply { paragraphs.add(para("a").apply { list = ListKind.BULLET }) }
        assertNotNull(InputRules.forBackspace(flow, FlowPos(0, 0)))
    }

    // --- inline math ---

    @Test
    fun dollarsSetTheirContentsAsAFormulaAndLeaveNoMarkers() {
        val p = typed("mass is \$E=mc^2\$")
        assertEquals("mass is E=mc^2", p.plainText())
        val run = p.runs.first { it.style.math }
        assertEquals("E=mc^2", run.text)
    }

    @Test
    fun aFormulaKeepsTheSpacesInsideIt() {
        val p = typed("\$a + b\$")
        assertEquals("a + b", p.plainText())
        assertTrue(p.runs.single().style.math)
    }

    @Test
    fun moneyIsNotAFormula() {
        // The closing marker would sit after a space, which is never a close.
        val p = typed("it cost \$5 and \$10")
        assertEquals("it cost \$5 and \$10", p.plainText())
        assertTrue(p.runs.none { it.style.math })
    }

    @Test
    fun anEmptyPairOfDollarsSetsNothing() {
        val p = typed("\$\$")
        assertEquals("\$\$", p.plainText())
        assertTrue(p.runs.none { it.style.math })
    }

    @Test
    fun typingOnPastAFormulaIsNotPartOfIt() {
        val flow = TextFlow()
        type(flow, "\$x^2\$ and on")
        val p = flow.paragraphs[0]
        assertEquals("x^2 and on", p.plainText())
        assertEquals("x^2", p.runs.first { it.style.math }.text)
        assertEquals(" and on", p.runs.last().text)
        assertFalse(p.runs.last().style.math)
    }

    @Test
    fun typingBackAtAFormulaJoinsIt() {
        val flow = TextFlow()
        val end = type(flow, "\$x\$")
        // Returning to the formula is typing into it: the same reach that shows
        // its source, and the only way a one-character formula can be edited.
        FlowEditor(flow).replaceRange(FlowRange.caret(end), "y")
        val p = flow.paragraphs[0]
        assertEquals("xy", p.runs.single().text)
        assertTrue(p.runs.single().style.math)
    }

    @Test
    fun theFirstKeyAfterAFormulaLandsOutsideItAnyway() {
        val flow = TextFlow()
        type(flow, "\$x^2\$ ok")
        val p = flow.paragraphs[0]
        assertEquals("x^2", p.runs.first { it.style.math }.text)
        assertEquals(" ok", p.runs.last().text)
        assertFalse(p.runs.last().style.math)
    }

    @Test
    fun aFormulaReportsItselfSoItDrawsRatherThanReopening() {
        val flow = TextFlow()
        type(flow, "a")
        val pos = FlowPos(0, 1)
        var last: InputRules.Result? = null
        for (ch in "\$x^2\$") {
            val (_, caret) = FlowEditor(flow).replaceRange(FlowRange.caret(last?.caret ?: pos), ch.toString())
            InputRules.forTyped(flow, caret, ch.toString())?.let {
                last = InputRules.apply(flow, caret, it)
            } ?: run { last = InputRules.Result(null, caret) }
        }
        assertTrue(last!!.math)
    }

    @Test
    fun doubledDollarsSetADisplayEquationAndCentreIt() {
        val flow = TextFlow()
        type(flow, "\$\$\\sum_{i=1}^n i\$\$")
        val p = flow.paragraphs[0]
        val run = p.runs.single()
        assertEquals("\\sum_{i=1}^n i", run.text)
        assertTrue(run.style.math)
        assertTrue(run.style.mathDisplay)
        assertEquals(ParaAlign.CENTER, p.align)
    }

    @Test
    fun aDisplayEquationMidSentenceStaysWhereItWasPut() {
        val flow = TextFlow()
        type(flow, "see \$\$x^2\$\$")
        val p = flow.paragraphs[0]
        assertEquals("see x^2", p.plainText())
        assertTrue(p.runs.last().style.mathDisplay)
        assertEquals(ParaAlign.LEFT, p.align)
    }

    @Test
    fun anInlineFormulaIsNotADisplayOne() {
        val p = typed("\$x^2\$")
        assertTrue(p.runs.single().style.math)
        assertFalse(p.runs.single().style.mathDisplay)
        assertEquals(ParaAlign.LEFT, p.align)
    }

    @Test
    fun emptyDoubledDollarsSetNothing() {
        assertEquals("\$\$\$\$", typed("\$\$\$\$").plainText())
    }

}
