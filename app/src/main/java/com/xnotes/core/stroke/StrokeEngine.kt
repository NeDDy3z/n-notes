package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw stylus samples into a smooth, variable-width ink ribbon (spec 03).
 * Pure, deterministic and unit-tested against the spec's conformance vectors.
 */
object StrokeEngine {
    /** EMA low-pass smoothing factor (1.0 = passthrough, ->0 = heavy lag). */
    const val ALPHA = 0.5

    /** Below this difference length a sample is degenerate; reuse last tangent. */
    const val MIN_TANGENT_LEN = 1e-6

    /** Floor on the calligraphic direction term so width stays positive. */
    const val MIN_DIRECTION = 0.1

    /** Steepness of the pressure response S-curve (see [logisticEase]). Raw stylus
     *  pressure is reshaped by a logistic before it sets the width: the light and hard
     *  ends move width gently, the mid-range moves it fast, so the small pressure swings
     *  of normal writing produce more visible width variation. 0 keeps the old linear
     *  response; higher = a sharper S. */
    const val PRESSURE_CURVE_K = 8.0

    /** Calligraphy pen: a heavier low-pass on the direction channel (the tangent's y that
     *  drives nib width) than [ALPHA] (position), so the width eases between thick and thin
     *  as the stroke curves instead of snapping when the tangent turns. The speed pen smooths
     *  its width the same spirit via a windowed velocity (see [speedFactors]); only the width
     *  magnitude is smoothed, not the ribbon's orientation. */
    const val DIR_ALPHA = 0.25

    /** Calligraphy pen: the broad/thick face of the nib is only allowed in once the stroke has held
     *  that heading for this many content px of travel (see [confirmThickening] in [build]). Long
     *  enough to outvote a pen-down or lift-off jitter or a one/two-pixel wobble, short enough that a
     *  real downstroke still swells almost at once. */
    const val DIR_CONFIRM_LEN = 8.0

    /** Calligraphy pen: a *finished* stroke whose whole arc is at most this many content px is a
     *  dot, and takes the nib's broad face outright — the thin face would leave a tap nearly
     *  invisible (a dot can never travel far enough to confirm a thick heading). Judged only once
     *  the pen has lifted ([build]'s `finished`), so the live preview never opens thick. */
    const val DOT_MAX_LEN = 5.0

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

    /** Taper pen: strokes shorter than this (page px of arc length) are left
     *  un-tapered, so a quick tick doesn't collapse to nothing. */
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
     *  just inside each end, so a light pen-down/up can't shrink the swept end disc thinner than the
     *  line. Only raises width, never lowers it, so the heavier middle and any deliberate mid-stroke
     *  pressure dip are untouched. The window halves on very short strokes so head and tail can't
     *  cross. A light lift-off is the same signal as a pinch, so these pens end full and round
     *  rather than easing to a thin tip. */
    private fun holdEndPressure(p: List<Double>): List<Double> {
        val n = p.size
        val w = min(CAP_HOLD_SAMPLES, (n - 1) / 2)
        if (w < 1) return p
        val out = p.toDoubleArray()
        val headFloor = p[w]
        for (i in 0 until w) if (out[i] < headFloor) out[i] = headFloor
        val tailFloor = p[n - 1 - w]
        for (i in n - w until n) if (out[i] < tailFloor) out[i] = tailFloor
        return out.asList()
    }

    /** One-pole IIR low-pass (exponential moving average). */
    fun ema(values: List<Double>, alpha: Double = ALPHA): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        }
        return out.asList()
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
     * or the samples carry no usable timing.
     */
    fun speedFactors(samples: List<Sample>, speedStrength: Double, speedScale: Double): List<Double> {
        val n = samples.size
        if (speedStrength <= 0.0 || n < 2) return List(n) { 1.0 }
        val t0 = samples.first().t
        val tN = samples.last().t
        if (tN - t0 <= 0.0) return List(n) { 1.0 }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + (samples[i].pos - samples[i - 1].pos).length()
        val half = SPEED_WINDOW_MS
        var lo = 0
        var hi = 0
        return (0 until n).map { i ->
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
            1.0 - speedStrength * smoothstep(SPEED_LO, SPEED_HI, dist / dt)
        }
    }

    /**
     * Per-point width multipliers in `[taperMinFactor, 1]` for the **taper pen**: the width eases
     * across the **whole stroke**, full at the head and easing down to [taperMinFactor] of full at
     * the tip (a sharp point when that is 0). Longer strokes just stretch the same profile. Returns
     * all-`1.0` when [taperEnabled] is false or the stroke is too short ([TAPER_MIN_LEN]).
     */
    fun taperFactors(centers: List<Pt>, taperEnabled: Boolean, taperMinFactor: Double): List<Double> {
        val n = centers.size
        if (!taperEnabled || n < 2) return List(n) { 1.0 }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + (centers[i] - centers[i - 1]).length()
        val total = cum[n - 1]
        if (total < TAPER_MIN_LEN) return List(n) { 1.0 }
        return (0 until n).map { i ->
            // Fractional arc position: 1 at the head, easing to 0 at the tip. The whole stroke is
            // the taper; the tip bottoms out at taperMinFactor of full instead of a sharp point.
            val edge = (total - cum[i]) / total
            taperMinFactor + (1.0 - taperMinFactor) * logisticEase(edge, TAPER_CURVE_K)
        }
    }

    /** Confirms a calligraphy nib's thick (high direction-y) runs with a morphological opening over
     *  an arc-length [window]: an erosion (trailing-window minimum) drops any thick run shorter than
     *  the window — a jitter, or a stray sample as the pen lands or lifts — back to thin, then a
     *  dilation (leading-window maximum) grows every run that survived back to its full length. So a
     *  real downstroke is thick along its whole length, including the lead-in the erosion shaved off,
     *  not only after the window has passed; a brief spike is gone for good. The path before the
     *  pen-down counts as the thin extreme, so a stroke that *starts* broad is confirmed exactly like
     *  one that turns broad mid-way. A drop is never delayed, so the line still thins the instant the
     *  stroke turns toward the nib edge. */
    private fun confirmThickening(ty: List<Double>, centers: List<Pt>, window: Double): List<Double> {
        val n = ty.size
        if (n < 2) return ty
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + (centers[i] - centers[i - 1]).length()
        // Erosion: the trailing-window minimum, so a thick value survives only where it has held for
        // the whole window back. Before pen-down (the window underruns the start) the path counts as
        // the thin extreme (-1), so a thick pen-down must also hold for the window before it wins.
        val eroded = DoubleArray(n)
        for (i in 0 until n) {
            var v = if (cum[i] < window) -1.0 else ty[i]
            var j = i
            while (j >= 0 && cum[i] - cum[j] <= window) { if (ty[j] < v) v = ty[j]; j-- }
            eroded[i] = v
        }
        // Dilation: the leading-window maximum, so each surviving run grows forward over the lead-in
        // the erosion ate, ending up thick along its full original length.
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var v = eroded[i]
            var j = i
            while (j < n && cum[j] - cum[i] <= window) { if (eroded[j] > v) v = eroded[j]; j++ }
            out[i] = v
        }
        // Start floor: the dilation's forward window can't always reach back over a sparse first
        // sample to restore the lead, so once a full window has been travelled, floor everything
        // before it at that first confirmed value (the opening's minimum over the window). A run
        // that held the broad heading the whole way lifts the floor to thick; a jitter leaves it
        // thin; a mid/thin start keeps its own width.
        val confirm = cum.indexOfFirst { it >= window }
        if (confirm > 0) {
            val floor = eroded[confirm]
            for (i in 0 until confirm) if (out[i] < floor) out[i] = floor
        }
        return out.asList()
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
    ): StrokeGeometry {
        val n = samples.size
        if (n == 0) return StrokeGeometry.EMPTY

        // 2. Smooth each channel independently. Straight-line strokes skip the position low-pass
        //    so the ribbon spans the raw samples exactly (EMA would pull a 2-point line's far end
        //    toward the midpoint, leaving it short of the pointer).
        val sx = if (smooth) ema(samples.map { it.x }) else samples.map { it.x }
        val sy = if (smooth) ema(samples.map { it.y }) else samples.map { it.y }
        // The pens that hold their ends (pen, highlighter) land and lift light, so the swept end
        // disc would shrink to a thin tip; hold the body width out to each end so it meets the line
        // at full width. The other ribbon pens take their ends at the raw pressure.
        val sp = ema(samples.map { it.pressure }).let {
            if (holdEnds && pressureEnabled) holdEndPressure(it) else it
        }
        val centers = (0 until n).map { Pt(sx[it], sy[it]) }

        fun hw(i: Int, ty: Double) = halfWidth(baseWidth, pressureEnabled, m, ds, sp[i], ty)

        // 3. Single sample -> a filled dot: one swept disc at the pure-pressure half-width. A
        //    finished calligraphy tap takes the dot width (past the broad face) so it stays visible.
        if (n == 1) {
            val h = hw(0, if (finished && ds > 0.0) DOT_DIR_Y else 0.0)
            return StrokeGeometry(emptyList(), centers, listOf(h))
        }

        // 4. Per-point unit tangent via finite differences.
        var lastGood = Pt(1.0, 0.0)
        val tangents = ArrayList<Pt>(n)
        for (i in 0 until n) {
            val diff = when (i) {
                0 -> centers[1] - centers[0]
                n - 1 -> centers[i] - centers[i - 1]
                else -> centers[i + 1] - centers[i - 1]
            }
            val len = diff.length()
            val t = if (len < MIN_TANGENT_LEN) lastGood else (diff / len).also { lastGood = it }
            tangents.add(t)
        }

        // Optional width multipliers: speed thins fast travel, taper points the ends.
        val sf = speedFactors(samples, speedStrength, speedScale)
        val tf = taperFactors(centers, taperEnabled, taperMinFactor)

        // Calligraphy: the tangent-y that sets nib width, with the broad (thick) face held back
        // until the stroke commits to that heading. confirmThickening opens the signal over
        // DIR_CONFIRM_LEN px (a jitter or a stray lift-off sample is dropped to thin, while a run
        // that holds is kept thick along its whole length, lead-in included), then a low-pass keeps
        // the confirmed transition gliding instead of stepping. The line still thins the instant the
        // stroke turns toward the nib edge. Orientation still follows the true tangent; only the
        // width magnitude is held back. A no-op when ds = 0.
        // Exception: a finished dot-sized stroke (DOT_MAX_LEN) can never confirm a heading, so it
        // takes the dot width (DOT_DIR_Y, past the broad face) whole rather than collapsing to the
        // near-invisible thin extreme.
        val dirY = if (ds > 0.0) {
            var arc = 0.0
            for (i in 1 until n) arc += (centers[i] - centers[i - 1]).length()
            if (finished && arc <= DOT_MAX_LEN) List(n) { DOT_DIR_Y }
            else ema(confirmThickening(tangents.map { it.y }, centers, DIR_CONFIRM_LEN), DIR_ALPHA)
        } else null

        // 5–7. Half-widths, normals, and the two ribbon edges.
        val left = ArrayList<Pt>(n)
        val right = ArrayList<Pt>(n)
        val halfWidths = ArrayList<Double>(n)
        for (i in 0 until n) {
            val t = tangents[i]
            val h = hw(i, dirY?.get(i) ?: t.y) * sf[i] * tf[i]
            halfWidths.add(h)
            val normal = Pt(-t.y, t.x) // tangent rotated 90°, already unit length
            left.add(centers[i] - normal * h)
            right.add(centers[i] + normal * h)
        }

        // 8. Outline = left in order + right reversed (single closed polygon).
        val outline = ArrayList<Pt>(2 * n)
        outline.addAll(left)
        for (i in right.indices.reversed()) outline.add(right[i])

        // 9. No separate end caps: the swept brush disc at each sample (the head and tail included)
        //    already rounds every end and join, so [holdEnds] only shapes the end half-widths.
        return StrokeGeometry(
            outline = if (outline.size >= 3) outline else emptyList(),
            centerline = centers,
            halfWidths = halfWidths,
        )
    }
}
