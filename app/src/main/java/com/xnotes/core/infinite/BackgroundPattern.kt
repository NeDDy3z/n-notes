package com.xnotes.core.infinite

/**
 * Scale selection and phase for the infinite canvas ruling. The pattern is drawn procedurally in a
 * fragment shader rather than as geometry, so all the shader needs is a period and an offset, both
 * already in device pixels. That is what this computes.
 *
 * Two things make it more than a division. First, a fixed content-space period would be a hair-thin
 * blur zoomed out and a single line zoomed in, so the period doubles or halves in powers of two
 * until it lands in a comfortable on-screen band, and the finer of the two neighbouring levels
 * fades in as it becomes legible. Second, the shader works in float, and at extreme scroll offsets
 * a content coordinate does not survive that; but a periodic pattern only ever needs
 * `scroll mod period`, which stays small and is computed here in double.
 */
object BackgroundPattern {

    /** Smallest on-screen period, in device pixels, the base level is allowed to shrink to. */
    const val MIN_PERIOD_PX = 14.0

    /** Ruling line thickness in device pixels, so the grid reads the same at every zoom. */
    const val LINE_WIDTH_PX = 1.0

    /** Dot radius in device pixels. */
    const val DOT_RADIUS_PX = 1.4

    /** Largest number of doublings or halvings applied, so an absurd zoom cannot spin. */
    private const val MAX_STEPS = 64

    /**
     * The resolved ruling for one frame. Periods and phases are device pixels, so the shader never
     * sees a content coordinate and never has to be precise at world scale.
     *
     * A pattern line sits wherever the content coordinate is a whole multiple of the period, so the
     * device-pixel distance from a fragment at device `x` to the nearest line is
     * `min(q, periodPx - q)` with `q = mod(x + phaseXPx, periodPx)`.
     */
    data class Resolved(
        val periodPx: Double,
        val phaseXPx: Double,
        val phaseYPx: Double,
        /** The half-period level drawn beneath the base one, faded in by [subdivisionAlpha]. */
        val subPeriodPx: Double,
        val subPhaseXPx: Double,
        val subPhaseYPx: Double,
        val subdivisionAlpha: Double,
    )

    /**
     * Power-of-two multiple of [spacing] whose on-screen period lands in
     * `[MIN_PERIOD_PX, 2 * MIN_PERIOD_PX)`. Returns the multiplier rather than the period so the
     * caller keeps the content-space period exact for the phase computation.
     */
    fun levelMultiplier(spacing: Double, zoom: Double): Double {
        if (!spacing.isFinite() || spacing <= 0.0 || !zoom.isFinite() || zoom <= 0.0) return 1.0
        val base = spacing * zoom
        if (!base.isFinite() || base <= 0.0) return 1.0
        var k = 1.0
        var steps = 0
        while (base * k < MIN_PERIOD_PX && steps < MAX_STEPS) {
            k *= 2.0
            steps++
        }
        while (base * (k / 2.0) >= MIN_PERIOD_PX && steps < MAX_STEPS) {
            k /= 2.0
            steps++
        }
        return k
    }

    /**
     * `scroll mod period`, always in `[0, period)`, computed in double so a canvas panned a million
     * pixels out still lines its ruling up exactly.
     */
    fun phase(scroll: Double, period: Double): Double {
        if (!period.isFinite() || period <= 0.0 || !scroll.isFinite()) return 0.0
        val m = scroll % period
        return if (m < 0.0) m + period else m
    }

    /**
     * How strongly the half-period subdivision shows: invisible when the base period is at its
     * smallest, full by the time the base period has doubled. The ramp has to reach full strength
     * exactly where [levelMultiplier] flips, because at that moment the subdivision becomes the new
     * base level; anything less than full there would pop.
     */
    fun subdivisionAlpha(periodPx: Double): Double {
        if (!periodPx.isFinite()) return 0.0
        return ((periodPx - MIN_PERIOD_PX) / MIN_PERIOD_PX).coerceIn(0.0, 1.0)
    }

    /** Everything the fragment shader needs for one frame. */
    fun resolve(background: CanvasBackground, viewport: CanvasViewport): Resolved =
        resolve(background, viewport.zoom, viewport.scrollX, viewport.scrollY)

    /** [resolve] from a snapshot of the view, which is what the render thread actually holds. */
    fun resolve(background: CanvasBackground, zoom: Double, scrollX: Double, scrollY: Double): Resolved {
        val spacing = background.clampedSpacing
        val contentPeriod = spacing * levelMultiplier(spacing, zoom)
        val subContentPeriod = contentPeriod / 2.0
        val periodPx = contentPeriod * zoom
        return Resolved(
            periodPx = periodPx,
            phaseXPx = phase(scrollX, contentPeriod) * zoom,
            phaseYPx = phase(scrollY, contentPeriod) * zoom,
            subPeriodPx = periodPx / 2.0,
            subPhaseXPx = phase(scrollX, subContentPeriod) * zoom,
            subPhaseYPx = phase(scrollY, subContentPeriod) * zoom,
            subdivisionAlpha = subdivisionAlpha(periodPx),
        )
    }

    /** Device-pixel distance from device coordinate [x] to the nearest ruling line. */
    fun distanceToLine(x: Double, periodPx: Double, phasePx: Double): Double {
        if (!(periodPx > 0.0)) return Double.MAX_VALUE
        var q = (x + phasePx) % periodPx
        if (q < 0.0) q += periodPx
        return minOf(q, periodPx - q)
    }
}
