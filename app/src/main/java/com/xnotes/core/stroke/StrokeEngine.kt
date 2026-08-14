package com.xnotes.core.stroke

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw stylus samples into a smooth, variable-width ink ribbon (spec 03).
 * Pure, deterministic and unit-tested against the spec's conformance vectors.
 * All math runs in doubles; only the packed [StrokeGeometry] output is floats.
 */
object StrokeEngine {
    /** EMA low-pass smoothing factor (1.0 = passthrough, ->0 = heavy lag). Per *sample*: the
     *  ribbon smooths per unit of travel instead (see [emaByArc] and [SMOOTH_LEN]), and this is
     *  what that is tuned to reproduce at [REFERENCE_SPACING]. */
    const val ALPHA = 0.5

    /** Below this difference length a sample is degenerate; reuse last tangent. */
    const val MIN_TANGENT_LEN = 1e-6

    /** Below this travel a step carries no arc for [emaByArc] to integrate over. */
    const val MIN_STEP = 1e-9

    /** Floor on the calligraphic direction term so width stays positive. */
    const val MIN_DIRECTION = 0.1

    /** Steepness of the pressure response S-curve (see [logisticEase]). Raw stylus
     *  pressure is reshaped by a logistic before it sets the width: the light and hard
     *  ends move width gently, the mid-range moves it fast, so the small pressure swings
     *  of normal writing produce more visible width variation. 0 keeps the old linear
     *  response; higher = a sharper S. */
    const val PRESSURE_CURVE_K = 8.0

    /** Sample spacing (content px) the smoothing lengths below are tuned at: about what a stylus
     *  reports while writing at normal speed at 100% zoom, through the capture gate. */
    const val REFERENCE_SPACING = 1.5

    /** Low-pass length (content px) for position and pressure: the distance [emaByArc] leaves the
     *  smoothed centreline trailing the raw path by, whatever the spacing. Set to the lag the
     *  per-sample [ALPHA] produced at [REFERENCE_SPACING], `d · (1 - alpha) / alpha`, so ink drawn
     *  at 100% zoom looks as it always has. */
    val SMOOTH_LEN = REFERENCE_SPACING * (1.0 - ALPHA) / ALPHA

    /** Calligraphy pen: the travel that decides the stroke's **head**, the one stretch no causal
     *  rule can judge, because at pen-down the samples that would tell a jitter from a genuinely
     *  broad opening have not arrived yet. Every sample inside it is drawn at the thinnest heading
     *  the window holds (see [headDirection]). Long enough to outvote a pen-down flick, short enough
     *  that a stroke which really starts broad opens broad. It is the dot threshold too: a finished
     *  stroke that never fills the window is a tap. */
    const val HEAD_LEN = 8.0

    /** Calligraphy pen: the travel the nib takes to widen across its whole range, thin face to broad
     *  (see [nibDirection]). A rate, not a threshold: a stray sample only buys its own fraction of
     *  it, while a real downstroke reaches full width within a letter's height. */
    const val OPEN_LEN = 8.0

    /** [OPEN_LEN]'s counterpart for thinning, so the two directions can be tuned apart: ink that
     *  swells too eagerly reads as a blot, ink that thins too slowly reads as a smear. */
    const val CLOSE_LEN = 8.0

    /** The nib's arc constants are hand gestures, so they are quoted at 100% zoom and scaled by the
     *  stroke's [smoothScale] to the page. Without it a nib drawn at 4x had to be dragged four times
     *  as far across the glass before it would thicken, since the page it was writing on was a
     *  quarter the size. */
    fun headLen(smoothScale: Double): Double = HEAD_LEN * max(smoothScale, 0.0)

    /** [OPEN_LEN] at the stroke's draw zoom; see [headLen]. */
    fun openLen(smoothScale: Double): Double = OPEN_LEN * max(smoothScale, 0.0)

    /** [CLOSE_LEN] at the stroke's draw zoom; see [headLen]. */
    fun closeLen(smoothScale: Double): Double = CLOSE_LEN * max(smoothScale, 0.0)

    /** Calligraphy pen: the direction-y a dot is built at. Past the broad face's 1.0 on purpose,
     *  so a dot lands slightly bigger than the thickest line and reads as a deliberate mark. */
    const val DOT_DIR_Y = 1.5

    /** Speed pen: dp/ms at/below which the line stays full width, and the speed
     *  at/above which it reaches its thinnest (0 and ≈3.75 in/s of hand travel).
     *  Measuring in dp — not page pixels — makes the effect independent of both zoom
     *  and screen density; see [speedFactors] and the per-stroke speed scale. */
    const val SPEED_LO = 0.0
    const val SPEED_HI = 0.6

    /** Speed pen: half the duration (ms) of the centred window the nib's speed is measured over.
     *  Speed is the arc length covered across `±this` ms divided by that span. A fixed *time*
     *  base (not a fixed sample count, which collapses to a point where the pen crawls and the
     *  distance-gated samples bunch up) keeps the estimate steady and lets the faster ink on
     *  either side of a brief corner pause dilute it, instead of the width ballooning into a
     *  blob there. The window slides inward at the stroke's ends so its first and last points
     *  still average a full span rather than the at-rest tip. */
    const val SPEED_WINDOW_MS = 40.0

    /** Speed pen: minimum per-segment dt (ms) so a duplicate-timestamp pair can't
     *  divide by ~zero and spike the speed. */
    const val MIN_DT = 1.0

    /** Taper pen: strokes shorter than this arc are left un-tapered, so a quick tick doesn't
     *  collapse to nothing. Quoted at 100% zoom and scaled by the stroke's draw zoom, since what
     *  makes a tick a tick is how far the hand went, not how much page it landed on. */
    const val TAPER_MIN_LEN = 8.0

    /** Pens that hold their ends ([holdEndPressure]) do so over this many samples at each end,
     *  enough to cover the EMA pressure ramp so the swept end disc meets the line at the body width. */
    const val CAP_HOLD_SAMPLES = 4

    /** Taper falloff shape: the tail ease is the [logisticEase] sigmoid clipped to its
     *  `[TAPER_TAIL, 1 - TAPER_TAIL]` band, since a true sigmoid only reaches 0 and 1 at +/-inf;
     *  the clipped band is then stretched back to a real point and full width. A smaller tail
     *  hugs the rails harder: a longer thin hold near the tip, then a quicker opening, than the
     *  old cubic smoothstep. [TAPER_CURVE_K] is the logistic steepness that spans exactly that
     *  band (sigma(+-k/2) = 1 - TAPER_TAIL / TAPER_TAIL). */
    const val TAPER_TAIL = 0.01
    val TAPER_CURVE_K = 2.0 * ln((1.0 - TAPER_TAIL) / TAPER_TAIL)

    /** Holds the pen/highlighter's first/last [CAP_HOLD_SAMPLES] samples up to the settled pressure
     *  just inside each end (in place), so a light pen-down/up can't shrink the swept end disc
     *  thinner than the line. Only raises width, never lowers it, so the heavier middle and any
     *  deliberate mid-stroke pressure dip are untouched. The window halves on very short strokes so
     *  head and tail can't cross. A light lift-off is the same signal as a pinch, so these pens end
     *  full and round rather than easing to a thin tip. */
    private fun holdEndPressure(p: DoubleArray) {
        val n = p.size
        val w = min(CAP_HOLD_SAMPLES, (n - 1) / 2)
        if (w < 1) return
        val headFloor = p[w]
        for (i in 0 until w) if (p[i] < headFloor) p[i] = headFloor
        val tailFloor = p[n - 1 - w]
        for (i in n - w until n) if (p[i] < tailFloor) p[i] = tailFloor
    }

    /** One-pole IIR low-pass (exponential moving average). */
    fun ema(values: DoubleArray, alpha: Double = ALPHA): DoubleArray {
        if (values.isEmpty()) return values
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        }
        return out
    }

    /** [ema] over a boxed list — the spec-vector form; [build] runs on the array one. */
    fun ema(values: List<Double>, alpha: Double = ALPHA): List<Double> =
        ema(values.toDoubleArray(), alpha).asList()

    /**
     * The same one-pole low-pass as [ema], measured in travel rather than in samples: the
     * continuous filter `dy/ds = (x(s) - y) / lambda` over arc length `s`, integrated exactly
     * across each step with the input read as a straight line between the two samples. [steps]
     * holds each sample's distance from the one before it; `steps[0]` is unused.
     *
     * This is what lets a committed stroke match the wet one. Pen-up sample reduction
     * ([StrokeSimplify]) changes the spacing, and a fixed per-sample factor turns that into a
     * different curve: its lag is one sample spacing, so a thinned stroke trails less and cuts its
     * corners deeper than the one that was drawn. Here the lag is [lambda] whatever the spacing,
     * so dropping a sample leaves the curve where it was. It also makes the ink independent of the
     * pen's report rate, and of how fast the hand was moving when the samples were spaced out.
     *
     * Reading the input as a line across the step rather than as a constant is what buys the last
     * of it. A constant would hold each sample over the whole step it ends, which biases the lag by
     * half a spacing, and half a spacing is exactly the quantity the reduction changes.
     */
    fun emaByArc(values: DoubleArray, steps: DoubleArray, lambda: Double): DoubleArray {
        if (values.isEmpty()) return values
        if (lambda <= 0.0) return values.copyOf() // no smoothing length: pass the samples through
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            val d = steps[i]
            if (d <= MIN_STEP) {
                out[i] = out[i - 1] // the pen did not move: no arc to integrate over
                continue
            }
            val decay = exp(-d / lambda)
            val slope = (values[i] - values[i - 1]) * lambda * (1.0 - decay) / d
            out[i] = decay * out[i - 1] + values[i] - decay * values[i - 1] - slope
        }
        return out
    }

    /**
     * Half-width at a point (spec 03 step 5), given smoothed [pressure] and the
     * tangent's y-component [ty]. The pure-pressure half-width (caps and the
     * single-sample dot) uses `ty = 0`.
     */
    fun halfWidth(
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        pressure: Double,
        ty: Double,
    ): Double {
        val pEff = if (pressureEnabled) logisticEase(pressure, PRESSURE_CURVE_K) else 1.0
        val wBase = baseWidth * (m + (1 - m) * pEff)
        val direction = max(1 + ds * ty, MIN_DIRECTION)
        return wBase * direction / 2.0
    }

    /**
     * Normalized logistic S-curve on `[0, 1]`, centred at 0.5 and rescaled so the endpoints
     * are exact (`0 -> 0`, `1 -> 1`) while only the middle bends. [k] sets the steepness: the
     * curve spans the logistic's `[sigma(-k/2), sigma(k/2)]` band, so a larger [k] both steepens
     * the middle and clips the rails nearer 0 and 1. `k <= 0` is the identity (a linear ramp).
     * Shared by the pressure response ([PRESSURE_CURVE_K]) and the taper ease ([TAPER_CURVE_K]).
     */
    fun logisticEase(x: Double, k: Double): Double {
        if (k <= 0.0) return x
        val lo = 1.0 / (1.0 + exp(k * 0.5))
        val hi = 1.0 / (1.0 + exp(-k * 0.5))
        val raw = 1.0 / (1.0 + exp(-k * (x - 0.5)))
        return (raw - lo) / (hi - lo)
    }

    /** Hermite smoothstep: 0 below [lo], 1 above [hi], an S-curve between. */
    private fun smoothstep(lo: Double, hi: Double, x: Double): Double {
        if (hi <= lo) return if (x >= hi) 1.0 else 0.0
        val t = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * Per-point width multipliers in `[1 − speedStrength, 1]` for the **speed pen**:
     * the faster the nib travels across the page, the thinner the line (ink has less
     * time to lay down). Speed at point `i` is the **arc length of the raw samples over a
     * centred time window** of `±[SPEED_WINDOW_MS]` ms divided by that span, in dp/ms, where
     * [speedScale] (zoom ÷ density, captured at pen-down) converts page pixels to dp so the
     * effect is zoom- and device-independent. It reads the raw sample motion, not the smoothed
     * centerline, so the position low-pass can't compress the start or cut a corner short and
     * read a false slow-down there. Summing distance and time over a fixed *time* span (not a
     * fixed sample count) rejects per-sample jitter and keeps slow corners and ends from
     * collapsing the window onto themselves and ballooning the width. Returns all-`1.0` when off
     * or the samples carry no usable timing. [steps] is [build]'s raw per-sample travel.
     */
    fun speedFactors(
        samples: List<Sample>,
        steps: DoubleArray,
        speedStrength: Double,
        speedScale: Double,
    ): DoubleArray {
        val n = samples.size
        val out = DoubleArray(n) { 1.0 }
        if (speedStrength <= 0.0 || n < 2) return out
        val t0 = samples.first().t
        val tN = samples.last().t
        if (tN - t0 <= 0.0) return out
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + steps[i]
        val half = SPEED_WINDOW_MS
        var lo = 0
        var hi = 0
        for (i in 0 until n) {
            // Centre a fixed-duration window on this sample's time; if it runs past either end of
            // the stroke, slide it inward so the span stays ~2·half rather than shrinking to a point.
            var a = samples[i].t - half
            var b = samples[i].t + half
            if (a < t0) { b += t0 - a; a = t0 }
            if (b > tN) { a -= b - tN; b = tN; if (a < t0) a = t0 }
            while (lo < i && samples[lo].t < a) lo++
            while (hi < n - 1 && samples[hi + 1].t <= b) hi++
            // Always span at least one segment so a window that falls between two far-apart slow
            // samples reads a real speed instead of a zero-length divide.
            var l = lo
            var h = hi
            if (h <= l) { if (h < n - 1) h++ else l-- }
            val dist = (cum[h] - cum[l]) * speedScale
            val dt = max(samples[h].t - samples[l].t, MIN_DT)
            out[i] = 1.0 - speedStrength * smoothstep(SPEED_LO, SPEED_HI, dist / dt)
        }
        return out
    }

    /**
     * Per-point width multipliers in `[taperMinFactor, 1]` for the **taper pen**: the width eases
     * across the **whole stroke**, full at the head and easing down to [taperMinFactor] of full at
     * the tip (a sharp point when that is 0). Longer strokes just stretch the same profile. Returns
     * all-`1.0` when the stroke is too short ([TAPER_MIN_LEN]). [cum] is cumulative arc along the
     * smoothed centreline, from [build].
     */
    fun taperFactors(
        cum: DoubleArray,
        taperMinFactor: Double,
        smoothScale: Double = 1.0,
    ): DoubleArray {
        val n = cum.size
        val out = DoubleArray(n) { 1.0 }
        if (n < 2) return out
        val total = cum[n - 1]
        if (total < TAPER_MIN_LEN * max(smoothScale, 0.0)) return out
        for (i in 0 until n) {
            // Fractional arc position: 1 at the head, easing to 0 at the tip. The whole stroke is
            // the taper; the tip bottoms out at taperMinFactor of full instead of a sharp point.
            val edge = (total - cum[i]) / total
            out[i] = taperMinFactor + (1.0 - taperMinFactor) * logisticEase(edge, TAPER_CURVE_K)
        }
        return out
    }

    /**
     * The calligraphy nib's **head**: the thinnest heading over the stroke's first [headLen] of
     * travel, paired with the last sample inside that window. Every sample in the window takes that
     * one value, so a pen-down jitter loses to the run that follows it inside the same window, and a
     * stroke that really starts broad keeps its broad head because its own minimum is broad.
     *
     * A minimum rather than a mean because the nib is asymmetric: a head that comes out too thin is
     * a soft error, a head that comes out too thick is a blob the writer has already seen.
     *
     * Until the window fills there is no answer, so a live stroke draws the safe value (thin) and is
     * rewritten once, when it fills. A *finished* stroke that never fills it is a dot and takes
     * [DOT_DIR_Y]. [cum] is cumulative arc along the smoothed centreline; [headLen] is already
     * scaled to the stroke's draw zoom.
     */
    private fun headDirection(
        ty: DoubleArray,
        cum: DoubleArray,
        headLen: Double,
        finished: Boolean,
    ): Pair<Double, Int> {
        val k = cum.indexOfFirst { it >= headLen }
        if (k < 0) return (if (finished) DOT_DIR_Y else -1.0) to ty.lastIndex
        var m = ty[0]
        for (i in 1..k) if (ty[i] < m) m = ty[i]
        return m to k
    }

    /**
     * The calligraphy nib's direction channel: a slew limiter over the raw heading. Widening is
     * earned over [openLen] of travel and thinning is spent over [closeLen], so the width can never
     * change faster than the writer has earned. This bounds the *rate*, not the duration: a short
     * heading change is flattened in proportion to how sharp it is, and a gentle one passes through.
     *
     * The channel spans 2.0 (thin face -1 to broad face +1), so the per-sample cap is
     * `step · 2 / rate`, and travelling exactly [openLen] at full cap carries the width from one
     * extreme to the other. Nothing here reads time: a fast stroke reports sparser samples, each
     * step is longer, and each gets a proportionally larger allowance, so the same path draws the
     * same widths however fast it was written.
     *
     * Samples up to [holdUntil] are pinned to [seed], which is the head: a rate cannot decide the
     * start, having no travel behind it to measure. [steps] is travel along the smoothed centreline,
     * and both lengths are already scaled to the stroke's draw zoom.
     */
    private fun nibDirection(
        ty: DoubleArray,
        steps: DoubleArray,
        openLen: Double,
        closeLen: Double,
        seed: Double,
        holdUntil: Int,
    ): DoubleArray {
        val out = DoubleArray(ty.size)
        var d = seed
        for (i in ty.indices) {
            if (i <= holdUntil) {
                out[i] = seed
                continue
            }
            val step = if (i == 0) 0.0 else steps[i]
            val rate = if (ty[i] > d) openLen else closeLen
            val limit = if (rate > 0.0) step * 2.0 / rate else Double.MAX_VALUE
            d += (ty[i] - d).coerceIn(-limit, limit)
            out[i] = d
        }
        return out
    }

    /**
     * Builds [StrokeGeometry] from [samples] and the style fields. [speedStrength]
     * and [taperEnabled] default to off, in which case the output is identical to
     * the four-field pen/calligraphy pipeline (spec 03 conformance).
     */
    fun build(
        samples: List<Sample>,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        speedStrength: Double = 0.0,
        taperEnabled: Boolean = false,
        taperMinFactor: Double = 0.0,
        speedScale: Double = 1.0,
        smooth: Boolean = true,
        holdEnds: Boolean = false,
        finished: Boolean = true,
        smoothScale: Double = 1.0,
    ): StrokeGeometry {
        val n = samples.size
        if (n == 0) return StrokeGeometry.EMPTY

        val rawX = DoubleArray(n)
        val rawY = DoubleArray(n)
        val rawP = DoubleArray(n)
        for (i in 0 until n) {
            val s = samples[i]
            rawX[i] = s.x
            rawY[i] = s.y
            rawP[i] = s.pressure
        }

        // Travel between consecutive samples: what the low-pass measures itself against, so the
        // smoothing is set by the path and not by how many samples describe it.
        val steps = DoubleArray(n)
        for (i in 1 until n) steps[i] = hypot(rawX[i] - rawX[i - 1], rawY[i] - rawY[i - 1])
        val smoothLen = SMOOTH_LEN * max(smoothScale, 0.0)

        // 2. Smooth each channel independently. Straight-line strokes skip the position low-pass
        //    so the ribbon spans the raw samples exactly (EMA would pull a 2-point line's far end
        //    toward the midpoint, leaving it short of the pointer).
        val sx = if (smooth) emaByArc(rawX, steps, smoothLen) else rawX
        val sy = if (smooth) emaByArc(rawY, steps, smoothLen) else rawY
        // The pens that hold their ends (pen, highlighter) land and lift light, so the swept end
        // disc would shrink to a thin tip; hold the body width out to each end so it meets the line
        // at full width. The other ribbon pens take their ends at the raw pressure.
        val sp = emaByArc(rawP, steps, smoothLen)
        if (holdEnds && pressureEnabled) holdEndPressure(sp)

        fun hw(i: Int, ty: Double) = halfWidth(baseWidth, pressureEnabled, m, ds, sp[i], ty)

        // 3. Single sample -> a filled dot: one swept disc at the pure-pressure half-width. A
        //    finished calligraphy tap takes the dot width (past the broad face) so it stays visible.
        if (n == 1) {
            val h = hw(0, if (finished && ds > 0.0) DOT_DIR_Y else 0.0)
            return StrokeGeometry(
                FloatArray(0),
                floatArrayOf(sx[0].toFloat(), sy[0].toFloat()),
                floatArrayOf(h.toFloat()),
            )
        }

        // 4. Per-point unit tangent via finite differences.
        var lastTx = 1.0
        var lastTy = 0.0
        val tx = DoubleArray(n)
        val ty = DoubleArray(n)
        for (i in 0 until n) {
            val dx: Double
            val dy: Double
            when (i) {
                0 -> { dx = sx[1] - sx[0]; dy = sy[1] - sy[0] }
                n - 1 -> { dx = sx[i] - sx[i - 1]; dy = sy[i] - sy[i - 1] }
                else -> { dx = sx[i + 1] - sx[i - 1]; dy = sy[i + 1] - sy[i - 1] }
            }
            val len = hypot(dx, dy)
            if (len < MIN_TANGENT_LEN) {
                tx[i] = lastTx
                ty[i] = lastTy
            } else {
                tx[i] = dx / len
                ty[i] = dy / len
                lastTx = tx[i]
                lastTy = ty[i]
            }
        }

        // Travel along the smoothed centreline and its running total: the path the ink follows, and
        // what every arc-length rule below is measured in. Only the nib and the taper read it.
        val needArc = ds > 0.0 || taperEnabled
        val dirSteps = DoubleArray(if (needArc) n else 0)
        val cum = DoubleArray(dirSteps.size)
        if (needArc) {
            for (i in 1 until n) {
                dirSteps[i] = hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
                cum[i] = cum[i - 1] + dirSteps[i]
            }
        }

        // Optional width multipliers: speed thins fast travel, taper points the ends. Neither pen is
        // the common one, so the arrays are built only for the strokes that use them.
        val sf = if (speedStrength > 0.0) speedFactors(samples, steps, speedStrength, speedScale) else null
        val tf = if (taperEnabled) taperFactors(cum, taperMinFactor, smoothScale) else null

        // Calligraphy: the tangent-y that sets nib width, in two pieces. The head is decided once
        // over the first HEAD_LEN of travel and pinned flat across it (a pen-down jitter loses to the
        // run that follows it, and a finished stroke too short to fill the window is a dot). After
        // that a slew limiter caps how fast the width may change per px travelled, OPEN_LEN to widen
        // and CLOSE_LEN to thin, so a stray sample only buys its own fraction of the range. Nothing
        // here looks ahead past the head, and nothing reads time. Orientation still follows the true
        // tangent; only the width magnitude is held back. A no-op when ds = 0.
        val dirY = if (ds > 0.0) {
            val (head, holdUntil) = headDirection(ty, cum, headLen(smoothScale), finished)
            nibDirection(ty, dirSteps, openLen(smoothScale), closeLen(smoothScale), head, holdUntil)
        } else null

        // 5–8. Half-widths, normals, and the two ribbon edges, packed straight into the output:
        // the outline is the left edge in order plus the right edge reversed (one closed polygon).
        // No separate end caps: the swept brush disc at each sample (the head and tail included)
        // already rounds every end and join, so [holdEnds] only shapes the end half-widths.
        val centerline = FloatArray(2 * n)
        val halfWidths = FloatArray(n)
        val outline = FloatArray(4 * n)
        for (i in 0 until n) {
            // Clamped to [-1, DOT_DIR_Y], not to [-1, 1]: a dot is 1.5 on purpose, past the broad
            // face, so a cap at 1.0 would quietly shrink every tap.
            val dir = if (dirY != null) dirY[i].coerceIn(-1.0, DOT_DIR_Y) else ty[i]
            var h = hw(i, dir)
            if (sf != null) h *= sf[i]
            if (tf != null) h *= tf[i]
            halfWidths[i] = h.toFloat()
            centerline[2 * i] = sx[i].toFloat()
            centerline[2 * i + 1] = sy[i].toFloat()
            val nx = -ty[i] // tangent rotated 90°, already unit length
            val ny = tx[i]
            outline[2 * i] = (sx[i] - nx * h).toFloat()
            outline[2 * i + 1] = (sy[i] - ny * h).toFloat()
            val j = 2 * n - 1 - i
            outline[2 * j] = (sx[i] + nx * h).toFloat()
            outline[2 * j + 1] = (sy[i] + ny * h).toFloat()
        }
        return StrokeGeometry(outline, centerline, halfWidths)
    }
}
