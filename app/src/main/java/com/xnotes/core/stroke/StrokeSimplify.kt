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

    /** Longest arc (content px) the reducer may open between kept samples. */
    const val MAX_GAP = 3.0

    /** Original samples within this arc of a sharp corner are kept, so the chord the reducer would
     *  otherwise draw across it cannot square the turn off. */
    const val CORNER_KEEP_ARC = 2.0

    /** A kept vertex whose chords bend past this cosine (30°) is a sharp corner. Gentle curves
     *  stay under it: RDP at eps leaves them turning ~15° per kept vertex. */
    const val CORNER_COS = 0.866

    /** Samples kept verbatim at each end: covers [StrokeEngine.CAP_HOLD_SAMPLES] (the end-width
     *  hold reads the settled pressure this many samples in) and the dense pen-up tail. */
    const val END_KEEP = StrokeEngine.CAP_HOLD_SAMPLES + 1

    /** Strokes at/below this arc (content px) are never reduced: the calligraphy dot rule
     *  ([StrokeEngine.DOT_MAX_LEN]) judges the smoothed arc, which must not shift near it. */
    const val MIN_ARC = 10.0

    private const val MIN_SAMPLES = 2 * END_KEEP + 3

    /**
     * The samples the ribbon actually needs. A sample survives when its position or its rendered
     * half-width ([halfWidths], from the stroke's built geometry, one per sample) deviates from
     * the kept chord by more than [eps] content px — the width channel is what keeps a pressure
     * spike or a speed-pen thinning from being flattened away. Returns the original list (same
     * reference) when nothing is worth dropping.
     */
    fun simplify(samples: List<Sample>, halfWidths: FloatArray, eps: Double): List<Sample> {
        val n = samples.size
        if (n < MIN_SAMPLES || halfWidths.size != n || eps <= 0.0) return samples

        val cum = DoubleArray(n)
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        }
        if (cum[n - 1] <= MIN_ARC) return samples

        val keep = BooleanArray(n)
        for (i in 0 until END_KEEP) {
            keep[i] = true
            keep[n - 1 - i] = true
        }

        rdp(samples, halfWidths, keep, END_KEEP - 1, n - END_KEEP, eps)
        keepCornerNeighborhoods(samples, keep, cum)
        capGaps(keep, cum)

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
    private fun keepCornerNeighborhoods(samples: List<Sample>, keep: BooleanArray, cum: DoubleArray) {
        val n = samples.size
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
            while (i > 0 && cum[c] - cum[i - 1] <= CORNER_KEEP_ARC) i--
            var j = c
            while (j < n - 1 && cum[j + 1] - cum[c] <= CORNER_KEEP_ARC) j++
            for (t in i..j) keep[t] = true
        }
    }

    /** Re-keep samples so every kept-to-kept arc is at most [MAX_GAP] — or a single raw segment,
     *  when a fast pen was already sparser than the cap. Checked on every sample (kept ones too),
     *  so a gap can't slip past just because its far end was already kept. */
    private fun capGaps(keep: BooleanArray, cum: DoubleArray) {
        var last = 0
        for (i in 1 until keep.size) {
            if (cum[i] - cum[last] > MAX_GAP && i - 1 > last) {
                keep[i - 1] = true
                last = i - 1
            }
            if (keep[i]) last = i
        }
    }
}
