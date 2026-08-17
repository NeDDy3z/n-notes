package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import kotlin.math.abs

/**
 * Turns a triangulated fill into a gradient, by subdividing until interpolating the ramp between
 * the corners is indistinguishable from evaluating it.
 *
 * This is the whole of gradient support, and it needs no shader. The vertex format already carries
 * RGBA and the rasterizer already interpolates it across a triangle, so once the mesh is fine
 * enough the ramp draws in the same batched call as a flat fill and costs nothing per frame.
 *
 * Subdivision is adaptive against the ramp rather than uniform. A two-stop linear gradient is exact
 * on the original triangles and is not split at all; a radial one or a ramp with tight stops splits
 * only where the error actually is.
 */
internal object GradientFill {

    /** [mesh] subdivided against [ramp], with a colour per vertex. */
    fun refine(mesh: Triangulator.Mesh, ramp: GradientRamp): MeshData {
        val sink = Sink(mesh.indices.size)
        var i = 0
        while (i < mesh.indices.size) {
            val a = mesh.points[mesh.indices[i]]
            val b = mesh.points[mesh.indices[i + 1]]
            val c = mesh.points[mesh.indices[i + 2]]
            split(sink, ramp, a, b, c, ramp.colorAt(a), ramp.colorAt(b), ramp.colorAt(c), 0)
            i += 3
        }
        return sink.build()
    }

    /** [data]'s existing vertices coloured from [ramp], with no subdivision. */
    fun color(data: MeshData, ramp: GradientRamp): MeshData {
        val colors = IntArray(data.vertexCount)
        for (i in colors.indices) {
            colors[i] = ramp.colorAt(Pt(data.positions[2 * i], data.positions[2 * i + 1])).toArgb()
        }
        return MeshData(data.positions, data.offsets, data.indices, colors)
    }

    private fun split(
        sink: Sink,
        ramp: GradientRamp,
        a: Pt,
        b: Pt,
        c: Pt,
        ca: Rgba,
        cb: Rgba,
        cc: Rgba,
        depth: Int,
    ) {
        if (depth >= MAX_DEPTH || sink.triangles >= MAX_TRIANGLES) {
            sink.add(a, b, c, ca, cb, cc)
            return
        }
        val ab = mid(a, b)
        val bc = mid(b, c)
        val ca2 = mid(c, a)
        val cab = ramp.colorAt(ab)
        val cbc = ramp.colorAt(bc)
        val cca = ramp.colorAt(ca2)
        // The midpoints are exactly where a split would put new vertices, so comparing the true
        // colour there against the interpolated one measures the error the split would remove.
        if (near(cab, ca, cb) && near(cbc, cb, cc) && near(cca, cc, ca)) {
            sink.add(a, b, c, ca, cb, cc)
            return
        }
        split(sink, ramp, a, ab, ca2, ca, cab, cca, depth + 1)
        split(sink, ramp, ab, b, bc, cab, cb, cbc, depth + 1)
        split(sink, ramp, ca2, bc, c, cca, cbc, cc, depth + 1)
        split(sink, ramp, ab, bc, ca2, cab, cbc, cca, depth + 1)
    }

    private fun near(actual: Rgba, a: Rgba, b: Rgba): Boolean =
        abs(actual.r - (a.r + b.r) / 2) <= TOLERANCE &&
            abs(actual.g - (a.g + b.g) / 2) <= TOLERANCE &&
            abs(actual.b - (a.b + b.b) / 2) <= TOLERANCE &&
            abs(actual.a - (a.a + b.a) / 2) <= TOLERANCE

    private fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

    /**
     * Collects the refined triangles. Vertices are not shared between them: a shared one would have
     * to agree with every triangle that touches it, and neighbours refined to different depths do
     * not. Their positions still line up exactly, so there is no crack, only a few more vertices.
     */
    private class Sink(hint: Int) {
        private var xs = DoubleArray(maxOf(48, hint * 2))
        private var cols = IntArray(maxOf(24, hint))
        private var count = 0

        val triangles: Int get() = count / 3

        fun add(a: Pt, b: Pt, c: Pt, ca: Rgba, cb: Rgba, cc: Rgba) {
            grow(3)
            put(a, ca)
            put(b, cb)
            put(c, cc)
        }

        private fun put(p: Pt, color: Rgba) {
            xs[2 * count] = p.x
            xs[2 * count + 1] = p.y
            cols[count] = color.toArgb()
            count++
        }

        private fun grow(more: Int) {
            if (count + more <= cols.size) return
            var size = cols.size
            while (size < count + more) size *= 2
            xs = xs.copyOf(size * 2)
            cols = cols.copyOf(size)
        }

        fun build(): MeshData = MeshData(
            xs.copyOf(2 * count),
            DoubleArray(2 * count),
            IntArray(count) { it },
            cols.copyOf(count),
        )
    }

    /** Channel steps of error allowed before a triangle is split; one step is invisible. */
    private const val TOLERANCE = 3

    /** Deepest one triangle is split: 4^5 pieces is far past what any ramp needs. */
    private const val MAX_DEPTH = 5

    /** Total ceiling per fill, so a ramp on a complex outline cannot run away. */
    private const val MAX_TRIANGLES = 24000
}
