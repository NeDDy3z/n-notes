package com.xnotes.core.model

import com.xnotes.canvas.SelectionMath
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.LockItems
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A locked item is out of reach of every way of selecting one, and stays locked across a copy. */
class LockedItemTest {

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private val pages = listOf(Page(100.0, 100.0))
    private val rects = listOf(Rect(0.0, 0.0, 100.0, 100.0))

    @Test
    fun `items start unlocked`() {
        assertFalse(dot(1.0, 1.0).locked)
    }

    @Test
    fun `a band skips a locked item`() {
        val free = dot(10.0, 10.0)
        val pinned = dot(20.0, 20.0).apply { locked = true }
        pages[0].items.addAll(listOf(free, pinned))
        val members = SelectionMath.bandMembers(pages, rects, Rect(0.0, 0.0, 90.0, 90.0))
        assertEquals(1, members.size)
        assertSame(free, members[0].item)
    }

    @Test
    fun `a lasso skips a locked item`() {
        val free = dot(10.0, 10.0)
        val pinned = dot(20.0, 20.0).apply { locked = true }
        pages[0].items.addAll(listOf(free, pinned))
        val poly = listOf(Pt(0.0, 0.0), Pt(90.0, 0.0), Pt(90.0, 90.0), Pt(0.0, 90.0))
        val members = SelectionMath.lassoMembers(pages, rects, poly)
        assertEquals(1, members.size)
        assertSame(free, members[0].item)
    }

    @Test
    fun `the flat-list band and lasso skip one too`() {
        val free = dot(10.0, 10.0)
        val pinned = dot(20.0, 20.0).apply { locked = true }
        val items = listOf(free, pinned)
        val box = Rect(0.0, 0.0, 90.0, 90.0)
        val poly = listOf(Pt(0.0, 0.0), Pt(90.0, 0.0), Pt(90.0, 90.0), Pt(0.0, 90.0))
        assertEquals(listOf(free), SelectionMath.bandMembers(items, box))
        assertEquals(listOf(free), SelectionMath.lassoMembers(items, poly))
    }

    @Test
    fun `a copy stays locked, so an autosave snapshot keeps it`() {
        val pinned = dot(1.0, 1.0).apply { locked = true }
        assertTrue(pinned.deepCopy(FakeTextMeasurer()).locked)
        assertFalse(dot(1.0, 1.0).deepCopy(FakeTextMeasurer()).locked)
    }

    @Test
    fun `the command locks and unlocks the whole set at once`() {
        val a = dot(1.0, 1.0)
        val b = dot(2.0, 2.0)
        val lock = LockItems(listOf(a, b), true)
        lock.redo()
        assertTrue(a.locked && b.locked)
        lock.undo()
        assertFalse(a.locked || b.locked)
    }
}
