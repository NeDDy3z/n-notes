package com.xnotes.core.stroke

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Sample reduction for finished strokes: drops the samples the rendered ribbon doesn't need, so
 * ink from very-high-rate styluses (333–500 Hz) written at high zoom stops costing 5–10x the
 * memory, file size and geometry time of normal ink. Runs at pen-up on every new stroke and once
 * at load for files written before it shipped (see DocumentCodec).
 *
 * The core is Ramer–Douglas–Peucker over the sample polyline with the rendered half-width as an
 * extra channel, plus three guards that bound what the reduction can move: [MAX_GAP] caps the arc
 * between kept samples, sharp corners keep their original neighborhood density, and the first/last
 * [END_KEEP] samples survive verbatim (they carry the end-width hold).
 *
 * The guards used to carry the whole weight, because the ribbon's low-pass was indexed by sample
 * and re-smoothed a thinned stroke into a visibly different curve — rounder corners, shorter tails.
 * It now smooths per unit of travel ([StrokeEngine.emaByArc]), so the curve no longer depends on
 * how many samples describe it, and the guards are left holding only the chord error itself.
 */
object StrokeSimplify {

    /** Compaction tolerance (content px) for legacy files, whose draw zoom is unknown. */
    const val LEGACY_EPS = 0.1

    /** Longest arc the reducer may open between kept samples, at 100% zoom. Scaled by the draw zoom
     *  like [eps], so the chord it allows is the same size on screen wherever it was drawn. */
    const val MAX_GAP = 3.0

    /** Original samples within this arc of a sharp corner are kept, so the chord the reducer would
     *  otherwise draw across it cannot square the turn off. Scaled with [MAX_GAP]. */
    const val CORNER_KEEP_ARC = 2.0

    /** A kept vertex whose chords bend past this cosine (30°) is a sharp corner. Gentle curves
     *  stay under it: RDP at eps leaves them turning ~15° per kept vertex. */
    const val CORNER_COS = 0.866

    /** Samples kept verbatim at each end: covers [StrokeEngine.CAP_HOLD_SAMPLES] (the end-width
     *  hold reads the settled pressure this many samples in) and the dense pen-up tail. */
    const val END_KEEP = StrokeEngine.CAP_HOLD_SAMPLES + 1

    /** Strokes at/below this arc are never reduced: the calligraphy dot rule
     *  ([StrokeEngine.DOT_MAX_LEN]) judges the smoothed arc, which must not shift near it. Scaled by
     *  the draw zoom, since the rule it is protecting is scaled by it too. */
    const val MIN_ARC = 10.0

    private const val MIN_SAMPLES = 2 * END_KEEP + 3

    /** Confirm windows a direction-critical stretch spans: the erosion's own, plus the dilation's
     *  on either side of it, plus one for the tangent's finite differences to land in. */
    const val DIR_REACH = 3.0

    /**
     * The samples the ribbon actually needs. A sample survives when its position or its rendered
     * half-width ([halfWidths], from the stroke's built geometry, one per sample) deviates from
     * the kept chord by more than [eps] content px — the width channel is what keeps a pressure
     * spike or a speed-pen thinning from being flattened away. Returns the original list (same
     * reference) when nothing is worth dropping.
     *
     * [scale] is the stroke's `smoothScale`: every arc below is quoted at 100% zoom and scaled by it,
     * so a stroke drawn zoomed in is reduced by the same amounts on screen as one drawn at 100%.
     * [directionStrength] is the pen's, and picks up the nib guard for a stroke whose width follows
     * its heading; see [keepDirectionCritical] for what that protects and why nothing else can.
     */
    fun simplify(
        samples: List<Sample>,
        halfWidths: FloatArray,
        eps: Double,
        scale: Double = 1.0,
        directionStrength: Double = 0.0,
    ): List<Sample> {
        val n = samples.size
        if (n < MIN_SAMPLES || halfWidths.size != n || eps <= 0.0) return samples
        val k = if (scale > 0.0) scale else 1.0

        val cum = DoubleArray(n)
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        }
        if (cum[n - 1] <= MIN_ARC * k) return samples

        val keep = BooleanArray(n)
        for (i in 0 until END_KEEP) {
            keep[i] = true
            keep[n - 1 - i] = true
        }

        // The nib's guard reads its arc straight from the engine, since a guard protecting a
        // different stretch than the one being confirmed would protect the wrong samples.
        val dirArc = if (directionStrength > 0.0) StrokeEngine.dirConfirmLen(k) else 0.0
        rdp(samples, halfWidths, keep, END_KEEP - 1, n - END_KEEP, eps)
        keepCornerNeighborhoods(samples, keep, cum, k)
        if (dirArc > 0.0) keepDirectionCritical(samples, keep, cum, dirArc, k)
        capGaps(keep, cum, k)

        var kept = 0
        for (i in 0 until n) if (keep[i]) kept++
        if (kept == n) return samples
        val out = ArrayList<Sample>(kept)
        for (i in 0 until n) if (keep[i]) out.add(samples[i])
        return out
    }

    /** Iterative RDP on `(first, last)`: split at the worst position/width deviation until none
     *  exceeds [eps]. Endpoints are already kept by the caller. */
    private fun rdp(samples: List<Sample>, hw: FloatArray, keep: BooleanArray, first: Int, last: Int, eps: Double) {
        if (last - first < 2) return
        val stack = ArrayDeque<Int>()
        stack.addLast(first)
        stack.addLast(last)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            val a = stack.removeLast()
            if (b - a < 2) continue
            val ax = samples[a].x
            val ay = samples[a].y
            val dx = samples[b].x - ax
            val dy = samples[b].y - ay
            val ha = hw[a].toDouble()
            val hb = hw[b].toDouble()
            val lenSq = dx * dx + dy * dy
            var worst = -1
            var worstDev = eps
            for (i in a + 1 until b) {
                val px = samples[i].x - ax
                val py = samples[i].y - ay
                val t = if (lenSq < 1e-12) 0.0 else ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
                var dev = hypot(px - t * dx, py - t * dy)
                val wDev = abs(hw[i] - (ha + (hb - ha) * t))
                if (wDev > dev) dev = wDev
                if (dev > worstDev) {
                    worstDev = dev
                    worst = i
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(a)
                stack.addLast(worst)
                stack.addLast(worst)
                stack.addLast(b)
            }
        }
    }

    /** Restore the original density around every sharp corner of the kept polyline. */
    private fun keepCornerNeighborhoods(
        samples: List<Sample>,
        keep: BooleanArray,
        cum: DoubleArray,
        scale: Double,
    ) {
        val n = samples.size
        val cornerArc = CORNER_KEEP_ARC * scale
        val kept = ArrayList<Int>()
        for (i in 0 until n) if (keep[i]) kept.add(i)
        for (k in 1 until kept.size - 1) {
            val p = kept[k - 1]
            val c = kept[k]
            val q = kept[k + 1]
            val ux = samples[c].x - samples[p].x
            val uy = samples[c].y - samples[p].y
            val vx = samples[q].x - samples[c].x
            val vy = samples[q].y - samples[c].y
            val ul = hypot(ux, uy)
            val vl = hypot(vx, vy)
            if (ul < 1e-9 || vl < 1e-9) continue
            if ((ux * vx + uy * vy) / (ul * vl) >= CORNER_COS) continue
            var i = c
            while (i > 0 && cum[c] - cum[i - 1] <= cornerArc) i--
            var j = c
            while (j < n - 1 && cum[j + 1] - cum[c] <= cornerArc) j++
            for (t in i..j) keep[t] = true
        }
    }

    /**
     * Calligraphy: keep the original density wherever the nib's thick/thin decision is made.
     *
     * That decision is not a filter but an order statistic. [StrokeEngine.confirmThickening] erodes
     * the heading with a trailing-window minimum and dilates it with a leading-window maximum, and a
     * minimum changes the moment a candidate leaves its window. So it is not enough to bound how far
     * the reduction moves the curve, which is all the other guards do, and all the arc-length
     * smoothing does: drop the one thin sample that was holding a window down and the whole run
     * confirms thick, which reads as the stroke changing weight at pen-up.
     *
     * Two places qualify. The run-in at each end, because the start floor reads its window at
     * exactly [arc] of travel and [END_KEEP] samples do not reach that far. And every crossover
     * where the path turns through the nib's edge, since that is where the heading a window
     * minimises over is actually changing; a steady run has nothing to lose, however thinned.
     */
    private fun keepDirectionCritical(
        samples: List<Sample>,
        keep: BooleanArray,
        cum: DoubleArray,
        arc: Double,
        scale: Double,
    ) {
        val n = samples.size
        val total = cum[n - 1]
        // The opening erodes and then dilates, so one confirmed value is a minimum of minima and
        // reaches a window each way on top of its own: protecting a single window either side left
        // the dilation still reading thinned ink just outside it, and a visible step with it.
        val reach = DIR_REACH * arc
        for (i in 0 until n) if (cum[i] <= reach || total - cum[i] <= reach) keep[i] = true
        // The heading over a short chord rather than between neighbours: consecutive samples on a
        // near-horizontal run flip sign on jitter alone, and that would protect the whole stroke.
        var previous = 0.0
        for (i in 1 until n) {
            val dy = headingY(samples, cum, i, scale)
            if (dy == 0.0) continue
            if (previous != 0.0 && (dy > 0.0) != (previous > 0.0)) {
                var a = i
                while (a > 0 && cum[i] - cum[a - 1] <= reach) a--
                var b = i
                while (b < n - 1 && cum[b + 1] - cum[i] <= reach) b++
                for (t in a..b) keep[t] = true
            }
            previous = dy
        }
    }

    /** The y of the path's heading at [i], over a chord of about [CORNER_KEEP_ARC] either side. */
    private fun headingY(samples: List<Sample>, cum: DoubleArray, i: Int, scale: Double): Double {
        val n = samples.size
        val chord = CORNER_KEEP_ARC * scale
        var a = i
        while (a > 0 && cum[i] - cum[a - 1] <= chord) a--
        var b = i
        while (b < n - 1 && cum[b] - cum[i] <= chord) b++
        return samples[b].y - samples[a].y
    }

    /** Re-keep samples so every kept-to-kept arc is at most [MAX_GAP] — or a single raw segment,
     *  when a fast pen was already sparser than the cap. Checked on every sample (kept ones too),
     *  so a gap can't slip past just because its far end was already kept. */
    private fun capGaps(keep: BooleanArray, cum: DoubleArray, scale: Double) {
        val maxGap = MAX_GAP * scale
        var last = 0
        for (i in 1 until keep.size) {
            if (cum[i] - cum[last] > maxGap && i - 1 > last) {
                keep[i - 1] = true
                last = i - 1
            }
            if (keep[i]) last = i
        }
    }
}
