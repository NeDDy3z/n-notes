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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InfiniteDocumentTest {

    private fun boxAt(x: Double, y: Double, w: Double = 10.0, h: Double = 10.0): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, w, h))

    private fun strokeAt(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    /** Records the change callbacks so tests can assert the renderer would be told the right thing. */
    private class Recorder : InfiniteDocument.Listener {
        val log = mutableListOf<String>()
        override fun onItemAdded(item: CanvasItem) { log += "add" }
        override fun onItemRemoved(item: CanvasItem) { log += "remove" }
        override fun onItemChanged(item: CanvasItem) { log += "change" }
        override fun onReset() { log += "reset" }
    }

    @Test fun aNewCanvasIsEmpty() {
        val doc = InfiniteDocument()
        assertTrue(doc.isEmpty)
        assertEquals(0, doc.itemCount)
        assertNull(doc.contentBounds())
        assertTrue(doc.visibleItems(Rect(-1e6, -1e6, 2e6, 2e6)).isEmpty())
    }

    @Test fun addingIndexesTheItem() {
        val doc = InfiniteDocument()
        val a = boxAt(100.0, 100.0)
        doc.add(a)
        assertEquals(1, doc.itemCount)
        assertEquals(1, doc.index.size)
        assertSame(a, doc.visibleItems(Rect(90.0, 90.0, 40.0, 40.0)).single())
    }

    @Test fun removingUnindexesTheItem() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        assertTrue(doc.remove(a))
        assertEquals(0, doc.index.size)
        assertTrue(doc.visibleItems(Rect(-100.0, -100.0, 200.0, 200.0)).isEmpty())
    }

    @Test fun removingSomethingAbsentReportsFalse() {
        val doc = InfiniteDocument()
        assertTrue(!doc.remove(boxAt(0.0, 0.0)))
    }

    @Test fun visibleItemsComeBackInZOrder() {
        val doc = InfiniteDocument()
        val back = boxAt(0.0, 0.0, 100.0, 100.0)
        val mid = boxAt(10.0, 10.0, 100.0, 100.0)
        val front = boxAt(20.0, 20.0, 100.0, 100.0)
        doc.addAll(listOf(back, mid, front))
        val seen = doc.visibleItems(Rect(0.0, 0.0, 200.0, 200.0))
        assertEquals(listOf(back, mid, front), seen)
    }

    @Test fun zOrderSurvivesAnOutOfOrderIndexWalk() {
        // Items far apart land in different cells, so the raw query order follows cell order,
        // not insertion order. The sort must still restore painter order.
        val doc = InfiniteDocument()
        val far = boxAt(5000.0, 5000.0)
        val near = boxAt(0.0, 0.0)
        doc.add(far)
        doc.add(near)
        val seen = doc.visibleItems(Rect(-100.0, -100.0, 10000.0, 10000.0))
        assertEquals(listOf(far, near), seen)
    }

    @Test fun visibleItemsCullsWhatIsOffScreen() {
        val doc = InfiniteDocument()
        val onScreen = boxAt(0.0, 0.0)
        doc.add(onScreen)
        doc.add(boxAt(9000.0, 9000.0))
        assertSame(onScreen, doc.visibleItems(Rect(-50.0, -50.0, 200.0, 200.0)).single())
    }

    @Test fun aMultiCellItemAppearsOnceInTheVisibleList() {
        val doc = InfiniteDocument()
        doc.add(boxAt(0.0, 0.0, w = 4000.0, h = 4000.0))
        assertEquals(1, doc.visibleItems(Rect(0.0, 0.0, 4000.0, 4000.0)).size)
    }

    @Test fun insertingAtASlotPutsTheItemAtThatDepth() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0, 100.0, 100.0)
        val c = boxAt(0.0, 0.0, 100.0, 100.0)
        doc.addAll(listOf(a, c))
        val b = boxAt(0.0, 0.0, 100.0, 100.0)
        doc.add(1, b)
        assertEquals(listOf(a, b, c), doc.visibleItems(Rect(0.0, 0.0, 100.0, 100.0)))
    }

    @Test fun insertIndexIsClampedRatherThanThrowing() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(99, a)
        assertEquals(0, doc.indexOfRef(a))
    }

    @Test fun itemChangedRefilesTheIndex() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        a.translate(6000.0, 6000.0)
        doc.itemChanged(a)
        assertTrue(doc.visibleItems(Rect(-50.0, -50.0, 200.0, 200.0)).isEmpty())
        assertSame(a, doc.visibleItems(Rect(5990.0, 5990.0, 50.0, 50.0)).single())
    }

    @Test fun replaceAllRebuildsTheIndexAndTheOrder() {
        val doc = InfiniteDocument()
        val old = boxAt(0.0, 0.0)
        doc.add(old)
        val a = boxAt(100.0, 0.0, 100.0, 100.0)
        val b = boxAt(110.0, 0.0, 100.0, 100.0)
        doc.replaceAll(listOf(b, a))
        assertEquals(2, doc.index.size)
        assertTrue(doc.visibleItems(Rect(-50.0, -50.0, 60.0, 60.0)).isEmpty())
        assertEquals(listOf(b, a), doc.visibleItems(Rect(0.0, 0.0, 400.0, 400.0)))
    }

    @Test fun contentBoundsUnionsEverything() {
        val doc = InfiniteDocument()
        doc.add(boxAt(-100.0, -50.0, 10.0, 10.0))
        doc.add(boxAt(200.0, 300.0, 20.0, 20.0))
        val b = doc.contentBounds()!!
        assertEquals(-100.0, b.left, 1e-9)
        assertEquals(-50.0, b.top, 1e-9)
        assertEquals(220.0, b.right, 1e-9)
        assertEquals(320.0, b.bottom, 1e-9)
    }

    @Test fun contentBoundsTracksEditsRatherThanGoingStale() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        assertEquals(10.0, doc.contentBounds()!!.right, 1e-9)
        a.translate(500.0, 0.0)
        doc.itemChanged(a)
        assertEquals(510.0, doc.contentBounds()!!.right, 1e-9)
        doc.remove(a)
        assertNull(doc.contentBounds())
    }

    @Test fun contentBoundsCoversStrokeGlowNotJustInk() {
        val doc = InfiniteDocument()
        val plain = strokeAt(0.0, 0.0)
        doc.add(plain)
        val inkOnly = doc.contentBounds()!!
        doc.remove(plain)
        val glowing = strokeAt(0.0, 0.0)
        glowing.config = glowing.config.copy(neon = true)
        doc.add(glowing)
        val withGlow = doc.contentBounds()!!
        assertTrue("the neon halo must widen the extent", withGlow.w > inkOnly.w)
    }

    @Test fun listenerHearsEveryStructuralChange() {
        val doc = InfiniteDocument()
        val rec = Recorder()
        doc.listener = rec
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        doc.itemChanged(a)
        doc.remove(a)
        doc.replaceAll(listOf(boxAt(1.0, 1.0)))
        assertEquals(listOf("add", "change", "remove", "reset"), rec.log)
    }

    @Test fun addAllFiresOncePerItem() {
        val doc = InfiniteDocument()
        val rec = Recorder()
        doc.listener = rec
        doc.addAll(listOf(boxAt(0.0, 0.0), boxAt(1.0, 1.0), boxAt(2.0, 2.0)))
        assertEquals(listOf("add", "add", "add"), rec.log)
    }

    @Test fun containsRefAndIndexOfRefUseIdentityNotEquality() {
        val doc = InfiniteDocument()
        val a = boxAt(5.0, 5.0)
        val lookalike = boxAt(5.0, 5.0)
        doc.add(a)
        assertTrue(doc.containsRef(a))
        assertTrue(!doc.containsRef(lookalike))
        assertEquals(0, doc.indexOfRef(a))
        assertEquals(-1, doc.indexOfRef(lookalike))
    }

    @Test fun titleFallsBackThroughDisplayNameThenPath() {
        assertEquals("Untitled", InfiniteDocument().title)
        assertEquals("plan", InfiniteDocument(path = "/x/plan.xcanvas").title)
        assertEquals("real", InfiniteDocument(path = "/x/plan.xcanvas", displayName = "real.xcanvas").title)
    }

    @Test fun theCanvasHandlesFarFlungContent() {
        val doc = InfiniteDocument()
        val a = boxAt(-1_000_000.0, 2_500_000.0)
        doc.add(a)
        assertSame(a, doc.visibleItems(Rect(-1_000_010.0, 2_499_990.0, 40.0, 40.0)).single())
        assertEquals(-1_000_000.0, doc.contentBounds()!!.left, 1e-6)
    }
}
