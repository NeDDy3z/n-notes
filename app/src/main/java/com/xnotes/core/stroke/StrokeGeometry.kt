package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt

/**
 * One captured stylus point, page-local; `pressure` in `[0, 1]`. [t] is the
 * milliseconds elapsed since the stroke's first sample (0 for that first one),
 * used only by velocity-aware tools (the speed pen); 0 everywhere else.
 */
data class Sample(val x: Double, val y: Double, val pressure: Double, val t: Double = 0.0) {
    val pos: Pt get() = Pt(x, y)
}

/**
 * The geometry derived from a stroke's samples (spec 03), packed into primitive float arrays —
 * a dense document caches millions of these points, so per-point objects or boxing would multiply
 * heap several-fold (floats are far past render precision; the renderer draws in floats anyway).
 * The ink is painted by sweeping a brush disc down the [centerline] at the per-point [halfWidths]
 * (Renderer.fillDiskRibbon), so caps and joins round on every pen; [outline] is that same ribbon
 * as one closed polygon, kept for the neon bloom and hit-testing. Compared by identity (a rebuild
 * is a new instance), like the model items.
 */
class StrokeGeometry(
    /** Closed ribbon polygon, interleaved x,y: the left edge in order, then the right reversed. */
    val outline: FloatArray,
    /** Smoothed centerline, interleaved x,y; one point per input sample. */
    val centerline: FloatArray,
    /** Brush disc radius at each centerline point. */
    val halfWidths: FloatArray,
) {
    /** Number of centerline points (one per input sample). */
    val pointCount get() = halfWidths.size

    /** Number of outline vertices (2 per centerline point when the ribbon has a body). */
    val outlineCount get() = outline.size / 2

    fun cx(i: Int): Double = centerline[2 * i].toDouble()
    fun cy(i: Int): Double = centerline[2 * i + 1].toDouble()
    fun hw(i: Int): Double = halfWidths[i].toDouble()

    companion object {
        val EMPTY = StrokeGeometry(FloatArray(0), FloatArray(0), FloatArray(0))
    }
}
