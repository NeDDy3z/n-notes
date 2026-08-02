package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.StrokeGeometry
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A triangle mesh in content space. Positions are doubles because the canvas is unbounded: a float
 * absolute position has already lost visible precision a million pixels from the origin, and the
 * uploader splits each coordinate into a coarse chunk index plus a small local offset precisely so
 * the GPU never has to hold one. Indices are relative to this mesh's own first vertex.
 */
class MeshData(
    /** Interleaved x,y in content space; `positions.size / 2` vertices. */
    val positions: DoubleArray,
    /**
     * Interleaved x,y displacement of each vertex from the line it belongs to, zero for a fill.
     * The renderer uses it to keep a sub-pixel line visible rather than letting it shimmer.
     */
    val offsets: DoubleArray,
    /** Triangle list, three indices per triangle, zero-based within this mesh. */
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 2
    val triangleCount: Int get() = indices.size / 3
    val isEmpty: Boolean get() = indices.isEmpty()

    companion object {
        val EMPTY = MeshData(DoubleArray(0), DoubleArray(0), IntArray(0))
    }
}

/**
 * Turns a [StrokeGeometry] into triangles once, at commit time. Every frame then draws those
 * triangles with the current zoom pushed in as a uniform, so ink is resolution independent: there
 * is no raster to be at the wrong scale, and no blur to resolve when a pinch settles.
 *
 * The ribbon needs no triangulator. [StrokeGeometry.outline] already holds both rails, the left
 * edge forward then the right edge reversed, so vertex `i` of the left rail is `outline[2i]` and
 * its partner on the right rail is `outline[2 * (2n - 1 - i)]`. Consecutive quads share their
 * whole edge exactly, so the body is watertight with no join geometry at all.
 *
 * What the rails do not cover is the round ends, and the outer notch where the stroke turns hard
 * enough that the two quads pinch. Both are filled with a disc, which is what the paged renderer
 * sweeps down the centerline, so the silhouette matches the ink the rest of the app draws.
 *
 * Antialiasing comes from multisampling on these triangle edges rather than from coverage computed
 * in a shader, because MSAA is the only one of the two that composes correctly where a stroke
 * overlaps itself: a sample is covered or not, so the same colour written twice is still that
 * colour. That makes the silhouette's fidelity the whole of the quality, which is why the round
 * parts are tessellated to a chord tolerance rather than to a fixed segment count.
 */
object StrokeTessellator {

    /**
     * Content-space chord error allowed on a round cap or join. Chosen so a curve stays under half
     * a device pixel of error at [CanvasViewport.MAX_ZOOM], since geometry is baked once and the
     * canvas zooms far past what a page ever does.
     */
    const val DEFAULT_TOLERANCE = 0.5 / CanvasViewport.MAX_ZOOM

    /** Fewest and most segments a full circle is ever cut into. */
    const val MIN_CIRCLE_SEGMENTS = MeshBuilder.MIN_CIRCLE_SEGMENTS
    const val MAX_CIRCLE_SEGMENTS = MeshBuilder.MAX_CIRCLE_SEGMENTS

    /**
     * Turn angle, in radians, past which a sample gets its own disc. Below it the two ribbon quads
     * already meet flush; above it their outer edges pinch and leave a notch the disc fills.
     * Smoothed handwriting turns a fraction of this per sample, so discs stay rare.
     */
    const val JOIN_DISC_ANGLE = 0.18

    /** Half-widths at or below this contribute nothing and are skipped. */
    private const val MIN_HALF_WIDTH = 1e-6

    /**
     * [widthScale] narrows the ribbon about its own centreline, which is how neon's white-hot core
     * is drawn: the same path at a fraction of the width, so it rounds with the body on every pen.
     */
    fun tessellate(
        g: StrokeGeometry,
        tolerance: Double = DEFAULT_TOLERANCE,
        widthScale: Double = 1.0,
    ): MeshData {
        val n = g.pointCount
        if (n == 0) return MeshData.EMPTY
        val b = MeshBuilder(estimateVertices(g), estimateIndices(g))
        if (n == 1) {
            val h = g.hw(0) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(0), g.cy(0), h, tolerance)
            return b.build()
        }
        if (widthScale != 1.0) return scaledRibbon(g, tolerance, widthScale)
        if (g.outlineCount < 2 * n) return b.build() // geometry without rails: nothing to draw

        // Body: one quad per segment, both of its vertices taken straight off the rails, so
        // consecutive quads share an entire edge and the ribbon never gaps along its length.
        for (i in 0 until n - 1) {
            val h0 = g.hw(i)
            val h1 = g.hw(i + 1)
            if (h0 <= MIN_HALF_WIDTH && h1 <= MIN_HALF_WIDTH) continue
            // Each rail vertex remembers how far it sits from the centreline, so a stroke thinner
            // than a pixel can be widened back to one and faded instead of breaking up.
            val l0 = b.vertex(leftX(g, n, i), leftY(g, n, i), leftX(g, n, i) - g.cx(i), leftY(g, n, i) - g.cy(i))
            val r0 = b.vertex(rightX(g, n, i), rightY(g, n, i), rightX(g, n, i) - g.cx(i), rightY(g, n, i) - g.cy(i))
            val l1 = b.vertex(leftX(g, n, i + 1), leftY(g, n, i + 1), leftX(g, n, i + 1) - g.cx(i + 1), leftY(g, n, i + 1) - g.cy(i + 1))
            val r1 = b.vertex(rightX(g, n, i + 1), rightY(g, n, i + 1), rightX(g, n, i + 1) - g.cx(i + 1), rightY(g, n, i + 1) - g.cy(i + 1))
            b.triangle(l0, r0, r1)
            b.triangle(l0, r1, l1)
        }

        // Round ends. A whole disc rather than a half one: it costs two extra fans per stroke and
        // removes every orientation question, and it is exactly the disc the paged renderer sweeps.
        if (g.hw(0) > MIN_HALF_WIDTH) b.circle(g.cx(0), g.cy(0), g.hw(0), tolerance)
        if (g.hw(n - 1) > MIN_HALF_WIDTH) b.circle(g.cx(n - 1), g.cy(n - 1), g.hw(n - 1), tolerance)

        // Discs at the hard turns only.
        for (i in 1 until n - 1) {
            val h = g.hw(i)
            if (h <= MIN_HALF_WIDTH) continue
            if (turnAngle(g, i) > JOIN_DISC_ANGLE) b.circle(g.cx(i), g.cy(i), h, tolerance)
        }
        return b.build()
    }

    /**
     * The dashed pen: the smoothed centreline cut into on/off runs, each drawn as a constant-width
     * ribbon with round ends. Same runs, same width and same round caps the paged renderer's dashed
     * pen paints, so a dashed stroke breaks in the same places on either canvas.
     *
     * A stroke too short to have a line is left to [tessellate], which draws it as the dot the paged
     * painter falls back to.
     */
    fun tessellateDashed(
        g: StrokeGeometry,
        dashLength: Double,
        dashGap: Double,
        halfWidth: Double,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): MeshData {
        val n = g.pointCount
        if (n < 2) return tessellate(g, tolerance)
        if (halfWidth <= MIN_HALF_WIDTH) return MeshData.EMPTY
        val path = ArrayList<Pt>(n)
        for (i in 0 until n) path.add(Pt(g.cx(i), g.cy(i)))
        val b = MeshBuilder(estimateVertices(g), estimateIndices(g))
        for (run in MeshBuilder.dashRuns(path, dashLength, dashGap, closed = false)) {
            b.polylineRibbon(run, halfWidth, closed = false, tolerance = tolerance)
        }
        return b.build()
    }

    /**
     * The ribbon at a fraction of its width, built from the centreline and scaled half-widths
     * rather than from the rails, since the rails only exist at full width.
     */
    private fun scaledRibbon(g: StrokeGeometry, tolerance: Double, widthScale: Double): MeshData {
        val n = g.pointCount
        val b = MeshBuilder(estimateVertices(g), estimateIndices(g))
        for (i in 0 until n - 1) {
            val h0 = g.hw(i) * widthScale
            val h1 = g.hw(i + 1) * widthScale
            if (h0 <= MIN_HALF_WIDTH && h1 <= MIN_HALF_WIDTH) continue
            val dx = g.cx(i + 1) - g.cx(i)
            val dy = g.cy(i + 1) - g.cy(i)
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val nx = -dy / len
            val ny = dx / len
            val l0 = b.vertex(g.cx(i) + nx * h0, g.cy(i) + ny * h0, nx * h0, ny * h0)
            val r0 = b.vertex(g.cx(i) - nx * h0, g.cy(i) - ny * h0, -nx * h0, -ny * h0)
            val l1 = b.vertex(g.cx(i + 1) + nx * h1, g.cy(i + 1) + ny * h1, nx * h1, ny * h1)
            val r1 = b.vertex(g.cx(i + 1) - nx * h1, g.cy(i + 1) - ny * h1, -nx * h1, -ny * h1)
            b.triangle(l0, r0, r1)
            b.triangle(l0, r1, l1)
        }
        // A disc at every sample, since a segment-normal ribbon gaps at its joins.
        for (i in 0 until n) {
            val h = g.hw(i) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(i), g.cy(i), h, tolerance)
        }
        return b.build()
    }

    /** Segments a circle of [radius] needs to stay within [tolerance] of true. */
    fun circleSegments(radius: Double, tolerance: Double): Int =
        MeshBuilder.circleSegments(radius, tolerance)

    /** Angle between the ribbon's normal before and after sample [i], in radians. */
    fun turnAngle(g: StrokeGeometry, i: Int): Double {
        val n = g.pointCount
        if (i <= 0 || i >= n - 1) return 0.0
        val ax = g.cx(i) - g.cx(i - 1)
        val ay = g.cy(i) - g.cy(i - 1)
        val bx = g.cx(i + 1) - g.cx(i)
        val by = g.cy(i + 1) - g.cy(i)
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < 1e-12 || lb < 1e-12) return 0.0
        val cross = (ax * by - ay * bx) / (la * lb)
        val dot = (ax * bx + ay * by) / (la * lb)
        return kotlin.math.abs(atan2(cross, dot))
    }

    // --- rail accessors: the outline is the left edge forward, then the right edge reversed ---

    fun leftX(g: StrokeGeometry, n: Int, i: Int): Double = g.outline[2 * i].toDouble()
    fun leftY(g: StrokeGeometry, n: Int, i: Int): Double = g.outline[2 * i + 1].toDouble()
    fun rightX(g: StrokeGeometry, n: Int, i: Int): Double = g.outline[2 * (2 * n - 1 - i)].toDouble()
    fun rightY(g: StrokeGeometry, n: Int, i: Int): Double = g.outline[2 * (2 * n - 1 - i) + 1].toDouble()

    private fun estimateVertices(g: StrokeGeometry): Int {
        val n = g.pointCount
        return 2 * n + 2 * (MIN_CIRCLE_SEGMENTS + 2) + 16
    }

    private fun estimateIndices(g: StrokeGeometry): Int {
        val n = g.pointCount
        return 6 * maxOf(0, n - 1) + 6 * (MIN_CIRCLE_SEGMENTS + 2) + 48
    }
}
