package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    /** A one-paragraph flow holding [text], with the caret at its end. */
    private fun flowOf(text: String): Pair<TextFlow, FlowPos> {
        val flow = TextFlow()
        FlowEditor(flow).replaceRange(FlowRange.caret(FlowPos.START), text, null)
        return flow to FlowPos(0, text.length)
    }

    private fun queryOf(text: String): SlashCommands.Query? {
        val (flow, pos) = flowOf(text)
        return SlashCommands.queryAt(flow, pos)
    }

    private fun ids(text: String): List<String> =
        queryOf(text)?.let { q -> SlashCommands.candidates(q).map { it.id } }.orEmpty()

    // --- when the menu opens ---

    @Test
    fun aSlashAtTheStartOfALineOpensTheWholeMenu() {
        val q = queryOf("/")
        assertNotNull(q)
        assertEquals(SlashCommands.entries().size, SlashCommands.candidates(q!!).size)
    }

    @Test
    fun aSlashAfterASpaceOpensTheMenu() {
        assertNotNull(queryOf("note /"))
    }

    @Test
    fun aSlashInsideAWordDoesNotOpenTheMenu() {
        assertNull(queryOf("and/or"))
        assertNull(queryOf("1/2"))
    }

    @Test
    fun aUrlNeverOpensTheMenu() {
        assertNull(queryOf("https://example.com"))
    }

    @Test
    fun theMenuClosesWhenTheWordMatchesNothing() {
        assertNull(queryOf("/zzz"))
    }

    @Test
    fun theMenuClosesOnceTheQueryRunsLong() {
        assertNull(queryOf("/" + "table ".repeat(6)))
    }

    @Test
    fun aSlashInACodeBlockIsJustASlash() {
        val (flow, pos) = flowOf("/table")
        flow.paragraphs[0].codeLang = "python"
        assertNull(SlashCommands.queryAt(flow, pos))
    }

    // --- filtering ---

    @Test
    fun aBareHPrefixOffersEveryHeading() {
        assertEquals(listOf("h1", "h2", "h3", "h4", "h5", "h6"), ids("/h"))
    }

    @Test
    fun aNumberedHeadingNarrowsToOne() {
        assertEquals(listOf("h2"), ids("/h2"))
    }

    @Test
    fun aliasesReachTheSameEntry() {
        assertEquals(listOf("bullet"), ids("/ul"))
        assertEquals(listOf("color"), ids("/colour"))
        assertEquals(listOf("todo"), ids("/task"))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(listOf("table"), ids("/TAB"))
    }

    @Test
    fun aSpaceClosesTheKeywordAndFreezesTheList() {
        // "/code p" must stay on code rather than re-filtering to "pre" or "paragraph".
        assertEquals(listOf("code"), ids("/code p"))
    }

    @Test
    fun anArgumentIsSplitOffTheKeyword() {
        val q = queryOf("/table 3x4")!!
        assertEquals("table", q.word)
        assertEquals("3x4", q.arg)
        assertTrue(q.hasArg)
    }

    @Test
    fun anEntryNeedingAnArgumentIsNotReadyWithoutOne() {
        val entry = SlashCommands.entries().first { it.id == "size" }
        assertEquals(false, SlashCommands.ready(queryOf("/size")!!, entry))
        assertTrue(SlashCommands.ready(queryOf("/size 18")!!, entry))
    }

    @Test
    fun anEntryWithoutAnArgumentIsAlwaysReady() {
        val entry = SlashCommands.entries().first { it.id == "h1" }
        assertTrue(SlashCommands.ready(queryOf("/h1")!!, entry))
    }

    // --- the double-slash escape ---

    @Test
    fun aSecondSlashMarksTheFirstForDeletion() {
        val (flow, pos) = flowOf("//")
        assertEquals(1, SlashCommands.escapeAt(flow, pos))
    }

    @Test
    fun theEscapeLeavesOneLiteralSlash() {
        val (flow, pos) = flowOf("//")
        val at = SlashCommands.escapeAt(flow, pos)!!
        FlowEditor(flow).deleteRange(FlowRange(FlowPos(0, at), FlowPos(0, at + 1)))
        assertEquals("/", flow.paragraphs[0].plainText())
    }

    @Test
    fun theEscapedSlashStillReadsAsAQuery() {
        // Nothing in the text distinguishes it from a slash just typed, so keeping the
        // menu shut afterwards is the host's flag to hold, not something detection can see.
        val (flow, pos) = flowOf("//")
        val at = SlashCommands.escapeAt(flow, pos)!!
        FlowEditor(flow).deleteRange(FlowRange(FlowPos(0, at), FlowPos(0, at + 1)))
        assertNotNull(SlashCommands.queryAt(flow, FlowPos(0, 1)))
    }

    @Test
    fun aSlashPairInsideAWordIsNotAnEscape() {
        val (flow, pos) = flowOf("http://")
        assertNull(SlashCommands.escapeAt(flow, pos))
    }

    @Test
    fun aLoneSlashIsNotAnEscape() {
        val (flow, pos) = flowOf("/")
        assertNull(SlashCommands.escapeAt(flow, pos))
    }

    // --- committing ---

    @Test
    fun strippingRemovesTheWholeQueryAndParksTheCaret() {
        val (flow, pos) = flowOf("todo /h2")
        val q = SlashCommands.queryAt(flow, pos)!!
        val (cmd, caret) = SlashCommands.strip(flow, q)
        assertEquals("todo ", flow.paragraphs[0].plainText())
        assertEquals(FlowPos(0, 5), caret)
        assertNotNull(cmd)
    }

    @Test
    fun strippingIsOneUndoStep() {
        val (flow, pos) = flowOf("/bullet")
        val q = SlashCommands.queryAt(flow, pos)!!
        val (cmd, _) = SlashCommands.strip(flow, q)
        cmd!!.undo()
        assertEquals("/bullet", flow.paragraphs[0].plainText())
    }

    @Test
    fun replacingSwapsTheQueryForItsText() {
        val (flow, pos) = flowOf("on /date")
        val q = SlashCommands.queryAt(flow, pos)!!
        val (_, caret) = SlashCommands.replace(flow, q, "22 Sep 2026")
        assertEquals("on 22 Sep 2026", flow.paragraphs[0].plainText())
        assertEquals(FlowPos(0, 14), caret)
    }

    @Test
    fun everyEntryIdIsUnique() {
        val ids = SlashCommands.entries().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyEntryIsReachableByItsOwnId() {
        // A menu entry no query can select would be dead weight in the list.
        for (entry in SlashCommands.entries()) {
            assertTrue(entry.id, ids("/${entry.id}").contains(entry.id))
        }
    }
    @Test
    fun equationIsReachableAndWantsItsLatex() {
        assertTrue("equation" in ids("/eq"))
        val entry = SlashCommands.entries().first { it.id == "equation" }
        assertTrue(entry.needsArg)
        assertFalse(SlashCommands.ready(queryOf("/equation ")!!, entry))
        assertTrue(SlashCommands.ready(queryOf("/equation x^2")!!, entry))
    }

    @Test
    fun aFormulaArgumentMayRunLongerThanAKeyword() {
        val long = "\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a} + \\int_0^1 f(x) dx"
        assertTrue(long.length > SlashCommands.MAX_QUERY)
        val q = queryOf("/equation $long")
        assertEquals(long, q!!.arg)
    }

    @Test
    fun aLongWordIsStillProseNotACommand() {
        assertNull(queryOf("/" + "a".repeat(SlashCommands.MAX_QUERY + 1)))
    }

}
