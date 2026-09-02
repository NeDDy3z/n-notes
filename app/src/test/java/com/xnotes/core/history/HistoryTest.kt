package com.xnotes.core.history

import com.xnotes.core.model.ImageData
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
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
import org.junit.Assert.assertNull
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

    // --- touched(): the regions an undo has to repaint (see CanvasState.repairInkRegions) ---

    /** A locator for commands that hold items but not pages; [HistoryTest] only ever has one page. */
    private fun onPage(page: Page): (CanvasItem) -> Page? = { page }

    @Test fun addItemTouchesItsOwnPage() {
        val page = Page(100.0, 100.0)
        val s = strokeAt(1.0)
        page.items.add(s)
        val cmd = AddItem(page, s)

        assertEquals(listOf(page to s), cmd.touched(onPage(page)))
        cmd.undo() // still nameable once the item is off the page: that is where it has to be rubbed out
        assertEquals(listOf(page to s), cmd.touched(onPage(page)))
    }

    @Test fun replacePageItemsTouchesOnlyTheDifference() {
        val page = Page(100.0, 100.0)
        val kept = strokeAt(1.0)
        val gone = strokeAt(2.0)
        val added = strokeAt(3.0)
        val cmd = ReplacePageItems(page, listOf(kept, gone), listOf(kept, added))

        val touched = cmd.touched(onPage(page))!!.map { it.second }
        assertEquals(listOf(gone, added), touched) // the untouched majority of the page is not repainted
    }

    @Test fun moveItemsTouchesThePageItSitsOn() {
        val page = Page(100.0, 100.0)
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))
        page.items.add(img)
        assertEquals(listOf(page to img), MoveItems(listOf(img), 5.0, 7.0).touched(onPage(page)))
    }

    @Test fun transferItemsTouchesBothPages() {
        val from = Page(100.0, 100.0)
        val to = Page(100.0, 100.0)
        val img = ImageItem(ImageData(java.io.File("test-image"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))
        val cmd = TransferItems(listOf(TransferItems.Transfer(from, to, img, 0.0, -120.0)))
        assertEquals(listOf(from to img, to to img), cmd.touched(onPage(from)))
    }

    @Test fun pageCommandsCannotSay() {
        // A page insert reflows the text flow across pages, so there is no small region to repair.
        val doc = Document.blank(count = 2)
        assertNull(AddPage(doc, Page(100.0, 100.0), 1).touched { null })
        assertNull(DeletePage(doc, doc.pages[0], 0).touched { null })
    }

    @Test fun compositeGivesUpWhenOneStepDoes() {
        val doc = Document.blank(count = 1)
        val page = doc.pages[0]
        val s = strokeAt(1.0)
        page.items.add(s)
        val known = AddItem(page, s)

        assertEquals(listOf(page to s), CompositeCommand(listOf(known)).touched(onPage(page)))
        assertNull(CompositeCommand(listOf(known, AddPage(doc, Page(100.0, 100.0), 1))).touched(onPage(page)))
    }

    @Test fun theStackDropsTheOldestEditPastItsLimit() {
        val page = Page(100.0, 100.0)
        val h = History(limit = 3)
        val added = (0 until 5).map { strokeAt(it.toDouble()) }
        for (s in added) {
            page.items.add(s)
            h.push(AddItem(page, s))
        }

        // Only the last three are reachable; undoing them all leaves the first two in place.
        repeat(3) { h.undo() }
        assertFalse(h.canUndo)
        assertEquals(listOf(added[0], added[1]), page.items)
    }

    @Test fun peeksReportTheNextCommandWithoutApplyingIt() {
        val page = Page(100.0, 100.0)
        val h = History()
        val s = strokeAt(1.0)
        page.items.add(s)
        val cmd = AddItem(page, s)
        h.push(cmd)

        assertSame(cmd, h.nextUndo)
        assertEquals(1, page.items.size) // peeking must not apply anything
        assertNull(h.nextRedo)

        h.undo()
        assertNull(h.nextUndo)
        assertSame(cmd, h.nextRedo)
    }
}
