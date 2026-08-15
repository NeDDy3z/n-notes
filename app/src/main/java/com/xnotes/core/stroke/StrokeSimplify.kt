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

    /** Master switch, read by the three places that reduce: both interaction controllers at pen-up
     *  and the codec's one-off compaction at load. Off means every stroke keeps every sample it was
     *  drawn with, whatever that costs in memory and file size, which is worth being able to do while
     *  judging ink: nothing that shows up at pen-up can then be blamed on a dropped sample. A `var`
     *  so it can be flipped from a debugger without a rebuild. [simplify] itself always reduces, so
     *  the unit tests keep testing the reduction whatever this says. */
    var enabled = true

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
     *  ([StrokeEngine.HEAD_LEN]) judges the smoothed arc, which must not shift near it. Scaled by
     *  the draw zoom, since the rule it is protecting is scaled by it too. */
    const val MIN_ARC = 10.0

    private const val MIN_SAMPLES = 2 * END_KEEP + 3

    /**
     * The samples the ribbon actually needs. A sample survives when its position or its rendered
     * half-width ([halfWidths], from the stroke's built geometry, one per sample) deviates from
     * the kept chord by more than [eps] content px — the width channel is what keeps a pressure
     * spike or a speed-pen thinning from being flattened away. Returns the original list (same
     * reference) when nothing is worth dropping.
     *
     * [scale] is the stroke's `smoothScale`: every arc below is quoted at 100% zoom and scaled by it,
     * so a stroke drawn zoomed in is reduced by the same amounts on screen as one drawn at 100%.
     * [directionStrength] is the pen's, and picks up the nib's head guard for a stroke whose width
     * follows its heading; see [keepHead] for what that protects and why nothing else can.
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

        rdp(samples, halfWidths, keep, END_KEEP - 1, n - END_KEEP, eps)
        keepCornerNeighborhoods(samples, keep, cum, k)
        if (directionStrength > 0.0) keepHead(keep, cum, k)
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
     * Calligraphy: keep every sample the nib's **head** is decided from.
     *
     * The rest of the nib needs no guard. Past the head the width is a slew limiter integrating over
     * arc, so it moves continuously with its input and the width channel [rdp] already reads catches
     * any sample that mattered to it. The head does not work that way: it is
     * the minimum heading over the stroke's first [StrokeEngine.HEAD_LEN], pinned flat across the
     * whole window, and a flat run is exactly where a width channel has nothing to flag. Drop the
     * sample that won the minimum and the head comes back broader with no deviation to have caught
     * it, which reads as the stroke changing weight at pen-up.
     *
     * The reach runs past the window itself, because [cum] is raw travel while the engine measures
     * the head on the smoothed centreline, which trails it, and the last sample's tangent reads one
     * kept sample further still.
     */
    private fun keepHead(keep: BooleanArray, cum: DoubleArray, scale: Double) {
        val headArc = StrokeEngine.headLen(scale) + (StrokeEngine.SMOOTH_LEN + MAX_GAP) * scale
        for (i in keep.indices) if (cum[i] <= headArc) keep[i] = true else break
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
