package com.xnotes.core.infinite

import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where a stored vertex lands on screen. The same arithmetic the ink vertex shader performs, stated
 * once in Kotlin so a test can hold the shader to it.
 *
 * It lives here rather than beside the shader because it is the one piece of the GL path that is
 * pure arithmetic, and pure arithmetic is the part that can be tested without a GPU. The two
 * regressions that put ink in the wrong place were both a factor applied twice in this formula, and
 * both were invisible to every existing test because the formula existed only as GLSL.
 *
 * Content space is unbounded, so a position is never held as one absolute number: it is a coarse
 * chunk index plus a small local offset, and the camera is subtracted in chunks before anything is
 * multiplied. Every intermediate therefore stays small however far out the viewport has travelled.
 */
object CanvasProjection {

    /**
     * Content pixels a chunk spans. Small enough that a local offset stays far inside float32's
     * exact range at the deepest zoom, large enough that the chunk index fits a signed short across
     * a world of about a hundred and thirty million content pixels.
     */
    const val CHUNK_SIZE = 4096.0

    /** Fixed-point steps per content pixel in the stored spine offset. */
    const val OFFSET_SCALE = 64.0

    /**
     * Narrowest half-width a line is rasterized at, in device pixels. Below this the vertex is
     * pushed back out to it and the width it gained comes out of its alpha, so a stroke fades
     * evenly instead of breaking into a crawling dotted shimmer.
     */
    const val MIN_HALF_WIDTH_PX = 0.5

    /** The chunk a content coordinate falls in, clamped to what a signed short holds. */
    fun chunkIndex(v: Double): Double {
        if (!v.isFinite()) return 0.0
        return floor(v / CHUNK_SIZE).coerceIn(-32768.0, 32767.0)
    }

    /** The scroll expressed inside its own chunk, so the uniform stays under a chunk's span. */
    fun localScroll(scroll: Double, camChunk: Double): Double = scroll - camChunk * CHUNK_SIZE

    /** Where a vertex sits in device pixels, y down from the viewport's top-left. */
    fun devicePoint(v: Vertex, camera: Camera): Point {
        val world = worldPoint(v, camera)
        return Point(
            (world.x - localScroll(camera.scrollX, camera.camChunkX)) * camera.zoom,
            (world.y - localScroll(camera.scrollY, camera.camChunkY)) * camera.zoom,
        )
    }

    /** Where a vertex sits in clip space, which is what the vertex shader writes. */
    fun clipPoint(v: Vertex, camera: Camera): Point {
        val d = devicePoint(v, camera)
        return Point(
            d.x / camera.viewportW * 2.0 - 1.0,
            1.0 - d.y / camera.viewportH * 2.0,
        )
    }

    /**
     * The alpha a vertex keeps after the sub-pixel rule. One for anything at least
     * [MIN_HALF_WIDTH_PX] wide on screen, and for every fill, which has no spine to measure.
     */
    fun widthFade(v: Vertex, camera: Camera): Double {
        val reach = hypot(v.offsetX * camera.widthScale, v.offsetY * camera.widthScale) * camera.zoom
        if (reach <= 0.0 || reach >= MIN_HALF_WIDTH_PX) return 1.0
        return reach / MIN_HALF_WIDTH_PX
    }

    /** The position relative to the camera's chunk, before the scroll and the zoom are applied. */
    private fun worldPoint(v: Vertex, camera: Camera): Point {
        // The vertex sits its own offset out from the line's centre, so the centre is recoverable
        // and the width can be scaled without touching the buffer.
        val centreX = v.localX - v.offsetX
        val centreY = v.localY - v.offsetY
        var spineX = v.offsetX * camera.widthScale
        var spineY = v.offsetY * camera.widthScale
        val reach = hypot(spineX, spineY) * camera.zoom
        if (reach > 0.0 && reach < MIN_HALF_WIDTH_PX) {
            val push = MIN_HALF_WIDTH_PX / reach
            spineX *= push
            spineY *= push
        }
        return Point(
            (v.chunkX - camera.camChunkX) * CHUNK_SIZE + centreX + spineX,
            (v.chunkY - camera.camChunkY) * CHUNK_SIZE + centreY + spineY,
        )
    }

    /** A vertex as the buffer holds it: chunk index, local offset, and displacement from its spine. */
    data class Vertex(
        val chunkX: Double,
        val chunkY: Double,
        val localX: Double,
        val localY: Double,
        val offsetX: Double = 0.0,
        val offsetY: Double = 0.0,
    ) {
        companion object {
            /** Split a content position the way the uploader does. */
            fun of(x: Double, y: Double, offsetX: Double = 0.0, offsetY: Double = 0.0): Vertex {
                val cx = chunkIndex(x)
                val cy = chunkIndex(y)
                return Vertex(cx, cy, x - cx * CHUNK_SIZE, y - cy * CHUNK_SIZE, offsetX, offsetY)
            }
        }
    }

    /** Everything the shader is told about the view for one draw. */
    data class Camera(
        val scrollX: Double,
        val scrollY: Double,
        val zoom: Double,
        val viewportW: Double,
        val viewportH: Double,
        val widthScale: Double = 1.0,
    ) {
        val camChunkX: Double get() = floor(scrollX / CHUNK_SIZE)
        val camChunkY: Double get() = floor(scrollY / CHUNK_SIZE)
    }

    data class Point(val x: Double, val y: Double)
}
