package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The damage rectangle decides what a front-buffered present is allowed to clear, so every case
 * here is really the same question: can this box leave a pixel of the last tail behind?
 */
class WetDamageTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = PixelRect(l, t, r, b)

    private fun assertRect(l: Int, t: Int, r: Int, b: Int, actual: PixelRect) {
        assertEquals("left", l, actual.left)
        assertEquals("top", t, actual.top)
        assertEquals("right", r, actual.right)
        assertEquals("bottom", b, actual.bottom)
    }

    @Test
    fun `a box rounds outward on every side`() {
        val into = PixelRect()
        assertTrue(WetDamage.map(Rect(10.2, 20.7, 5.4, 3.1), 0.0, 0.0, 1.0, 0.0, into))
        assertRect(10, 20, 16, 24, into)
    }

    @Test
    fun `the outset is added before the rounding, not after`() {
        val into = PixelRect()
        WetDamage.map(Rect(10.0, 10.0, 4.0, 4.0), 0.0, 0.0, 1.0, 0.5, into)
        assertRect(9, 9, 15, 15, into)
    }

    @Test
    fun `scroll and zoom land the box where the shader would put it`() {
        val into = PixelRect()
        WetDamage.map(Rect(100.0, 200.0, 10.0, 10.0), 50.0, 100.0, 2.0, 0.0, into)
        assertRect(100, 200, 120, 220, into)
    }

    @Test
    fun `a box that is not a number damages nothing`() {
        val into = PixelRect()
        assertFalse(WetDamage.map(Rect(Double.NaN, 0.0, 1.0, 1.0), 0.0, 0.0, 1.0, 0.0, into))
        assertFalse(WetDamage.map(Rect(0.0, 0.0, Double.NaN, 1.0), 0.0, 0.0, 1.0, 0.0, into))
    }

    @Test
    fun `a degenerate box is empty rather than inverted`() {
        val into = PixelRect()
        assertFalse(WetDamage.map(Rect(10.0, 10.0, 0.0, 0.0), 0.0, 0.0, 1.0, 0.0, into))
        assertTrue(into.isEmpty)
    }

    @Test
    fun `a union keeps every side it was given`() {
        val box = rect(10, 10, 20, 20)
        box.union(rect(5, 15, 15, 30))
        assertRect(5, 10, 20, 30, box)
    }

    @Test
    fun `an empty rectangle contributes nothing to a union, in either order`() {
        val box = rect(10, 10, 20, 20)
        box.union(PixelRect())
        assertRect(10, 10, 20, 20, box)

        val empty = PixelRect()
        empty.union(rect(10, 10, 20, 20))
        assertRect(10, 10, 20, 20, empty)
    }

    @Test
    fun `clamping cuts a box down to the surface and can empty it`() {
        val box = rect(-5, -5, 50, 50)
        box.clampTo(40, 30)
        assertRect(0, 0, 40, 30, box)

        val off = rect(60, 60, 80, 80)
        off.clampTo(40, 30)
        assertTrue(off.isEmpty)
    }

    @Test
    fun `boxes that touch only along an edge do not intersect`() {
        assertTrue(rect(0, 0, 10, 10).intersects(rect(9, 9, 20, 20)))
        assertFalse(rect(0, 0, 10, 10).intersects(rect(10, 0, 20, 10)))
        assertFalse(rect(0, 0, 10, 10).intersects(rect(0, 10, 10, 20)))
    }

    @Test
    fun `an empty rectangle meets nothing, in either order`() {
        val empty = rect(5, 5, 5, 5)
        val box = rect(0, 0, 10, 10)
        assertFalse(box.intersects(empty))
        assertFalse(empty.intersects(box))
    }
}
