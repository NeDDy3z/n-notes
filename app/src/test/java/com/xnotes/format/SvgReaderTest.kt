package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.FillRule
import com.xnotes.core.vector.LineCap
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorPaint
import com.xnotes.core.vector.VectorScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgReaderTest {

    private fun read(body: String, attrs: String = "viewBox=\"0 0 100 100\""): VectorScene =
        SvgReader.parse("""<svg xmlns="http://www.w3.org/2000/svg" $attrs>$body</svg>""".toByteArray())

    private fun solid(paint: VectorPaint?) = (paint as? VectorPaint.Solid)?.color

    private fun points(scene: VectorScene, path: Int = 0, contour: Int = 0): List<Pt> =
        PathFlattener.flatten(scene.paths[path].contours[contour], 0.01)

    @Test
    fun `size comes from width and height, else the viewBox`() {
        assertEquals(100.0, read("", "viewBox=\"0 0 100 100\"").width, 1e-9)
        assertEquals(48.0, read("", "width=\"48\" height=\"24\" viewBox=\"0 0 96 48\"").width, 1e-9)
        assertEquals(24.0, read("", "width=\"48\" height=\"24\" viewBox=\"0 0 96 48\"").height, 1e-9)
        assertEquals(96.0, read("", "width=\"1in\" height=\"1in\"").width, 1e-9)
    }

    @Test
    fun `a viewBox maps onto the document box`() {
        // The viewBox is half the document size, so a unit square lands twice as big.
        val scene = read("""<rect x="0" y="0" width="10" height="10"/>""", "width=\"100\" height=\"100\" viewBox=\"0 0 50 50\"")
        val pts = points(scene)
        assertEquals(0.0, pts.minOf { it.x }, 1e-9)
        assertEquals(20.0, pts.maxOf { it.x }, 1e-9)
    }

    @Test
    fun `a viewBox origin shifts the content`() {
        val scene = read("""<rect x="10" y="10" width="10" height="10"/>""", "viewBox=\"10 10 100 100\"")
        assertEquals(0.0, points(scene).minOf { it.x }, 1e-9)
    }

    @Test
    fun `a rect fills black by default`() {
        val scene = read("""<rect x="1" y="2" width="3" height="4"/>""")
        assertEquals(1, scene.paths.size)
        assertEquals(0, solid(scene.paths[0].fill)!!.r)
        assertEquals(255, solid(scene.paths[0].fill)!!.a)
        assertNull(scene.paths[0].stroke)
    }

    @Test
    fun `a rounded rect is still one closed contour`() {
        val scene = read("""<rect x="0" y="0" width="20" height="10" rx="3"/>""")
        assertEquals(1, scene.paths[0].contours.size)
        assertTrue(scene.paths[0].contours[0].closed)
        val pts = points(scene)
        assertEquals(0.0, pts.minOf { it.x }, 1e-6)
        assertEquals(20.0, pts.maxOf { it.x }, 1e-6)
        assertEquals(10.0, pts.maxOf { it.y }, 1e-6)
    }

    @Test
    fun `every rounded corner is a true quarter circle`() {
        val r = 8.0
        val scene = read("""<rect x="0" y="0" width="60" height="40" rx="$r"/>""")
        val centres = listOf(
            Pt(r, r), Pt(60.0 - r, r), Pt(60.0 - r, 40.0 - r), Pt(r, 40.0 - r),
        )
        for (p in points(scene)) {
            // Points on a corner arc sit r from that corner's centre; points on an edge do not
            // belong to any corner, so only test the ones inside a corner's quadrant.
            val c = centres.firstOrNull {
                (p.x < r || p.x > 60.0 - r) && (p.y < r || p.y > 40.0 - r) &&
                    (p.x < it.x) == (it.x < 30.0) && (p.y < it.y) == (it.y < 20.0)
            } ?: continue
            val d = Math.hypot(p.x - c.x, p.y - c.y)
            assertEquals("point $p should sit $r from $c", r, d, 0.05)
        }
    }

    @Test
    fun `a circle spans its diameter`() {
        val pts = points(read("""<circle cx="50" cy="50" r="20"/>"""))
        assertEquals(30.0, pts.minOf { it.x }, 0.02)
        assertEquals(70.0, pts.maxOf { it.x }, 0.02)
        assertEquals(30.0, pts.minOf { it.y }, 0.02)
        assertEquals(70.0, pts.maxOf { it.y }, 0.02)
    }

    @Test
    fun `path data reads absolute and relative commands alike`() {
        val abs = points(read("""<path d="M 10 10 L 30 10 L 30 30 Z"/>"""))
        val rel = points(read("""<path d="m 10 10 l 20 0 l 0 20 z"/>"""))
        assertEquals(abs, rel)
        assertEquals(3, abs.size)
    }

    @Test
    fun `a moveto with extra pairs draws linetos`() {
        val pts = points(read("""<path d="M0 0 10 0 10 10"/>"""))
        assertEquals(3, pts.size)
        assertEquals(10.0, pts[2].x, 1e-9)
    }

    @Test
    fun `each subpath becomes its own contour`() {
        val scene = read("""<path d="M0 0 H10 V10 Z M20 20 H30 V30 Z"/>""")
        assertEquals(2, scene.paths[0].contours.size)
        assertTrue(scene.paths[0].contours.all { it.closed })
    }

    @Test
    fun `an arc bulges away from its chord`() {
        val pts = points(read("""<path d="M0 50 A 25 25 0 0 1 50 50"/>"""))
        assertTrue(pts.size > 4)
        assertEquals(25.0, pts.minOf { it.y }, 0.05)
    }

    @Test
    fun `a smooth curve reflects the previous control point`() {
        val curved = points(read("""<path d="M0 0 C 0 20 20 20 20 0 S 40 -20 40 0"/>"""))
        // The reflection puts the second curve's first control point below the axis.
        assertTrue(curved.any { it.y < -1.0 })
    }

    @Test
    fun `transforms compose down the tree`() {
        val scene = read("""<g transform="translate(10,0)"><g transform="scale(2)"><rect x="0" y="0" width="5" height="5"/></g></g>""")
        val pts = points(scene)
        assertEquals(10.0, pts.minOf { it.x }, 1e-9)
        assertEquals(20.0, pts.maxOf { it.x }, 1e-9)
    }

    @Test
    fun `rotate about a point turns around it`() {
        val pts = points(read("""<path transform="rotate(90 10 10)" d="M10 10 L20 10"/>"""))
        assertEquals(10.0, pts[1].x, 1e-9)
        assertEquals(20.0, pts[1].y, 1e-9)
    }

    @Test
    fun `a matrix transform scales the stroke width with it`() {
        val scene = read("""<path transform="matrix(3,0,0,3,0,0)" stroke="red" stroke-width="2" d="M0 0 L1 1"/>""")
        assertEquals(6.0, scene.paths[0].strokeWidth, 1e-9)
    }

    @Test
    fun `presentation attributes inherit from the group`() {
        val scene = read("""<g fill="red" fill-rule="evenodd"><rect width="4" height="4"/></g>""")
        assertEquals(255, solid(scene.paths[0].fill)!!.r)
        assertEquals(FillRule.EVEN_ODD, scene.paths[0].fillRule)
    }

    @Test
    fun `the style attribute beats the presentation attribute`() {
        val scene = read("""<rect width="4" height="4" fill="red" style="fill:blue"/>""")
        assertEquals(255, solid(scene.paths[0].fill)!!.b)
        assertEquals(0, solid(scene.paths[0].fill)!!.r)
    }

    @Test
    fun `a stylesheet class styles its elements`() {
        val scene = read("""<style>.a { fill: #00ff00; stroke-linecap: round }</style><rect class="a" width="4" height="4"/>""")
        assertEquals(255, solid(scene.paths[0].fill)!!.g)
        assertEquals(LineCap.ROUND, scene.paths[0].cap)
    }

    @Test
    fun `currentColor follows the color property`() {
        val scene = read("""<g color="#123456"><rect width="4" height="4" fill="currentColor"/></g>""")
        assertEquals(0x12, solid(scene.paths[0].fill)!!.r)
        assertEquals(0x56, solid(scene.paths[0].fill)!!.b)
    }

    @Test
    fun `opacity multiplies down onto the paint`() {
        val scene = read("""<g opacity="0.5"><rect width="4" height="4" fill-opacity="0.5"/></g>""")
        assertEquals(64.0, solid(scene.paths[0].fill)!!.a.toDouble(), 2.0)
    }

    @Test
    fun `fill none with no stroke draws nothing`() {
        assertTrue(read("""<rect width="4" height="4" fill="none"/>""").paths.isEmpty())
    }

    @Test
    fun `display none hides a whole subtree`() {
        assertTrue(read("""<g display="none"><rect width="4" height="4"/></g>""").paths.isEmpty())
    }

    @Test
    fun `use draws its target again where it points`() {
        val scene = read(
            """<defs><rect id="r" width="4" height="4"/></defs><use href="#r" x="10" y="0"/><use href="#r" x="20" y="0"/>""",
        )
        assertEquals(2, scene.paths.size)
        assertEquals(10.0, points(scene, 0).minOf { it.x }, 1e-9)
        assertEquals(20.0, points(scene, 1).minOf { it.x }, 1e-9)
    }

    @Test
    fun `use pointing at its own ancestor terminates`() {
        val scene = read("""<g id="g"><use href="#g"/><rect width="4" height="4"/></g>""")
        assertTrue(scene.paths.isNotEmpty())
        assertTrue(scene.skipped.contains("deeply nested use"))
    }

    @Test
    fun `stroke settings survive the read`() {
        val scene = read(
            """<path d="M0 0 L10 0" stroke="steelblue" stroke-width="3" stroke-linecap="round" stroke-dasharray="4 2"/>""",
        )
        assertEquals(0x46, solid(scene.paths[0].stroke)!!.r)
        assertEquals(3.0, scene.paths[0].strokeWidth, 1e-9)
        assertEquals(LineCap.ROUND, scene.paths[0].cap)
        assertEquals(listOf(4.0, 2.0), scene.paths[0].dash!!.toList())
    }

    @Test
    fun `an odd dash pattern is doubled to make it even`() {
        val scene = read("""<path d="M0 0 L10 0" stroke="black" stroke-dasharray="5"/>""")
        assertEquals(listOf(5.0, 5.0), scene.paths[0].dash!!.toList())
    }

    @Test
    fun `unimplemented features are named rather than silently dropped`() {
        val scene = read(
            """<defs><pattern id="p"/><filter id="f"/></defs>
               <rect width="4" height="4" fill="url(#p)"/><text x="0" y="0">hi</text><image href="x.png"/>""",
        )
        assertTrue(scene.skipped.contains("pattern"))
        assertTrue(scene.skipped.contains("text"))
        assertTrue(scene.skipped.contains("image"))
    }

    @Test
    fun `a rectangular clip is kept and a shaped one is named and ignored`() {
        val rect = read(
            """<defs><clipPath id="c"><rect x="1" y="2" width="3" height="4"/></clipPath></defs>
               <rect width="10" height="10" clip-path="url(#c)"/>""",
        )
        assertEquals(1.0, rect.paths[0].clip!!.left, 1e-9)
        assertEquals(3.0, rect.paths[0].clip!!.w, 1e-9)
        assertTrue(rect.skipped.isEmpty())

        val shaped = read(
            """<defs><clipPath id="c"><circle cx="5" cy="5" r="4"/></clipPath></defs>
               <rect width="10" height="10" clip-path="url(#c)"/>""",
        )
        assertNull(shaped.paths[0].clip)
        assertTrue(shaped.skipped.contains("clip path"))
    }

    @Test
    fun `a gradient resolves against the path's own box`() {
        val scene = read(
            """<defs><linearGradient id="g"><stop offset="0" stop-color="red"/>
                 <stop offset="1" stop-color="blue"/></linearGradient></defs>
               <rect x="10" y="20" width="40" height="60" fill="url(#g)"/>""",
        )
        val g = scene.paths[0].fill as VectorPaint.Linear
        assertEquals(10.0, g.x0, 1e-9)
        assertEquals(50.0, g.x1, 1e-9)
        assertEquals(2, g.stops.size)
        assertTrue(scene.skipped.isEmpty())
    }

    @Test
    fun `stop opacity folds into the stop's own alpha`() {
        val scene = read(
            """<defs><linearGradient id="g"><stop offset="0" stop-color="red" stop-opacity="0.5"/>
                 <stop offset="1" stop-color="blue"/></linearGradient></defs>
               <rect width="10" height="10" fill="url(#g)" fill-opacity="0.5"/>""",
        )
        val g = scene.paths[0].fill as VectorPaint.Linear
        assertEquals(64.0, g.stops[0].color.a.toDouble(), 2.0)
        assertEquals(128.0, g.stops[1].color.a.toDouble(), 2.0)
    }

    @Test
    fun `a filter and a mask are named where they are used, and the element still draws`() {
        val scene = read(
            """<defs><filter id="f"/><mask id="m"/></defs>
               <rect width="4" height="4" filter="url(#f)"/>
               <rect width="4" height="4" style="mask:url(#m)"/>""",
        )
        assertEquals(2, scene.paths.size)
        assertTrue(scene.skipped.contains("filter"))
        assertTrue(scene.skipped.contains("mask"))
    }

    @Test
    fun `group opacity is named only when the group holds more than one thing`() {
        val many = read("""<g opacity="0.5"><rect width="4" height="4"/><rect width="4" height="4"/></g>""")
        assertTrue(many.skipped.contains("group opacity"))
        val one = read("""<g opacity="0.5"><rect width="4" height="4"/></g>""")
        assertTrue(one.skipped.isEmpty())
    }

    @Test
    fun `a file that will not parse loads empty`() {
        assertTrue(SvgReader.parse("<svg><rect".toByteArray()).isEmpty)
        assertTrue(SvgReader.parse(ByteArray(0)).isEmpty)
        assertTrue(SvgReader.parse("<html><body/></html>".toByteArray()).isEmpty)
    }

    @Test
    fun `a polygon closes and a polyline does not`() {
        val closed = read("""<polygon points="0,0 10,0 10,10"/>""")
        val open = read("""<polyline points="0,0 10,0 10,10" fill="none" stroke="black"/>""")
        assertTrue(closed.paths[0].contours[0].closed)
        assertTrue(!open.paths[0].contours[0].closed)
    }

    @Test
    fun `colour syntax covers hex, rgb and names`() {
        assertEquals(0x11, solid(read("""<rect width="1" height="1" fill="#123"/>""").paths[0].fill)!!.r)
        assertEquals(0x33, solid(read("""<rect width="1" height="1" fill="#123"/>""").paths[0].fill)!!.b)
        assertEquals(10, solid(read("""<rect width="1" height="1" fill="rgb(10,20,30)"/>""").paths[0].fill)!!.r)
        assertEquals(128, solid(read("""<rect width="1" height="1" fill="rgb(50%,0,0)"/>""").paths[0].fill)!!.r, )
        assertNotNull(solid(read("""<rect width="1" height="1" fill="rebeccapurple"/>""").paths[0].fill))
    }
}
