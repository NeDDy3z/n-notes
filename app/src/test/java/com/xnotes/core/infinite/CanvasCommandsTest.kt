package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.history.MoveItems
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanvasCommandsTest {

    private fun boxAt(x: Double, y: Double): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, 10.0, 10.0))

    @Test fun addUndoRedoIsIdempotent() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        val cmd = AddCanvasItem(doc, a)
        cmd.undo()
        assertTrue(doc.isEmpty)
        assertEquals(0, doc.index.size)
        cmd.redo()
        cmd.redo()
        assertEquals(1, doc.itemCount)
        assertEquals(1, doc.index.size)
    }

    @Test fun addKeepsTheIndexInStepAcrossACycle() {
        val doc = InfiniteDocument()
        val a = boxAt(400.0, 400.0)
        doc.add(a)
        val cmd = AddCanvasItem(doc, a)
        cmd.undo()
        assertTrue(doc.visibleItems(Rect(390.0, 390.0, 40.0, 40.0)).isEmpty())
        cmd.redo()
        assertSame(a, doc.visibleItems(Rect(390.0, 390.0, 40.0, 40.0)).single())
    }

    @Test fun addItemsUndoRemovesAllOfThem() {
        val doc = InfiniteDocument()
        val items = listOf(boxAt(0.0, 0.0), boxAt(20.0, 0.0), boxAt(40.0, 0.0))
        doc.addAll(items)
        val cmd = AddCanvasItems(doc, items)
        cmd.undo()
        assertTrue(doc.isEmpty)
        cmd.redo()
        assertEquals(3, doc.itemCount)
    }

    @Test fun eraseUndoRestoresExactZOrder() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        val b = boxAt(10.0, 0.0)
        val c = boxAt(20.0, 0.0)
        val d = boxAt(30.0, 0.0)
        doc.addAll(listOf(a, b, c, d))
        val cmd = EraseCanvasItems.capture(doc, listOf(b, c))
        doc.removeAll(listOf(b, c))
        assertEquals(listOf(a, d), doc.items)
        cmd.undo()
        assertEquals(listOf(a, b, c, d), doc.items)
    }

    @Test fun eraseUndoRestoresAMiddleItemBetweenItsNeighbours() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        val b = boxAt(10.0, 0.0)
        val c = boxAt(20.0, 0.0)
        doc.addAll(listOf(a, b, c))
        val cmd = EraseCanvasItems.capture(doc, listOf(b))
        cmd.redo()
        assertEquals(listOf(a, c), doc.items)
        cmd.undo()
        assertEquals(listOf(a, b, c), doc.items)
        assertEquals(3, doc.index.size)
    }

    @Test fun eraseIgnoresItemsThatWereNeverInTheDocument() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        val cmd = EraseCanvasItems.capture(doc, listOf(a, boxAt(99.0, 99.0)))
        cmd.redo()
        assertTrue(doc.isEmpty)
        cmd.undo()
        assertEquals(listOf(a), doc.items)
    }

    @Test fun replaceSwapsTheWholeListBothWays() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        val b = boxAt(10.0, 0.0)
        doc.addAll(listOf(a, b))
        val before = doc.items.toList()
        val after = listOf(b, a)
        doc.replaceAll(after)
        val cmd = ReplaceCanvasItems(doc, before, after)
        cmd.undo()
        assertEquals(before, doc.items)
        cmd.redo()
        assertEquals(after, doc.items)
        assertEquals(2, doc.index.size)
    }

    @Test fun replaceKeepsTheIndexUsable() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        val b = boxAt(3000.0, 3000.0)
        val cmd = ReplaceCanvasItems(doc, listOf(a), listOf(b))
        cmd.redo()
        assertTrue(doc.visibleItems(Rect(-50.0, -50.0, 200.0, 200.0)).isEmpty())
        assertSame(b, doc.visibleItems(Rect(2990.0, 2990.0, 40.0, 40.0)).single())
        cmd.undo()
        assertSame(a, doc.visibleItems(Rect(-50.0, -50.0, 200.0, 200.0)).single())
    }

    @Test fun onCanvasReindexesAfterAPagedMoveCommand() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        val move = MoveItems(listOf(a), 8000.0, 0.0)
        val cmd = OnCanvas(doc, move, listOf(a))
        cmd.redo()
        assertTrue(doc.visibleItems(Rect(-50.0, -50.0, 200.0, 200.0)).isEmpty())
        assertSame(a, doc.visibleItems(Rect(7990.0, -10.0, 40.0, 40.0)).single())
        cmd.undo()
        assertSame(a, doc.visibleItems(Rect(-50.0, -50.0, 200.0, 200.0)).single())
    }

    @Test fun onCanvasKeepsContentBoundsFresh() {
        val doc = InfiniteDocument()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        val cmd = OnCanvas(doc, MoveItems(listOf(a), 100.0, 0.0), listOf(a))
        cmd.redo()
        assertEquals(110.0, doc.contentBounds()!!.right, 1e-9)
        cmd.undo()
        assertEquals(10.0, doc.contentBounds()!!.right, 1e-9)
    }

    @Test fun historyDrivesTheCanvasEndToEnd() {
        val doc = InfiniteDocument()
        val history = History()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        history.push(AddCanvasItem(doc, a))
        val b = boxAt(50.0, 50.0)
        doc.add(b)
        history.push(AddCanvasItem(doc, b))

        history.undo()
        assertEquals(listOf(a), doc.items)
        history.undo()
        assertTrue(doc.isEmpty)
        assertTrue(!history.canUndo)

        history.redo()
        history.redo()
        assertEquals(listOf(a, b), doc.items)
        assertEquals(2, doc.index.size)
    }

    @Test fun aNewEditAfterUndoDropsTheRedoBranch() {
        val doc = InfiniteDocument()
        val history = History()
        val a = boxAt(0.0, 0.0)
        doc.add(a)
        history.push(AddCanvasItem(doc, a))
        history.undo()
        val b = boxAt(9.0, 9.0)
        doc.add(b)
        history.push(AddCanvasItem(doc, b))
        assertTrue(!history.canRedo)
        assertEquals(listOf(b), doc.items)
    }
}
