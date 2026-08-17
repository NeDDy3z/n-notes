package com.xnotes.platform

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.GlyphOutliner
import com.xnotes.core.vector.GlyphRun
import com.xnotes.core.vector.GlyphStyle
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/**
 * Glyph outlines through `Paint.getTextPath`, so a placed SVG's text is geometry rather than
 * pixels and stays sharp at every zoom.
 *
 * The outline comes back as a platform `Path`, which below API 34 cannot be read back verb by
 * verb. So it is walked with a `PathMeasure` at a fine step and then decimated: the walk gives
 * exact contour boundaries, which triangulation needs to tell a counter from a bowl, and the
 * decimation puts the straight parts back to two points each.
 *
 * Font matching is the platform's, which is the same matching the raster path already had. A file
 * naming a family the device lacks sets in a substitute either way.
 */
class AndroidGlyphOutliner : GlyphOutliner {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    @Synchronized
    override fun outline(text: String, style: GlyphStyle): GlyphRun? {
        if (text.isEmpty() || style.size <= 0.0) return null
        apply(style)
        path.reset()
        runCatching { paint.getTextPath(text, 0, text.length, 0f, 0f, path) }.getOrNull() ?: return null
        val contours = walk(path, style.size)
        return GlyphRun(contours, paint.measureText(text).toDouble())
    }

    @Synchronized
    override fun measure(text: String, style: GlyphStyle): Double {
        if (text.isEmpty() || style.size <= 0.0) return 0.0
        apply(style)
        return paint.measureText(text).toDouble()
    }

    private fun apply(style: GlyphStyle) {
        val face = when {
            style.bold && style.italic -> Typeface.BOLD_ITALIC
            style.bold -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        paint.typeface = Typeface.create(style.family ?: "sans-serif", face)
        paint.textSize = style.size.toFloat()
        // Paint states tracking in ems; SVG states it in the same units as the font size.
        paint.letterSpacing = (style.letterSpacing / style.size).toFloat()
    }

    /** Every contour of [source] as a polyline, sampled finely and then thinned back down. */
    private fun walk(source: Path, size: Double): List<VectorContour> {
        val step = size / SAMPLES_PER_EM
        val tolerance = size / DECIMATE_DENOMINATOR
        val measure = PathMeasure(source, false)
        val out = ArrayList<VectorContour>()
        val at = FloatArray(2)
        var guard = MAX_CONTOURS
        do {
            val length = measure.length
            if (length > 1e-4) {
                val count = kotlin.math.ceil(length / step).toInt().coerceIn(2, MAX_SAMPLES)
                val pts = ArrayList<Pt>(count + 1)
                for (i in 0..count) {
                    val d = length * i / count
                    if (measure.getPosTan(d, at, null)) pts.add(Pt(at[0].toDouble(), at[1].toDouble()))
                }
                val thinned = PathFlattener.decimate(pts, tolerance)
                if (thinned.size >= 3) out.add(contourOf(thinned))
            }
        } while (measure.nextContour() && guard-- > 0)
        return out
    }

    private fun contourOf(points: List<Pt>): VectorContour {
        // A closed glyph contour comes back with its last point on its first; drop the repeat.
        val last = points.size - 1
        val ring = if (near(points[0], points[last])) points.subList(0, last) else points
        return VectorContour(ring[0], ring.drop(1).map { VectorSeg.Line(it) }, closed = true)
    }

    private fun near(a: Pt, b: Pt) = kotlin.math.abs(a.x - b.x) < 1e-4 && kotlin.math.abs(a.y - b.y) < 1e-4

    private companion object {
        /** How finely a contour is walked, in samples per em of arc length. */
        const val SAMPLES_PER_EM = 64.0

        /** Decimation tolerance as a fraction of the em: well under a pixel at any real zoom. */
        const val DECIMATE_DENOMINATOR = 2000.0

        const val MAX_SAMPLES = 4096
        const val MAX_CONTOURS = 4096
    }
}
