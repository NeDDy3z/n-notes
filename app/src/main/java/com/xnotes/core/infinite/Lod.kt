package com.xnotes.core.infinite

/**
 * Geometry decimation levels for the infinite canvas. Content is tessellated once at commit time
 * and drawn every frame, so per-frame cost tracks visible vertex count. Zoomed far out a stroke's
 * samples land on top of each other, and dropping most of them is invisible.
 *
 * A level's [TOLERANCES] entry is the deviation, in content pixels, that its decimation may
 * introduce. At zoom `z` that shows on screen as `tolerance * z` device pixels, so the coarsest
 * level worth using is the largest one still under [SCREEN_ERROR_PX]. Level 0 is the exact
 * geometry and is always valid.
 */
object Lod {

    /** Allowed deviation per level, in content pixels. Index 0 is lossless. */
    val TOLERANCES = doubleArrayOf(0.0, 0.6, 2.5, 10.0)

    /** Levels available, counting the lossless one. */
    val LEVELS: Int get() = TOLERANCES.size

    /** Screen-space error budget, in device pixels. Half a pixel is under the visible threshold. */
    const val SCREEN_ERROR_PX = 0.5

    /** The coarsest level whose screen-space error stays within budget at [zoom]. */
    fun levelFor(zoom: Double): Int {
        if (!zoom.isFinite() || zoom <= 0.0) return 0
        var level = 0
        for (i in 1 until TOLERANCES.size) {
            if (TOLERANCES[i] * zoom <= SCREEN_ERROR_PX) level = i else break
        }
        return level
    }

    fun toleranceFor(level: Int): Double = TOLERANCES[level.coerceIn(0, TOLERANCES.lastIndex)]

    /**
     * The zoom at or above which [level] is too coarse to use. Callers cache geometry per level
     * and swap on a zoom change, so this is the threshold that triggers the swap.
     */
    fun maxZoomFor(level: Int): Double {
        val tol = toleranceFor(level)
        return if (tol <= 0.0) Double.POSITIVE_INFINITY else SCREEN_ERROR_PX / tol
    }

    /**
     * Screen-space width, in device pixels, that a stroke of [contentWidth] draws at [zoom],
     * clamped so a hair of ink never falls below one pixel and shimmers as the view pans.
     */
    fun clampedScreenWidth(contentWidth: Double, zoom: Double): Double =
        maxOf(contentWidth * zoom, MIN_SCREEN_WIDTH_PX)

    /**
     * Alpha scale that pays back the width the [clampedScreenWidth] floor added, so a stroke thinner
     * than a pixel fades instead of fattening. 1.0 once the true width is at or above the floor.
     */
    fun subPixelAlpha(contentWidth: Double, zoom: Double): Double {
        val trueWidth = contentWidth * zoom
        if (!trueWidth.isFinite() || trueWidth >= MIN_SCREEN_WIDTH_PX) return 1.0
        if (trueWidth <= 0.0) return 0.0
        return trueWidth / MIN_SCREEN_WIDTH_PX
    }

    /** Narrowest a stroke is ever rasterized, in device pixels. Below this it fades instead. */
    const val MIN_SCREEN_WIDTH_PX = 1.0
}
