package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formula edge, which is two places at one offset. Throughout: "x^2" set as
 * a formula over 0..3, then "moretext" as ordinary prose over 3..11, so offset 3
 * is both the end of the LaTeX and the spot in front of the "m".
 */
class MathCaretTest {

    private val para = Paragraph(
        mutableListOf(
            Run("x^2", CharStyle(math = true)),
            Run("moretext", CharStyle.DEFAULT),
        ),
    )

    /**
     * Walks the caret by [delta] from [from] the way the editor does, returning
     * the offset and the open formula after each press. A press is spent on an
     * edge rather than a move whenever one is reached, in either direction.
     */
    private fun arrow(p: Paragraph, from: Int, delta: Int, presses: Int): List<Pair<Int, Int>> {
        var off = from
        var held = -1
        val out = mutableListOf<Pair<Int, Int>>()
        val last = p.runs.sumOf { it.text.length }
        repeat(presses) {
            val exit = MathCaret.exitAt(p, off, delta, held)
            val edge = MathCaret.edgeAt(p, off, delta)
            when {
                exit >= 0 -> held = -1
                edge >= 0 && held != edge -> held = edge
                else -> {
                    off = (off + delta).coerceIn(0, last)
                    if (!MathCaret.holds(p, off, held)) held = -1
                }
            }
            out += off to MathCaret.revealed(p, off, held)
        }
        return out
    }

    private fun arrowLeft(from: Int, presses: Int) = arrow(para, from, -1, presses)

    @Test
    fun arrowingLeftStopsAtTheFormulaEdgeBeforeEnteringIt() {
        // From between "m" and "o": out to the edge, into the edge, then through.
        assertEquals(
            listOf(3 to -1, 3 to 0, 2 to 0, 1 to 0, 0 to 0),
            arrowLeft(from = 4, presses = 5),
        )
    }

    @Test
    fun theSecondPressOpensTheSourceWithoutMovingTheCaret() {
        val steps = arrowLeft(from = 4, presses = 2)
        assertEquals(3, steps[0].first)
        assertEquals(3, steps[1].first)
        // Same offset both times: only which side of the edge it is on changed.
        assertEquals(-1, steps[0].second)
        assertEquals(0, steps[1].second)
    }

    @Test
    fun walkingOffTheFarEdgeReleasesTheFormula() {
        val before = Paragraph(
            mutableListOf(Run("ab"), Run("x^2", CharStyle(math = true)), Run("cd")),
        )
        // Held at its start (offset 2), one more press leaves it entirely.
        assertTrue(MathCaret.holds(before, 2, 2))
        assertFalse(MathCaret.holds(before, 1, 2))
        assertEquals(-1, MathCaret.revealed(before, 1, -1))
    }

    @Test
    fun movingRightEntersAtTheFormulasStartInstead() {
        val before = Paragraph(
            mutableListOf(Run("ab"), Run("x^2", CharStyle(math = true)), Run("cd")),
        )
        // Every one of these names the formula by its start, which is the key a
        // hold is kept under, not the offset the caret is standing on.
        assertEquals(2, MathCaret.edgeAt(before, 2, 1))
        assertEquals(-1, MathCaret.edgeAt(before, 2, -1))
        assertEquals(2, MathCaret.edgeAt(before, 5, -1))
        assertEquals(-1, MathCaret.edgeAt(before, 5, 1))
    }

    @Test
    fun anEdgeIsNotWithinTheFormulaOnItsOwn() {
        assertEquals(-1, MathCaret.within(para, 0))
        assertEquals(-1, MathCaret.within(para, 3))
        assertEquals(0, MathCaret.within(para, 1))
        assertEquals(0, MathCaret.within(para, 2))
    }

    @Test
    fun typingAtAHeldEdgeGoesIntoTheLatex() {
        // The caret shows the source at offset 3, so what is typed there belongs
        // to the formula; without the hold the same offset is ordinary prose.
        assertTrue(MathCaret.heldStyle(para, 3, 0)!!.math)
        assertNull(MathCaret.heldStyle(para, 3, -1))
        assertNull(MathCaret.heldStyle(para, 4, 0))
    }

    @Test
    fun aHoldOnlyEverCoversItsOwnFormula() {
        val two = Paragraph(
            mutableListOf(
                Run("a", CharStyle(math = true)),
                Run(" and ", CharStyle.DEFAULT),
                Run("b", CharStyle(math = true)),
            ),
        )
        assertTrue(MathCaret.holds(two, 1, 0))
        assertFalse(MathCaret.holds(two, 6, 0))
        assertEquals(6, MathCaret.revealed(two, 6, 6))
        assertEquals(-1, MathCaret.revealed(two, 6, 0))
    }

    @Test
    fun aParagraphWithNoFormulasNeverHoldsAnything() {
        val plain = Paragraph(mutableListOf(Run("just words")))
        assertEquals(-1, MathCaret.within(plain, 4))
        assertEquals(-1, MathCaret.edgeAt(plain, 4, -1))
        assertEquals(-1, MathCaret.revealed(plain, 4, 0))
        assertFalse(MathCaret.holds(plain, 4, 0))
        assertNull(MathCaret.heldStyle(plain, 4, 0))
    }
    // --- leaving is the same shape as arriving ---

    @Test
    fun steppingOutOfTheSourceCostsThePressThatSteppingInDid() {
        val mid = Paragraph(
            mutableListOf(Run("ab"), Run("x^2", CharStyle(math = true)), Run("cd")),
        )
        // From between "c" and "d": to the edge, into it, across the LaTeX, out
        // of it at the far edge, and only then on into the "ab".
        assertEquals(
            listOf(5 to -1, 5 to 2, 4 to 2, 3 to 2, 2 to 2, 2 to -1, 1 to -1),
            arrow(mid, from = 6, delta = -1, presses = 7),
        )
    }

    @Test
    fun leavingToTheRightWorksTheSameWayRound() {
        val mid = Paragraph(
            mutableListOf(Run("ab"), Run("x^2", CharStyle(math = true)), Run("cd")),
        )
        assertEquals(
            listOf(2 to 2, 3 to 2, 4 to 2, 5 to 2, 5 to -1, 6 to -1),
            arrow(mid, from = 2, delta = 1, presses = 6),
        )
    }

    @Test
    fun theFarEdgeReleasesAndTheNearEdgeDoesNot() {
        val mid = Paragraph(
            mutableListOf(Run("ab"), Run("x^2", CharStyle(math = true)), Run("cd")),
        )
        // Held in the formula at offset 2: left leaves it, right goes deeper.
        assertEquals(2, MathCaret.exitAt(mid, 2, -1, held = 2))
        assertEquals(-1, MathCaret.exitAt(mid, 2, 1, held = 2))
        assertEquals(2, MathCaret.exitAt(mid, 5, 1, held = 2))
        assertEquals(-1, MathCaret.exitAt(mid, 5, -1, held = 2))
        // And nothing is released when nothing is held.
        assertEquals(-1, MathCaret.exitAt(mid, 2, -1, held = -1))
    }

    @Test
    fun adjacentFormulasAreLeftAndEnteredOneAtATime() {
        val two = Paragraph(
            mutableListOf(
                Run("a", CharStyle(math = true)),
                Run("b", CharStyle(math = true)),
            ),
        )
        // Offset 1 ends the first formula and starts the second, so it is stood on
        // three times over: inside the first, inside neither, inside the second.
        // One press may not both leave one formula and enter the next.
        assertEquals(
            listOf(0 to 0, 1 to 0, 1 to -1, 1 to 1, 2 to 1, 2 to -1),
            arrow(two, from = 0, delta = 1, presses = 6),
        )
    }

    // --- deleting into a formula ---
    //
    // Backspace travels left and forward delete travels right, so both ask the
    // same question an arrow does: is the caret standing at an edge? A press that
    // is spent stepping in deletes nothing, and the one after it deletes LaTeX
    // the user can now see.

    /**
     * Backspacing from [from], as the editor does it: the returned pair is the
     * text left and which formula is open, after each press.
     */
    private fun backspace(p: Paragraph, from: Int, presses: Int): List<Pair<String, Int>> {
        val flow = TextFlow().apply { paragraphs.add(p) }
        var off = from
        var held = -1
        val out = mutableListOf<Pair<String, Int>>()
        repeat(presses) {
            val edge = MathCaret.edgeAt(p, off, -1)
            if (edge >= 0 && held != edge) {
                held = edge
            } else if (off > 0) {
                FlowEditor(flow).deleteRange(FlowRange(FlowPos(0, off - 1), FlowPos(0, off)))
                off--
                if (!MathCaret.holds(p, off, held)) held = -1
            }
            out += p.plainText() to MathCaret.revealed(p, off, held)
        }
        return out
    }

    @Test
    fun theFirstBackspaceOpensTheFormulaAndDeletesNothing() {
        val p = Paragraph(mutableListOf(Run("x^2", CharStyle(math = true))))
        val steps = backspace(p, from = 3, presses = 3)
        // Opened, still whole; then the LaTeX goes one character at a time.
        assertEquals("x^2" to 0, steps[0])
        assertEquals("x^" to 0, steps[1])
        assertEquals("x" to 0, steps[2])
    }

    @Test
    fun backspaceBesideAFormulaStillDeletesTheOrdinaryTextFirst() {
        val p = Paragraph(
            mutableListOf(Run("x^2", CharStyle(math = true)), Run("ab", CharStyle.DEFAULT)),
        )
        val steps = backspace(p, from = 5, presses = 3)
        // The prose goes first, and only then does the edge come into play.
        assertEquals("x^2a" to -1, steps[0])
        assertEquals("x^2" to -1, steps[1])
        assertEquals("x^2" to 0, steps[2])
    }

    @Test
    fun forwardDeleteEntersAtTheStartTheWayBackspaceEntersAtTheEnd() {
        val p = Paragraph(
            mutableListOf(Run("ab"), Run("x^2", CharStyle(math = true))),
        )
        // Forward delete travels right, so its edge is the formula's start.
        assertEquals(2, MathCaret.edgeAt(p, 2, 1))
        assertEquals(-1, MathCaret.edgeAt(p, 2, -1))
    }

    @Test
    fun aCaretLeftBehindAShrunkenFormulaIsNotOnItAnyMore() {
        // What a deletion leaves for an instant: the LaTeX has lost its last
        // character but the caret has not moved off the end yet. Laying out
        // against that pair draws the formula, so whatever decides to lay out
        // again has to compare against what was drawn, not what was intended.
        val shrunk = Paragraph(mutableListOf(Run("x^", CharStyle(math = true))))
        assertFalse(MathCaret.holds(shrunk, 3, 0))
        assertEquals(-1, MathCaret.revealed(shrunk, 3, 0))
        // Once the caret catches up it is inside the formula again.
        assertTrue(MathCaret.holds(shrunk, 2, 0))
        assertEquals(0, MathCaret.revealed(shrunk, 2, 0))
    }

    @Test
    fun deletingTheLastOfTheLatexLetsTheFormulaGo() {
        val gone = Paragraph(mutableListOf(Run("after", CharStyle.DEFAULT)))
        assertFalse(MathCaret.holds(gone, 0, 0))
        assertEquals(-1, MathCaret.revealed(gone, 0, 0))
        assertNull(MathCaret.heldStyle(gone, 0, 0))
    }

}
