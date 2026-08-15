package com.xnotes.core.history

import com.xnotes.core.model.ImageData
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.RectHandle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private fun strokeAt(x: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, 0.0, 1.0)))

    @Test fun addItemUndoRedo() {
        val page = Page(100.0, 100.0)
        val s = strokeAt(1.0)
        page.items.add(s)
        val cmd = AddItem(page, s)
        cmd.undo()
        assertTrue(page.items.isEmpty())
        cmd.redo()
        assertEquals(1, page.items.size)
        cmd.redo() // idempotent
        assertEquals(1, page.items.size)
    }

    @Test fun eraseItemsUndoRestores() {
        val page = Page(100.0, 100.0)
        val a = strokeAt(1.0)
        val b = strokeAt(2.0)
        page.items.addAll(listOf(a, b))
        val cmd = EraseItems(listOf(page to a, page to b))
        cmd.redo()
        assertTrue(page.items.isEmpty())
        cmd.undo()
        assertEquals(2, page.items.size)
    }

    @Test fun moveItemsRoundTrip() {
        val img = ImageItem(ImageData(java.io.File("test-image"),10, 10), Rect(0.0, 0.0, 10.0, 10.0))
        val cmd = MoveItems(listOf(img), 5.0, 7.0)
        // simulate: the gesture already moved the item, then we push the command
        img.translate(5.0, 7.0)
        cmd.undo()
        assertEquals(Rect(0.0, 0.0, 10.0, 10.0), img.rect)
        cmd.redo()
        assertEquals(Rect(5.0, 7.0, 10.0, 10.0), img.rect)
    }

    @Test fun transferItemsMovesBetweenPagesAndBack() {
        val from = Page(100.0, 100.0)
        val to = Page(100.0, 100.0)
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 90.0, 10.0, 10.0))
        from.items.add(img)
        val cmd = TransferItems(listOf(TransferItems.Transfer(from, to, img, 0.0, -120.0)))

        cmd.redo()
        assertTrue(from.items.isEmpty())
        assertSame(img, to.items.single())
        assertEquals(Rect(0.0, -30.0, 10.0, 10.0), img.rect)
        cmd.redo() // idempotent
        assertEquals(1, to.items.size)
        assertEquals(Rect(0.0, -30.0, 10.0, 10.0), img.rect)

        cmd.undo()
        assertTrue(to.items.isEmpty())
        assertSame(img, from.items.single())
        assertEquals(Rect(0.0, 90.0, 10.0, 10.0), img.rect)
        cmd.undo() // idempotent
        assertEquals(1, from.items.size)
        assertEquals(Rect(0.0, 90.0, 10.0, 10.0), img.rect)
    }

    @Test fun resizeItemRoundTrip() {
        val img = ImageItem(ImageData(java.io.File("test-image"),10, 10), Rect(20.0, 20.0, 40.0, 40.0))
        val cmd = ResizeItem(img, RectHandle(Rect(0.0, 0.0, 10.0, 10.0)), RectHandle(Rect(20.0, 20.0, 40.0, 40.0)))
        cmd.undo()
        assertEquals(Rect(0.0, 0.0, 10.0, 10.0), img.rect)
        cmd.redo()
        assertEquals(Rect(20.0, 20.0, 40.0, 40.0), img.rect)
    }

    @Test fun reorderItemsSnapshots() {
        val page = Page(100.0, 100.0)
        val a = strokeAt(1.0)
        val b = strokeAt(2.0)
        val c = strokeAt(3.0)
        page.items.addAll(listOf(a, b, c))
        val old = page.items.toList()
        val new = listOf(b, c, a) // 'a' brought to front
        val cmd = ReorderItems(page, old, new)
        cmd.redo()
        assertSame(a, page.items.last())
        cmd.undo()
        assertSame(a, page.items.first())
    }

    @Test fun addAndDeletePageCommands() {
        val doc = Document.blank(count = 2)
        val newPage = Page(100.0, 100.0)
        doc.pages.add(1, newPage)
        val add = AddPage(doc, newPage, 1)
        add.undo()
        assertEquals(2, doc.pages.size)
        add.redo()
        assertEquals(3, doc.pages.size)
        assertSame(newPage, doc.pages[1])

        val del = DeletePage(doc, newPage, 1)
        del.redo()
        assertEquals(2, doc.pages.size)
        del.undo()
        assertSame(newPage, doc.pages[1])
    }

    @Test fun historyStackSemantics() {
        val page = Page(100.0, 100.0)
        val h = History()
        assertFalse(h.canUndo)

        val s = strokeAt(1.0)
        page.items.add(s)
        h.push(AddItem(page, s))
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)

        h.undo()
        assertTrue(page.items.isEmpty())
        assertTrue(h.canRedo)

        h.redo()
        assertEquals(1, page.items.size)

        // a new edit clears the redo branch
        h.undo()
        assertTrue(h.canRedo)
        val s2 = strokeAt(2.0)
        page.items.add(s2)
        h.push(AddItem(page, s2))
        assertFalse(h.canRedo)

        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }
}
