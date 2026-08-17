package com.xnotes.core.vector

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.MeshPart
import com.xnotes.format.SvgReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VectorMesherTest {

    private val tolerance = 0.01

    private fun mesh(
        body: String,
        rect: Rect = Rect(0.0, 0.0, 100.0, 100.0),
        orientation: Int = 0,
        angle: Double = 0.0,
    ): List<MeshPart> {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">$body</svg>"""
        return VectorMesher.mesh(
            SvgReader.parse(svg.toByteArray()), rect, orientation, angle, tolerance,
        )
    }

    private fun bounds(parts: List<MeshPart>): Rect {
        var l = Double.MAX_VALUE
        var t = Double.MAX_VALUE
        var r = -Double.MAX_VALUE
        var b = -Double.MAX_VALUE
        for (part in parts) {
            val p = part.mesh.positions
            var i = 0
            while (i < p.size) {
                l = minOf(l, p[i])
                r = maxOf(r, p[i])
                t = minOf(t, p[i + 1])
                b = maxOf(b, p[i + 1])
                i += 2
            }
        }
        return Rect(l, t, r - l, b - t)
    }

    private fun area(parts: List<MeshPart>): Double {
        var sum = 0.0
        for (part in parts) {
            val p = part.mesh.positions
            val idx = part.mesh.indices
            var i = 0
            while (i < idx.size) {
                val ax = p[2 * idx[i]]
                val ay = p[2 * idx[i] + 1]
                val bx = p[2 * idx[i + 1]]
                val by = p[2 * idx[i + 1] + 1]
                val cx = p[2 * idx[i + 2]]
                val cy = p[2 * idx[i + 2] + 1]
                sum += abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2.0
                i += 3
            }
        }
        return sum
    }

    @Test
    fun `a filled rect lands in its placement box`() {
        val parts = mesh("""<rect x="0" y="0" width="100" height="100" fill="red"/>""", Rect(10.0, 20.0, 40.0, 60.0))
        assertEquals(1, parts.size)
        val b = bounds(parts)
        assertEquals(10.0, b.left, 1e-9)
        assertEquals(20.0, b.top, 1e-9)
        assertEquals(50.0, b.right, 1e-9)
        assertEquals(80.0, b.bottom, 1e-9)
        assertEquals(40.0 * 60.0, area(parts), 1e-6)
    }

    @Test
    fun `a fill batches into the opaque pass`() {
        assertEquals(InkPass.OPAQUE, mesh("""<rect width="50" height="50" fill="blue"/>""")[0].pass)
    }

    @Test
    fun `a translucent fill still batches, since the triangles do not overlap`() {
        val parts = mesh("""<rect width="50" height="50" fill="blue" fill-opacity="0.3"/>""")
        assertEquals(InkPass.OPAQUE, parts[0].pass)
        assertEquals(76.0, parts[0].color.a.toDouble(), 2.0)
    }

    @Test
    fun `a translucent stroke goes through the mask, since it overlaps itself`() {
        val parts = mesh("""<path d="M10 10 L90 10" stroke="black" stroke-opacity="0.4" stroke-width="4" fill="none"/>""")
        assertEquals(InkPass.TRANSLUCENT, parts[0].pass)
    }

    @Test
    fun `a hole is cut out of the fill`() {
        val solid = mesh("""<path d="M0 0 H100 V100 H0 Z" fill="black"/>""")
        val holed = mesh("""<path d="M0 0 H100 V100 H0 Z M30 30 V70 H70 V30 Z" fill="black"/>""")
        assertEquals(10000.0, area(solid), 1e-6)
        assertEquals(10000.0 - 1600.0, area(holed), 1e-3)
    }

    @Test
    fun `a stroke covers about its own width times its length`() {
        val parts = mesh("""<path d="M10 50 L90 50" stroke="black" stroke-width="4" fill="none"/>""")
        assertEquals(80.0 * 4.0, area(parts), 1.0)
    }

    @Test
    fun `a round cap adds a disc at each end`() {
        val butt = area(mesh("""<path d="M10 50 L90 50" stroke="black" stroke-width="10" fill="none"/>"""))
        val round = area(
            mesh("""<path d="M10 50 L90 50" stroke="black" stroke-width="10" stroke-linecap="round" fill="none"/>"""),
        )
        // A whole disc at each end; half of each overlaps the ribbon, which the triangle sum counts.
        assertEquals(2.0 * Math.PI * 25.0, round - butt, 2.0)
    }

    @Test
    fun `a mitred corner reaches past a bevelled one`() {
        val d = """M20 20 L80 20 L80 80"""
        val miter = area(mesh("""<path d="$d" stroke="black" stroke-width="20" fill="none" stroke-linejoin="miter"/>"""))
        val bevel = area(mesh("""<path d="$d" stroke="black" stroke-width="20" fill="none" stroke-linejoin="bevel"/>"""))
        assertTrue("miter $miter should exceed bevel $bevel", miter > bevel + 10.0)
    }

    @Test
    fun `a dashed stroke covers less than a solid one`() {
        val solid = area(mesh("""<path d="M0 50 L100 50" stroke="black" stroke-width="4" fill="none"/>"""))
        val dashed = area(
            mesh("""<path d="M0 50 L100 50" stroke="black" stroke-width="4" fill="none" stroke-dasharray="5 5"/>"""),
        )
        assertEquals(solid / 2.0, dashed, solid * 0.1)
    }

    @Test
    fun `a quarter turn swaps the box the document maps onto`() {
        // A wide document turned upright fills the tall box it was placed in.
        val parts = mesh("""<rect width="100" height="100" fill="black"/>""", Rect(0.0, 0.0, 40.0, 80.0), 90)
        val b = bounds(parts)
        assertEquals(40.0, b.w, 1e-6)
        assertEquals(80.0, b.h, 1e-6)
    }

    @Test
    fun `a free angle turns the geometry about the box centre`() {
        val parts = mesh("""<rect width="100" height="100" fill="black"/>""", Rect(0.0, 0.0, 100.0, 100.0), 0, Math.PI / 4.0)
        val b = bounds(parts)
        assertEquals(50.0, b.centerX, 1e-6)
        assertEquals(100.0 * Math.sqrt(2.0), b.w, 1e-6)
    }

    @Test
    fun `an empty document meshes to nothing`() {
        assertTrue(mesh("").isEmpty())
        assertTrue(VectorMesher.mesh(VectorScene.EMPTY, Rect(0.0, 0.0, 10.0, 10.0), tolerance = 0.1).isEmpty())
    }

    @Test
    fun `a path with neither fill nor stroke meshes to nothing`() {
        assertTrue(mesh("""<path d="M0 0 L10 10" fill="none"/>""").isEmpty())
    }

    @Test
    fun `both a fill and a stroke produce two parts, fill first`() {
        val parts = mesh("""<rect x="10" y="10" width="80" height="80" fill="red" stroke="blue" stroke-width="2"/>""")
        assertEquals(2, parts.size)
        assertEquals(255, parts[0].color.r)
        assertEquals(255, parts[1].color.b)
    }
}
