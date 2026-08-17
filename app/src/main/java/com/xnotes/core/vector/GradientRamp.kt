package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Evaluates a gradient at a point, in the document's own coordinate space.
 *
 * The canvas has no gradient shader and does not need one: the vertex format already carries RGBA
 * and the rasterizer already interpolates it across a triangle. So a ramp becomes per-vertex colour
 * on a mesh subdivided until the interpolation error disappears, and from then on it draws in the
 * same batched call as everything else. See [GradientRefine], which does the subdividing.
 */
internal class GradientRamp private constructor(
    private val stops: List<GradientStop>,
    private val spread: SpreadMethod,
    private val param: (Pt) -> Double,
) {

    fun colorAt(p: Pt): Rgba = sample(wrap(param(p)))

    /** The average of the ramp, used where one colour has to stand in for the whole fill. */
    fun average(): Rgba {
        var r = 0
        var g = 0
        var b = 0
        var a = 0
        for (s in stops) {
            r += s.color.r
            g += s.color.g
            b += s.color.b
            a += s.color.a
        }
        val n = stops.size.coerceAtLeast(1)
        return Rgba(r / n, g / n, b / n, a / n)
    }

    private fun wrap(t: Double): Double {
        if (!t.isFinite()) return 0.0
        return when (spread) {
            SpreadMethod.PAD -> t.coerceIn(0.0, 1.0)
            SpreadMethod.REPEAT -> t - floor(t)
            SpreadMethod.REFLECT -> {
                val u = abs(t) % 2.0
                if (u > 1.0) 2.0 - u else u
            }
        }
    }

    private fun sample(t: Double): Rgba {
        if (stops.isEmpty()) return Rgba(0, 0, 0, 0)
        if (t <= stops.first().offset) return stops.first().color
        if (t >= stops.last().offset) return stops.last().color
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (t > b.offset) continue
            val span = b.offset - a.offset
            val f = if (span <= 1e-12) 0.0 else (t - a.offset) / span
            return Rgba(
                lerp(a.color.r, b.color.r, f),
                lerp(a.color.g, b.color.g, f),
                lerp(a.color.b, b.color.b, f),
                lerp(a.color.a, b.color.a, f),
            )
        }
        return stops.last().color
    }

    private fun lerp(a: Int, b: Int, f: Double): Int = (a + (b - a) * f).toInt().coerceIn(0, 255)

    companion object {

        /** A ramp for [paint], or null when it is a flat colour or has no usable stops. */
        fun of(paint: VectorPaint): GradientRamp? = when (paint) {
            is VectorPaint.Solid -> null
            is VectorPaint.Linear -> linear(paint)
            is VectorPaint.Radial -> radial(paint)
        }

        private fun linear(g: VectorPaint.Linear): GradientRamp? {
            val stops = normalize(g.stops) ?: return null
            val dx = g.x1 - g.x0
            val dy = g.y1 - g.y0
            val lenSq = dx * dx + dy * dy
            // A zero-length linear gradient paints the last stop everywhere, per the spec.
            if (lenSq < 1e-18) return GradientRamp(stops, SpreadMethod.PAD) { 1.0 }
            return GradientRamp(stops, g.spread) { p ->
                ((p.x - g.x0) * dx + (p.y - g.y0) * dy) / lenSq
            }
        }

        /**
         * A radial ramp, as the fraction of the way from the focus to the circle along the ray
         * through the point. With the focus at the centre that reduces to distance over radius,
         * which is the case nearly every file uses.
         */
        private fun radial(g: VectorPaint.Radial): GradientRamp? {
            val stops = normalize(g.stops) ?: return null
            if (g.r <= 1e-12) return GradientRamp(stops, SpreadMethod.PAD) { 1.0 }
            // The spec pulls a focus outside the circle back onto it; just inside keeps it solvable.
            var fx = g.fx
            var fy = g.fy
            val d = hypot(fx - g.cx, fy - g.cy)
            if (d > g.r * 0.99) {
                val k = g.r * 0.99 / d
                fx = g.cx + (fx - g.cx) * k
                fy = g.cy + (fy - g.cy) * k
            }
            if (abs(fx - g.cx) < 1e-9 && abs(fy - g.cy) < 1e-9) {
                return GradientRamp(stops, g.spread) { p -> hypot(p.x - g.cx, p.y - g.cy) / g.r }
            }
            val fcx = fx - g.cx
            val fcy = fy - g.cy
            val c = fcx * fcx + fcy * fcy - g.r * g.r
            return GradientRamp(stops, g.spread) { p ->
                val ux = p.x - fx
                val uy = p.y - fy
                val a = ux * ux + uy * uy
                if (a < 1e-18) {
                    0.0
                } else {
                    val b = 2.0 * (ux * fcx + uy * fcy)
                    val disc = b * b - 4.0 * a * c
                    if (disc < 0.0) 1.0 else 2.0 * a / (-b + sqrt(disc))
                }
            }
        }

        /** Stops sorted, clamped into `[0, 1]` and forced non-decreasing, or null when unusable. */
        private fun normalize(stops: List<GradientStop>): List<GradientStop>? {
            if (stops.isEmpty()) return null
            var last = 0.0
            val out = ArrayList<GradientStop>(stops.size)
            for (s in stops.sortedBy { it.offset }) {
                last = maxOf(last, s.offset.coerceIn(0.0, 1.0))
                out.add(GradientStop(last, s.color))
            }
            return out
        }
    }
}
