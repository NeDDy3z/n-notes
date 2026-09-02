package com.xnotes.format

/**
 * Stroke samples as they come off the parser: parallel growable arrays rather than a list of
 * [com.xnotes.core.stroke.Sample]s.
 *
 * A dense note holds millions of samples, and building one boxed object per point cost an
 * allocation each on the way in and a second walk of the whole list to pack it into the stroke.
 * The stroke takes these arrays directly instead.
 */
internal class RawSamples {

    var xs = DoubleArray(INITIAL)
        private set
    var ys = DoubleArray(INITIAL)
        private set
    var ps = DoubleArray(INITIAL)
        private set

    /** Allocated only once a sample carries a non-zero time, as the stroke's own storage is. */
    var ts: DoubleArray? = null
        private set

    var n = 0
        private set

    fun add(x: Double, y: Double, pressure: Double, time: Double) {
        if (n == xs.size) {
            val c = n * 2
            xs = xs.copyOf(c)
            ys = ys.copyOf(c)
            ps = ps.copyOf(c)
            ts = ts?.copyOf(c)
        }
        xs[n] = x
        ys[n] = y
        ps[n] = pressure
        if (time != 0.0 && ts == null) ts = DoubleArray(xs.size) // earlier samples were zero
        ts?.set(n, time)
        n++
    }

    private companion object {
        const val INITIAL = 64
    }
}
