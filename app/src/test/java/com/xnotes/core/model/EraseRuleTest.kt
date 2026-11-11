package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.SelectKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseRuleTest {

    private val sky = Rgba(90, 160, 240, 255)

    private fun fill() = ShapeItem.fill(listOf(listOf(Pt(0.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 200.0), Pt(0.0, 200.0))), sky)

    private fun ink() = Stroke(Tool.PEN, ToolConfig(), (0..20).map { Sample(it * 5.0, 100.0, 1.0) })

    private fun box(fill: Rgba? = sky) = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 200.0), Rgba(1, 1, 1, 255), 3.0, fill)

    @Test fun aFillTheEraserTouchesAtAllGoesWhole() {
        assertTrue(EraseRule.takes(fill(), 50.0, 30.0, 6.0))
        assertTrue("even over ink", EraseRule.takes(fill(), 50.0, 100.0, 6.0))
        assertTrue("even just at its edge", EraseRule.takes(fill(), -4.0, 100.0, 6.0))
        assertFalse(EraseRule.takes(fill(), -10.0, 100.0, 6.0))
    }

    @Test fun linesGoAsEver() {
        assertTrue(EraseRule.takes(ink(), 50.0, 100.0, 6.0))
        assertFalse(EraseRule.takes(ink(), 50.0, 30.0, 6.0))
        assertTrue("an outline takes its shape", EraseRule.takes(box(), 100.0, 0.0, 6.0))
    }

    @Test fun aFilledShapeTouchedInsideLosesOnlyItsFill() {
        assertTrue(EraseRule.takesFill(box(), 100.0, 100.0, 6.0))
        assertFalse(EraseRule.takes(box(), 100.0, 100.0, 6.0))
        assertFalse("on its outline it goes whole instead", EraseRule.takesFill(box(), 100.0, 0.0, 6.0))
        assertFalse("a hollow one has nothing to lose inside", EraseRule.takesFill(box(null), 100.0, 100.0, 6.0))
    }

    @Test fun lockedItemsAreNeverTaken() {
        val locked = fill().also { it.locked = true }
        assertFalse(EraseRule.takes(locked, 50.0, 30.0, 6.0))
    }

    /**
     * The eraser's filter only narrows: a switched-off kind survives the circle, a switched-on one
     * goes as ever, and no filter at all is today's behaviour. A shape's fill follows its kind, so
     * a SHAPES filter governs a dropped colour too.
     */
    @Test fun theFilterNarrowsWhatTheEraserTakes() {
        assertFalse(EraseRule.takes(ink(), 50.0, 100.0, 6.0, setOf(SelectKind.SHAPES, SelectKind.TAPE)))
        assertTrue(EraseRule.takes(ink(), 50.0, 100.0, 6.0, null))
        assertFalse(EraseRule.takes(ink(), 50.0, 100.0, 6.0, setOf(SelectKind.SHAPES)))
        assertTrue(EraseRule.takes(fill(), 50.0, 30.0, 6.0, setOf(SelectKind.SHAPES)))
        assertFalse(EraseRule.takesFill(box(), 100.0, 100.0, 6.0, setOf(SelectKind.INK)))
        assertTrue(EraseRule.takesFill(box(), 100.0, 100.0, 6.0, setOf(SelectKind.SHAPES)))
    }

    /** The filter cannot open what the eraser never takes anyway: the protected kinds stay out. */
    @Test fun theFilterNeverWidensWhatTheEraserTakes() {
        val text = TextItem(Pt(10.0, 10.0), width = 60.0, text = "x", measurer = FakeTextMeasurer())
        val audio = AudioItem(AudioData(java.io.File("unused"), "m4a", 60_000, ByteArray(8) { 40 }), Rect(10.0, 10.0, 60.0, 16.0), "n", 1, FakeTextMeasurer())
        for (item in listOf(text, audio)) {
            assertFalse("$item is protected without a filter", EraseRule.erasable(item))
            assertFalse("$item must stay protected under any filter", EraseRule.erasable(item, SelectKind.entries.toSet()))
        }
    }

    /** Only the kinds the eraser can reach are offered as its filter; a tape among them, erasably. */
    @Test fun theEraserTakesListNamesWhatItCanReach() {
        assertEquals(listOf(SelectKind.INK, SelectKind.SHAPES, SelectKind.TAPE), SelectKind.ERASER_TAKES)
        val sample: Map<SelectKind, CanvasItem> = mapOf(
            SelectKind.INK to ink(),
            SelectKind.SHAPES to box(),
            SelectKind.TAPE to TapeItem(TapeShape(TapeMode.RECTANGLE, listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 30.0), Pt(0.0, 30.0)), 30.0), sky),
        )
        for (kind in SelectKind.ERASER_TAKES) {
            val item = sample.getValue(kind)
            assertTrue("$kind is offered but claims no item here", SelectKind.of(item) == kind)
            assertTrue("$kind is offered but not erasable", EraseRule.erasable(item))
        }
    }
}
