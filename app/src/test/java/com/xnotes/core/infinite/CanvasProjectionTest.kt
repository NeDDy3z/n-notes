package com.xnotes.core.infinite

import com.xnotes.core.infinite.CanvasProjection.Camera
import com.xnotes.core.infinite.CanvasProjection.Vertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CanvasProjectionTest {

    private fun camera(
        scrollX: Double = 0.0,
        scrollY: Double = 0.0,
        zoom: Double = 1.0,
        widthScale: Double = 1.0,
    ) = Camera(scrollX, scrollY, zoom, 1000.0, 800.0, widthScale)

    @Test
    fun `content under the viewport origin lands at the top left`() {
        val camera = camera(scrollX = 300.0, scrollY = 200.0)
        val d = CanvasProjection.devicePoint(Vertex.of(300.0, 200.0), camera)
        assertEquals(0.0, d.x, 1e-9)
        assertEquals(0.0, d.y, 1e-9)
    }

    @Test
    fun `zoom scales the distance from the viewport origin`() {
        val camera = camera(scrollX = 100.0, scrollY = 100.0, zoom = 2.5)
        val d = CanvasProjection.devicePoint(Vertex.of(140.0, 180.0), camera)
        assertEquals(100.0, d.x, 1e-9)
        assertEquals(200.0, d.y, 1e-9)
    }

    /** The regression that came back twice: a factor applied once too often. */
    @Test
    fun `zoom is applied exactly once`() {
        val plain = camera(zoom = 1.0)
        val doubled = camera(zoom = 2.0)
        val v = Vertex.of(120.0, 90.0)
        val a = CanvasProjection.devicePoint(v, plain)
        val b = CanvasProjection.devicePoint(v, doubled)
        assertEquals(a.x * 2.0, b.x, 1e-9)
        assertEquals(a.y * 2.0, b.y, 1e-9)
    }

    @Test
    fun `clip space puts the viewport centre at the origin and flips y`() {
        val camera = camera()
        val c = CanvasProjection.clipPoint(Vertex.of(500.0, 400.0), camera)
        assertEquals(0.0, c.x, 1e-9)
        assertEquals(0.0, c.y, 1e-9)

        val topLeft = CanvasProjection.clipPoint(Vertex.of(0.0, 0.0), camera)
        assertEquals(-1.0, topLeft.x, 1e-9)
        assertEquals(1.0, topLeft.y, 1e-9)

        val bottomRight = CanvasProjection.clipPoint(Vertex.of(1000.0, 800.0), camera)
        assertEquals(1.0, bottomRight.x, 1e-9)
        assertEquals(-1.0, bottomRight.y, 1e-9)
    }

    /** Splitting a position into chunk plus local must be exactly reversible. */
    @Test
    fun `a far away vertex lands where a near one at the same offset does`() {
        val near = CanvasProjection.devicePoint(
            Vertex.of(37.5, 12.25), camera(scrollX = 0.0, scrollY = 0.0),
        )
        val far = CanvasProjection.devicePoint(
            Vertex.of(40_000_037.5, 40_000_012.25),
            camera(scrollX = 40_000_000.0, scrollY = 40_000_000.0),
        )
        assertEquals(near.x, far.x, 1e-6)
        assertEquals(near.y, far.y, 1e-6)
    }

    @Test
    fun `the camera chunk absorbs the scroll so nothing large is multiplied`() {
        val camera = camera(scrollX = 40_000_000.0, scrollY = -9_000_000.0)
        val localX = CanvasProjection.localScroll(camera.scrollX, camera.camChunkX)
        val localY = CanvasProjection.localScroll(camera.scrollY, camera.camChunkY)
        assertTrue(localX >= 0.0 && localX < CanvasProjection.CHUNK_SIZE)
        assertTrue(localY >= 0.0 && localY < CanvasProjection.CHUNK_SIZE)
    }

    @Test
    fun `a negative coordinate takes the chunk below it`() {
        assertEquals(-1.0, CanvasProjection.chunkIndex(-1.0), 0.0)
        assertEquals(-1.0, CanvasProjection.chunkIndex(-CanvasProjection.CHUNK_SIZE), 0.0)
        assertEquals(-2.0, CanvasProjection.chunkIndex(-CanvasProjection.CHUNK_SIZE - 1.0), 0.0)
        assertEquals(0.0, CanvasProjection.chunkIndex(0.0), 0.0)
    }

    @Test
    fun `a fill keeps its full alpha at every zoom`() {
        val fill = Vertex.of(10.0, 10.0)
        assertEquals(1.0, CanvasProjection.widthFade(fill, camera(zoom = 0.02)), 0.0)
        assertEquals(1.0, CanvasProjection.widthFade(fill, camera(zoom = 64.0)), 0.0)
    }

    @Test
    fun `a sub-pixel stroke widens to half a pixel and loses the width from its alpha`() {
        // A rail vertex at x=10 displaced 2 from its spine, so the spine runs down x=8. At 0.05x
        // that half-width reaches 0.1 device px, a fifth of the floor.
        val edge = Vertex.of(10.0, 10.0, offsetX = 2.0, offsetY = 0.0)
        val spine = Vertex.of(8.0, 10.0)
        val cam = camera(zoom = 0.05)
        assertEquals(0.2, CanvasProjection.widthFade(edge, cam), 1e-9)

        val d = CanvasProjection.devicePoint(edge, cam)
        val c = CanvasProjection.devicePoint(spine, cam)
        assertEquals(CanvasProjection.MIN_HALF_WIDTH_PX, abs(d.x - c.x), 1e-9)
    }

    @Test
    fun `a stroke wider than the floor is left alone`() {
        val edge = Vertex.of(10.0, 10.0, offsetX = 2.0, offsetY = 0.0)
        val cam = camera(zoom = 4.0)
        assertEquals(1.0, CanvasProjection.widthFade(edge, cam), 0.0)
        val d = CanvasProjection.devicePoint(edge, cam)
        val c = CanvasProjection.devicePoint(Vertex.of(8.0, 10.0), cam)
        assertEquals(8.0, abs(d.x - c.x), 1e-9)
    }

    /** Neon's core is the body's own geometry narrowed about its centreline. */
    @Test
    fun `the width scale narrows a ribbon about its own centre`() {
        // Spine at x=6, rail at x=10. A quarter width puts the rail at 7 and leaves the spine put.
        val edge = Vertex.of(10.0, 10.0, offsetX = 4.0, offsetY = 0.0)
        val cam = camera(zoom = 1.0, widthScale = 0.25)
        val d = CanvasProjection.devicePoint(edge, cam)
        assertEquals(7.0, d.x, 1e-9)
        assertEquals(10.0, d.y, 1e-9)
        assertEquals(6.0, CanvasProjection.devicePoint(Vertex.of(6.0, 10.0), cam).x, 1e-9)
    }

    /** Dragging a selection is a uniform, so it must land exactly where moving the model would. */
    @Test
    fun `a live translation matches moving the content itself`() {
        val cam = camera(scrollX = 100.0, scrollY = 50.0, zoom = 1.5)
        val dragged = cam.copy(translateX = 17.0, translateY = -9.0)
        val live = CanvasProjection.devicePoint(Vertex.of(220.0, 130.0), dragged)
        val moved = CanvasProjection.devicePoint(Vertex.of(237.0, 121.0), cam)
        assertEquals(moved.x, live.x, 1e-9)
        assertEquals(moved.y, live.y, 1e-9)
    }

    @Test
    fun `a live translation scales with the zoom like everything else`() {
        val at = Vertex.of(10.0, 10.0)
        val plain = CanvasProjection.devicePoint(at, camera(zoom = 3.0))
        val shifted = CanvasProjection.devicePoint(at, camera(zoom = 3.0).copy(translateX = 4.0))
        assertEquals(12.0, shifted.x - plain.x, 1e-9)
    }

    /** Turning a selection is a uniform too, so it must land where rotating the model would. */
    @Test
    fun `a live rotation matches turning the content itself`() {
        val cam = camera(scrollX = 100.0, scrollY = 50.0, zoom = 1.5)
        // A quarter turn about (200, 100) sends (260, 100) to (200, 160).
        val turned = cam.copy(rotCos = 0.0, rotSin = 1.0, pivotX = 200.0, pivotY = 100.0)
        val live = CanvasProjection.devicePoint(Vertex.of(260.0, 100.0), turned)
        val baked = CanvasProjection.devicePoint(Vertex.of(200.0, 160.0), cam)
        assertEquals(baked.x, live.x, 1e-9)
        assertEquals(baked.y, live.y, 1e-9)
    }

    /** A rotation must leave the ribbon exactly as wide as it was; that is why it can be a uniform. */
    @Test
    fun `a live rotation keeps the ribbon its own width`() {
        val cam = camera(zoom = 1.0)
        val turned = cam.copy(rotCos = 0.0, rotSin = 1.0, pivotX = 10.0, pivotY = 10.0)
        val rail = Vertex.of(10.0, 14.0, offsetX = 0.0, offsetY = 4.0)
        val spine = Vertex.of(10.0, 10.0)
        val railAt = CanvasProjection.devicePoint(rail, turned)
        val spineAt = CanvasProjection.devicePoint(spine, turned)
        val half = kotlin.math.hypot(railAt.x - spineAt.x, railAt.y - spineAt.y)
        assertEquals("a turn is not a scale", 4.0, half, 1e-9)
        // And the rail turned with the spine: what pointed down now points left.
        assertEquals(-4.0, railAt.x - spineAt.x, 1e-9)
        assertEquals(0.0, railAt.y - spineAt.y, 1e-9)
    }

    @Test
    fun `a live rotation leaves its own pivot alone`() {
        val cam = camera(scrollX = 20.0, scrollY = 30.0, zoom = 2.0)
        val turned = cam.copy(rotCos = 0.6, rotSin = 0.8, pivotX = 90.0, pivotY = 70.0)
        val plain = CanvasProjection.devicePoint(Vertex.of(90.0, 70.0), cam)
        val spun = CanvasProjection.devicePoint(Vertex.of(90.0, 70.0), turned)
        assertEquals(plain.x, spun.x, 1e-6)
        assertEquals(plain.y, spun.y, 1e-6)
    }

    @Test
    fun `the width scale leaves the centreline where it was`() {
        val centre = Vertex.of(10.0, 10.0)
        val plain = CanvasProjection.devicePoint(centre, camera())
        val scaled = CanvasProjection.devicePoint(centre, camera(widthScale = 0.25))
        assertEquals(plain.x, scaled.x, 1e-9)
        assertEquals(plain.y, scaled.y, 1e-9)
    }
}
