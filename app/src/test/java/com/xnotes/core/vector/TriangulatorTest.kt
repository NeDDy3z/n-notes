package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.FillRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TriangulatorTest {

    private fun square(x: Double, y: Double, s: Double) = listOf(
        Pt(x, y), Pt(x + s, y), Pt(x + s, y + s), Pt(x, y + s),
    )

    private fun area(mesh: Triangulator.Mesh): Double {
        var sum = 0.0
        var i = 0
        while (i < mesh.indices.size) {
            val a = mesh.points[mesh.indices[i]]
            val b = mesh.points[mesh.indices[i + 1]]
            val c = mesh.points[mesh.indices[i + 2]]
            sum += abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2.0
            i += 3
        }
        return sum
    }

    @Test
    fun `a square is two triangles`() {
        val mesh = Triangulator.triangulate(listOf(square(0.0, 0.0, 10.0)), FillRule.NONZERO)
        assertNotNull(mesh)
        assertEquals(6, mesh!!.indices.size)
        assertEquals(100.0, area(mesh), 1e-9)
    }

    @Test
    fun `winding does not change the result`() {
        val forward = Triangulator.triangulate(listOf(square(0.0, 0.0, 10.0)), FillRule.NONZERO)!!
        val backward = Triangulator.triangulate(
            listOf(square(0.0, 0.0, 10.0).asReversed()),
            FillRule.NONZERO,
        )!!
        assertEquals(area(forward), area(backward), 1e-9)
    }

    @Test
    fun `a hole is cut out of its ring`() {
        val rings = listOf(square(0.0, 0.0, 10.0), square(3.0, 3.0, 4.0).asReversed())
        val mesh = Triangulator.triangulate(rings, FillRule.NONZERO)
        assertNotNull(mesh)
        assertEquals(100.0 - 16.0, area(mesh!!), 1e-6)
    }

    @Test
    fun `even-odd cuts a hole whichever way the inner ring runs`() {
        val rings = listOf(square(0.0, 0.0, 10.0), square(3.0, 3.0, 4.0))
        val mesh = Triangulator.triangulate(rings, FillRule.EVEN_ODD)
        assertNotNull(mesh)
        assertEquals(100.0 - 16.0, area(mesh!!), 1e-6)
    }

    @Test
    fun `non-zero keeps a same-way inner ring solid`() {
        val rings = listOf(square(0.0, 0.0, 10.0), square(3.0, 3.0, 4.0))
        val mesh = Triangulator.triangulate(rings, FillRule.NONZERO)
        assertNotNull(mesh)
        // Both rings fill, and the inner one is covered twice; area is the sum, never 84.
        assertTrue(area(mesh!!) > 100.0 - 1e-6)
    }

    @Test
    fun `two holes in one ring`() {
        val rings = listOf(
            square(0.0, 0.0, 20.0),
            square(2.0, 2.0, 3.0).asReversed(),
            square(12.0, 12.0, 4.0).asReversed(),
        )
        val mesh = Triangulator.triangulate(rings, FillRule.NONZERO)
        assertNotNull(mesh)
        assertEquals(400.0 - 9.0 - 16.0, area(mesh!!), 1e-6)
    }

    @Test
    fun `an island inside a hole fills again`() {
        val rings = listOf(
            square(0.0, 0.0, 20.0),
            square(4.0, 4.0, 12.0).asReversed(),
            square(7.0, 7.0, 6.0),
        )
        val mesh = Triangulator.triangulate(rings, FillRule.EVEN_ODD)
        assertNotNull(mesh)
        assertEquals(400.0 - 144.0 + 36.0, area(mesh!!), 1e-6)
    }

    @Test
    fun `a concave outline still tiles its own area`() {
        val ring = listOf(
            Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(6.0, 10.0),
            Pt(6.0, 4.0), Pt(4.0, 4.0), Pt(4.0, 10.0), Pt(0.0, 10.0),
        )
        val mesh = Triangulator.triangulate(listOf(ring), FillRule.NONZERO)
        assertNotNull(mesh)
        assertEquals(100.0 - 12.0, area(mesh!!), 1e-9)
    }

    @Test
    fun `degenerate input is refused rather than guessed at`() {
        assertNull(Triangulator.triangulate(listOf(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))), FillRule.NONZERO))
        assertNull(Triangulator.triangulate(emptyList(), FillRule.NONZERO))
    }

    @Test
    fun `a ring past the vertex cap is handed to the stencil instead`() {
        val n = Triangulator.MAX_RING_VERTICES + 10
        val ring = (0 until n).map {
            val a = 2.0 * Math.PI * it / n
            Pt(100.0 * kotlin.math.cos(a), 100.0 * kotlin.math.sin(a))
        }
        assertNull(Triangulator.triangulate(listOf(ring), FillRule.NONZERO))
    }

    @Test
    fun `a circle triangulates to about its own area`() {
        val n = 128
        val ring = (0 until n).map {
            val a = 2.0 * Math.PI * it / n
            Pt(50.0 * kotlin.math.cos(a), 50.0 * kotlin.math.sin(a))
        }
        val mesh = Triangulator.triangulate(listOf(ring), FillRule.NONZERO)
        assertNotNull(mesh)
        assertEquals(Math.PI * 2500.0, area(mesh!!), 20.0)
    }
}
