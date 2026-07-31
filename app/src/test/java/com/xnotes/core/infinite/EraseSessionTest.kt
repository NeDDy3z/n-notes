package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EraseSessionTest {

    /** A horizontal stroke from x=0 to x=[length] along y=[y], one sample every 5 px. */
    private fun line(length: Double, y: Double = 0.0): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        (0..(length / 5).toInt()).map { Sample(it * 5.0, y, 1.0) }.toMutableList(),
    )

    private fun image(x: Double, y: Double): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, 10.0, 10.0))

    private fun docOf(vararg items: CanvasItem): InfiniteDocument =
        InfiniteDocument().apply { addAll(items.toList()) }

    // --- whole-stroke mode ---

    @Test fun wholeStrokeModeRemovesWhatItTouches() {
        val hit = line(100.0)
        val miss = line(100.0, y = 500.0)
        val doc = docOf(hit, miss)
        val session = EraseSession(doc)

        assertNotNull(session.erase(50.0, 0.0, 8.0, area = false))
        assertEquals(listOf<CanvasItem>(miss), doc.items)
    }

    @Test fun wholeStrokeModeMissesWhatIsOutOfReach() {
        val doc = docOf(line(100.0))
        val session = EraseSession(doc)
        assertNull(session.erase(50.0, 400.0, 8.0, area = false))
        assertEquals(1, doc.itemCount)
        assertTrue(session.isEmpty)
    }

    @Test fun imagesSurviveBothModes() {
        val img = image(0.0, 0.0)
        val doc = docOf(img)
        val session = EraseSession(doc)
        assertNull(session.erase(5.0, 5.0, 20.0, area = false))
        assertNull(session.erase(5.0, 5.0, 20.0, area = true))
        assertEquals(listOf<CanvasItem>(img), doc.items)
    }

    @Test fun aDragThatRemovesSeveralStrokesIsOneEdit() {
        val a = line(60.0, y = 0.0)
        val b = line(60.0, y = 6.0)
        val doc = docOf(a, b)
        val session = EraseSession(doc)
        session.erase(30.0, 3.0, 20.0, area = false)
        assertTrue(doc.isEmpty)

        val cmd = session.buildCommand()!!
        cmd.undo()
        assertEquals(listOf<CanvasItem>(a, b), doc.items)
        cmd.redo()
        assertTrue(doc.isEmpty)
    }

    // --- area mode ---

    @Test fun areaModeCutsAStrokeInTwo() {
        val stroke = line(100.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)

        assertNotNull(session.erase(50.0, 0.0, 8.0, area = true))
        assertEquals("a mid-stroke hole leaves two fragments", 2, doc.itemCount)
        assertTrue(doc.items.all { it is Stroke && it !== stroke })
        val left = doc.items[0] as Stroke
        val right = doc.items[1] as Stroke
        assertTrue(left.samples.last().x < 50.0)
        assertTrue(right.samples.first().x > 50.0)
    }

    @Test fun fragmentsTakeTheOriginalsPlaceInTheOrder() {
        val under = line(20.0, y = 100.0)
        val cut = line(100.0)
        val over = line(20.0, y = 200.0)
        val doc = docOf(under, cut, over)
        val session = EraseSession(doc)

        session.erase(50.0, 0.0, 8.0, area = true)
        assertEquals(4, doc.itemCount)
        assertSame("the item below must stay below", under, doc.items.first())
        assertSame("the item above must stay above", over, doc.items.last())
    }

    @Test fun trimmingAnEndLeavesOneFragment() {
        val stroke = line(100.0)
        val doc = docOf(stroke)
        EraseSession(doc).erase(0.0, 0.0, 8.0, area = true)
        assertEquals(1, doc.itemCount)
        assertTrue((doc.items[0] as Stroke).samples.first().x > 0.0)
    }

    @Test fun erasingEveryPointRemovesTheStrokeOutright() {
        val stroke = line(20.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)
        session.erase(10.0, 0.0, 60.0, area = true)
        assertTrue(doc.isEmpty)

        // Nothing is left to find the stroke by, so the recorded slot is what brings it back.
        session.buildCommand()!!.undo()
        assertEquals(listOf<CanvasItem>(stroke), doc.items)
    }

    @Test fun undoRestoresTheExactListAfterAnAreaCut() {
        val before = listOf(line(20.0, 100.0), line(100.0), line(20.0, 200.0))
        val doc = InfiniteDocument().apply { addAll(before) }
        val session = EraseSession(doc)
        session.erase(50.0, 0.0, 8.0, area = true)
        val cmd = session.buildCommand()!!

        cmd.undo()
        assertEquals(before, doc.items)
        cmd.redo()
        assertEquals(4, doc.itemCount)
        cmd.undo()
        assertEquals(before, doc.items)
    }

    @Test fun aFragmentCutAgainStillUndoesToTheOriginal() {
        val stroke = line(200.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)

        session.erase(60.0, 0.0, 8.0, area = true) // splits into two
        assertEquals(2, doc.itemCount)
        session.erase(140.0, 0.0, 8.0, area = true) // cuts the right-hand fragment again
        assertEquals(3, doc.itemCount)

        val cmd = session.buildCommand()!!
        cmd.undo()
        assertEquals("one drag, one original, however many cuts", listOf<CanvasItem>(stroke), doc.items)
        cmd.redo()
        assertEquals(3, doc.itemCount)
    }

    @Test fun severalOriginalsCutInOneDragAllComeBack() {
        val a = line(120.0, y = 0.0)
        val b = line(120.0, y = 60.0)
        val doc = docOf(a, b)
        val session = EraseSession(doc)

        session.erase(60.0, 0.0, 8.0, area = true)
        session.erase(60.0, 60.0, 8.0, area = true)
        assertEquals(4, doc.itemCount)

        session.buildCommand()!!.undo()
        assertEquals(listOf<CanvasItem>(a, b), doc.items)
    }

    @Test fun cuttingTheLowerItemFirstStillUndoesInOrder() {
        // The recorded slots are captured against different list states, so undo has to run in
        // reverse to read each one against the state it was taken in.
        val a = line(120.0, y = 0.0)
        val b = line(120.0, y = 60.0)
        val c = line(120.0, y = 120.0)
        val doc = docOf(a, b, c)
        val session = EraseSession(doc)

        session.erase(60.0, 0.0, 8.0, area = true)   // splits a, shifting b and c along
        session.erase(60.0, 120.0, 8.0, area = true) // then splits c
        session.buildCommand()!!.undo()
        assertEquals(listOf<CanvasItem>(a, b, c), doc.items)
    }

    // --- bookkeeping ---

    @Test fun aDragThatCutsNothingProducesNoEdit() {
        val doc = docOf(line(100.0))
        val session = EraseSession(doc)
        session.erase(50.0, 900.0, 8.0, area = true)
        assertTrue(session.isEmpty)
        assertNull(session.buildCommand())
    }

    @Test fun theSpatialIndexTracksTheFragments() {
        val doc = docOf(line(100.0))
        EraseSession(doc).erase(50.0, 0.0, 8.0, area = true)
        assertEquals(doc.itemCount, doc.index.size)
        assertEquals(2, doc.itemsIn(Rect(-20.0, -20.0, 160.0, 40.0)).size)
        // The hole is really a hole: nothing is indexed where the eraser passed.
        assertTrue(doc.itemsIn(Rect(48.0, -1.0, 4.0, 2.0)).isEmpty())
    }

    @Test fun theIndexIsRestoredByUndo() {
        val stroke = line(100.0)
        val doc = docOf(stroke)
        val session = EraseSession(doc)
        session.erase(50.0, 0.0, 8.0, area = true)
        session.buildCommand()!!.undo()
        assertEquals(1, doc.index.size)
        assertSame(stroke, doc.itemsIn(Rect(40.0, -10.0, 20.0, 20.0)).single())
    }

    @Test fun onlyItemsUnderTheEraserAreEvenConsidered() {
        // A far-off stroke must not be touched, whatever the document holds.
        val near = line(40.0)
        val far = line(40.0, y = 50_000.0)
        val doc = docOf(near, far)
        EraseSession(doc).erase(20.0, 0.0, 10.0, area = false)
        assertEquals(listOf<CanvasItem>(far), doc.items)
    }

    @Test fun contentBoundsFollowTheCut() {
        val doc = docOf(line(100.0))
        val wide = doc.contentBounds()!!.w
        EraseSession(doc).erase(95.0, 0.0, 20.0, area = true)
        assertTrue("trimming the end must shrink the extent", doc.contentBounds()!!.w < wide)
    }
}
