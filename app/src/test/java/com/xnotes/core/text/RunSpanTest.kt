package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The style spans a text box lays over its plain text: how a keystroke shifts them and
 * how a restyle splits and merges them.
 */
class RunSpanTest {
    private val yellow = CharStyle(highlight = Rgba(255, 235, 59, 255))
    private val green = CharStyle(highlight = Rgba(120, 220, 120, 255))

    // --- shiftRunSpans ---

    @Test
    fun aSpanBeforeTheEditHoldsStill() {
        val spans = listOf(RunSpan(0, 2, yellow))
        assertEquals(spans, shiftRunSpans(spans, at = 4, removed = 0, added = 3))
    }

    @Test
    fun aSpanCoveringTheEditGrowsWithIt() {
        // Typing inside a highlighted stretch keeps the whole stretch highlighted.
        assertEquals(listOf(RunSpan(2, 9, yellow)), shiftRunSpans(listOf(RunSpan(2, 7, yellow)), at = 4, removed = 1, added = 3))
    }

    @Test
    fun aSpanAfterTheEditSlidesByTheLengthChange() {
        assertEquals(listOf(RunSpan(8, 12, yellow)), shiftRunSpans(listOf(RunSpan(6, 10, yellow)), at = 3, removed = 2, added = 4))
    }

    @Test
    fun aSpanCoveringTheDeletionPinsAndSurvives() {
        // A delete under a highlighted stretch leaves the surviving piece highlighted,
        // pinned to the deletion point; a span wholly inside the deletion drops.
        assertEquals(listOf(RunSpan(3, 4, yellow)), shiftRunSpans(listOf(RunSpan(3, 5, yellow)), at = 3, removed = 2, added = 1))
        assertEquals(emptyList<RunSpan>(), shiftRunSpans(listOf(RunSpan(3, 5, yellow)), at = 3, removed = 3, added = 1))
    }

    @Test
    fun aSpanEndingInsideTheEditClips() {
        assertEquals(listOf(RunSpan(1, 3, yellow)), shiftRunSpans(listOf(RunSpan(1, 5, yellow)), at = 3, removed = 4, added = 1))
    }

    @Test
    fun anEmptiedSpanDrops() {
        assertEquals(emptyList<RunSpan>(), shiftRunSpans(listOf(RunSpan(3, 5, yellow)), at = 2, removed = 5, added = 0))
    }

    @Test
    fun aDeletionShiftsTheSpansPastIt() {
        assertEquals(
            listOf(RunSpan(0, 1, yellow), RunSpan(3, 5, green)),
            shiftRunSpans(listOf(RunSpan(0, 1, yellow), RunSpan(5, 7, green)), at = 2, removed = 2, added = 0),
        )
    }

    // --- restyleRunSpans ---

    @Test
    fun aHighlightOverPlainTextMakesItsOwnSpan() {
        assertEquals(listOf(RunSpan(2, 5, yellow)), restyleRunSpans(emptyList(), 2, 5) { it.copy(highlight = yellow.highlight) })
    }

    @Test
    fun aRestyleSplitsTheSpanItCrosses() {
        // Highlighting the middle of a bold stretch: the bold keeps outside, the middle
        // is bold AND highlighted, and the split edges stay where the selection put them.
        val bold = CharStyle(bold = true)
        val out = restyleRunSpans(listOf(RunSpan(0, 8, bold)), 2, 5) { it.copy(highlight = yellow.highlight) }
        assertEquals(
            listOf(RunSpan(0, 2, bold), RunSpan(2, 5, bold.copy(highlight = yellow.highlight)), RunSpan(5, 8, bold)),
            out,
        )
    }

    @Test
    fun uncoveredGapsTakeTheAppliedBaseStyle() {
        // Toggling bold over 0..10 with one existing bold span 4..6: the whole range ends bold.
        val bold = CharStyle(bold = true)
        val out = restyleRunSpans(listOf(RunSpan(4, 6, bold)), 0, 10) { it.copy(bold = true) }
        assertEquals(listOf(RunSpan(0, 10, bold)), out)
    }

    @Test
    fun aClearRestoresThePlainPiecesItCovers() {
        // Clearing a highlight that covers two spans: the covered pieces lose it and merge
        // back into one plain stretch, while the untouched spans keep their styles.
        val out = restyleRunSpans(listOf(RunSpan(0, 3, yellow), RunSpan(6, 9, green)), 1, 8) { it.copy(highlight = null) }
        assertEquals(
            listOf(RunSpan(0, 1, yellow), RunSpan(1, 8, CharStyle.DEFAULT), RunSpan(8, 9, green)),
            out,
        )
    }

    @Test
    fun anEmptySelectionChangesNothing() {
        val spans = listOf(RunSpan(0, 2, yellow))
        assertEquals(spans, restyleRunSpans(spans, 3, 3) { it.copy(bold = true) })
    }
}
