package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpatialIndexTest {

    /** An image is the simplest item with a settable AABB, so it stands in for anything. */
    private fun boxAt(x: Double, y: Double, w: Double = 10.0, h: Double = 10.0): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, w, h))

    private fun ids(items: List<*>) = items.map { System.identityHashCode(it) }.toSet()

    @Test fun emptyIndexFindsNothing() {
        val ix = SpatialIndex()
        assertTrue(ix.isEmpty())
        assertEquals(0, ix.query(Rect(0.0, 0.0, 1000.0, 1000.0)).size)
    }

    @Test fun insertThenQueryHits() {
        val ix = SpatialIndex()
        val a = boxAt(5.0, 5.0)
        ix.insert(a)
        assertEquals(1, ix.size)
        assertSame(a, ix.query(Rect(0.0, 0.0, 20.0, 20.0)).single())
    }

    @Test fun queryMissesItemsOutsideTheRect() {
        val ix = SpatialIndex()
        ix.insert(boxAt(5.0, 5.0))
        assertTrue(ix.query(Rect(1000.0, 1000.0, 50.0, 50.0)).isEmpty())
    }

    @Test fun queryFindsItemsFarApartInTheirOwnCells() {
        val ix = SpatialIndex(cellSize = 100.0)
        val near = boxAt(10.0, 10.0)
        val far = boxAt(5000.0, 5000.0)
        ix.insert(near)
        ix.insert(far)
        assertSame(near, ix.query(Rect(0.0, 0.0, 50.0, 50.0)).single())
        assertSame(far, ix.query(Rect(4990.0, 4990.0, 50.0, 50.0)).single())
    }

    @Test fun negativeCoordinatesIndexAndQuery() {
        val ix = SpatialIndex(cellSize = 100.0)
        val a = boxAt(-5000.0, -5000.0)
        ix.insert(a)
        assertSame(a, ix.query(Rect(-5010.0, -5010.0, 40.0, 40.0)).single())
        assertTrue(ix.query(Rect(0.0, 0.0, 100.0, 100.0)).isEmpty())
    }

    @Test fun itemSpanningManyCellsIsFoundFromEachOfThem() {
        val ix = SpatialIndex(cellSize = 100.0)
        val wide = boxAt(0.0, 0.0, w = 450.0, h = 20.0)
        ix.insert(wide)
        for (x in intArrayOf(10, 150, 250, 350, 440)) {
            assertSame(wide, ix.query(Rect(x.toDouble(), 0.0, 5.0, 5.0)).single())
        }
    }

    @Test fun queryDeduplicatesAMultiCellItem() {
        val ix = SpatialIndex(cellSize = 100.0)
        val wide = boxAt(0.0, 0.0, w = 450.0, h = 450.0)
        ix.insert(wide)
        assertEquals(1, ix.query(Rect(0.0, 0.0, 500.0, 500.0)).size)
    }

    @Test fun queryRawMayRepeatAMultiCellItem() {
        val ix = SpatialIndex(cellSize = 100.0)
        ix.insert(boxAt(0.0, 0.0, w = 450.0, h = 450.0))
        val raw = ArrayList<com.xnotes.core.model.CanvasItem>()
        ix.queryRaw(Rect(0.0, 0.0, 500.0, 500.0), raw)
        assertTrue("raw hits are per-cell, so they repeat", raw.size > 1)
    }

    @Test fun removeTakesTheItemOutOfEveryCell() {
        val ix = SpatialIndex(cellSize = 100.0)
        val wide = boxAt(0.0, 0.0, w = 450.0, h = 20.0)
        ix.insert(wide)
        ix.remove(wide)
        assertEquals(0, ix.size)
        assertTrue(ix.query(Rect(-100.0, -100.0, 1000.0, 1000.0)).isEmpty())
    }

    @Test fun removingAnUnknownItemIsHarmless() {
        val ix = SpatialIndex()
        ix.insert(boxAt(0.0, 0.0))
        ix.remove(boxAt(999.0, 999.0))
        assertEquals(1, ix.size)
    }

    @Test fun insertIsIdempotent() {
        val ix = SpatialIndex()
        val a = boxAt(0.0, 0.0)
        ix.insert(a)
        ix.insert(a)
        assertEquals(1, ix.size)
        assertEquals(1, ix.query(Rect(-10.0, -10.0, 100.0, 100.0)).size)
    }

    @Test fun updateRefilesAMovedItem() {
        val ix = SpatialIndex(cellSize = 100.0)
        val a = boxAt(10.0, 10.0)
        ix.insert(a)
        a.translate(5000.0, 5000.0)
        ix.update(a)
        assertTrue(ix.query(Rect(0.0, 0.0, 100.0, 100.0)).isEmpty())
        assertSame(a, ix.query(Rect(5000.0, 5000.0, 40.0, 40.0)).single())
        assertEquals(1, ix.size)
    }

    @Test fun updateWithinTheSameCellKeepsTheItemFindable() {
        val ix = SpatialIndex(cellSize = 100.0)
        val a = boxAt(10.0, 10.0)
        ix.insert(a)
        a.translate(1.0, 1.0)
        ix.update(a)
        assertSame(a, ix.query(Rect(0.0, 0.0, 100.0, 100.0)).single())
    }

    @Test fun oversizedItemsStayQueryableAndRemovable() {
        val ix = SpatialIndex(cellSize = 1.0) // every item is oversized at this cell size
        val a = boxAt(0.0, 0.0, w = 1000.0, h = 1000.0)
        ix.insert(a)
        assertEquals(1, ix.size)
        assertSame(a, ix.query(Rect(500.0, 500.0, 1.0, 1.0)).single())
        assertTrue(ix.query(Rect(2000.0, 2000.0, 1.0, 1.0)).isEmpty())
        ix.remove(a)
        assertEquals(0, ix.size)
    }

    @Test fun clearEmptiesEverything() {
        val ix = SpatialIndex(cellSize = 100.0)
        ix.insert(boxAt(0.0, 0.0))
        ix.insert(boxAt(0.0, 0.0, w = 1e6, h = 1e6))
        ix.clear()
        assertTrue(ix.isEmpty())
        assertTrue(ix.query(Rect(-1e6, -1e6, 2e6, 2e6)).isEmpty())
    }

    @Test fun aRectEndingOnACellBoundaryDoesNotLeakIntoTheNextCell() {
        val ix = SpatialIndex(cellSize = 100.0)
        val a = boxAt(0.0, 0.0, w = 100.0, h = 100.0)
        ix.insert(a)
        // Touching the shared edge still counts as an intersection (Rect.intersects is inclusive),
        // but a query well past it must miss.
        assertTrue(ix.query(Rect(150.0, 150.0, 10.0, 10.0)).isEmpty())
        assertEquals(1, ix.query(Rect(50.0, 50.0, 10.0, 10.0)).size)
    }

    @Test fun manyItemsAllComeBack() {
        val ix = SpatialIndex(cellSize = 128.0)
        val made = (0 until 500).map { boxAt(it * 37.0, it * 11.0) }
        made.forEach { ix.insert(it) }
        assertEquals(500, ix.size)
        val all = ix.query(Rect(-1e5, -1e5, 2e5, 2e5))
        assertEquals(500, all.size)
        assertEquals(ids(made), ids(all))
    }
}
