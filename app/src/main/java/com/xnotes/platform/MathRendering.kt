package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.export.ExportConfig
import com.hrm.latex.renderer.export.LatexExporterState
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathTypesetter

/**
 * Typesets and draws the flow's LaTeX, through a renderer that can only be built
 * inside a composition. One per process like [com.xnotes.settings.LiveSettings],
 * because a [AndroidRenderer] is made fresh around each canvas and there is
 * nowhere else for the formula cache to live. Until [install] runs, measuring
 * returns null and maths reads as its own source, which is also what happens to
 * LaTeX that will not parse.
 */
object MathRendering : MathTypesetter {

    /** The measurer, the exporter and the density they were both built against. */
    private class Engine(
        val measurer: LatexMeasurerState,
        val exporter: LatexExporterState,
        val spPx: Float,
    )

    @Volatile private var engine: Engine? = null

    // Formulas raster at this multiple of their page size so zooming in stays
    // sharp; past it they soften like any other bitmap, which is the trade for
    // not re-rastering every one of them on every pinch.
    private const val OVERSAMPLE = 3f

    private const val CACHE_BYTES = 8 shl 20

    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val blit = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Adopt the renderer built in the composition. Called again whenever the
     * density changes, which invalidates every rastered formula.
     */
    fun install(measurer: LatexMeasurerState, exporter: LatexExporterState, density: Density) {
        val spPx = with(density) { 1.sp.toPx() }
        synchronized(this) {
            engine = Engine(measurer, exporter, spPx)
            bitmaps.evictAll()
        }
    }

    override fun ready(): Boolean = engine != null

    override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? {
        val e = engine ?: return null
        if (latex.isBlank() || e.spPx <= 0f) return null
        // The library caches in plain maps, and page caches are built off the main
        // thread, so every call into it is serialized here rather than there.
        val src = source(latex, display)
        val d = synchronized(this) { e.measurer.measure(src, config(e, sizePt, null)) } ?: return null
        return MathBox(
            width = d.widthPx.toDouble(),
            ascent = d.baselinePx.toDouble(),
            descent = (d.heightPx - d.baselinePx).toDouble(),
        )
    }

    /** Draw [latex] with its left edge at [x], sitting on [baseline], in [color]. */
    fun draw(
        canvas: Canvas,
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean = false,
    ) {
        val e = engine ?: return
        val box = measure(latex, sizePt, display) ?: return
        val bmp = raster(e, source(latex, display), sizePt, color) ?: return
        canvas.drawBitmap(
            bmp,
            null,
            RectF(
                x.toFloat(),
                (baseline - box.ascent).toFloat(),
                (x + box.width).toFloat(),
                (baseline + box.descent).toFloat(),
            ),
            blit,
        )
    }

    /**
     * What actually goes to the renderer. Display form is asked for in LaTeX's own
     * terms rather than a render flag, so it is the same string the user would
     * have written by hand and the measurement and the raster cannot disagree.
     */
    private fun source(latex: String, display: Boolean): String =
        if (display) "\\displaystyle $latex" else latex

    private fun raster(e: Engine, latex: String, sizePt: Double, color: Rgba): Bitmap? {
        val key = "$latex|$sizePt|${color.toArgb()}"
        bitmaps.get(key)?.let { return it }
        val bmp = synchronized(this) {
            bitmaps.get(key) ?: e.exporter.export(
                latex = latex,
                config = config(e, sizePt, color),
                exportConfig = ExportConfig(scale = OVERSAMPLE, transparentBackground = true),
            )?.imageBitmap?.asAndroidBitmap()?.also { bitmaps.put(key, it) }
        }
        return bmp
    }

    /**
     * Ask for the formula in page pixels rather than screen ones: dividing the
     * size back out of the measurer's own density is what keeps an equation the
     * same size on the page whatever the display or the reader's font scale.
     */
    private fun config(e: Engine, sizePt: Double, color: Rgba?): LatexConfig {
        val px = sizePt * AndroidText.POINTS_TO_PX
        return LatexConfig(
            fontSize = (px / e.spPx).sp,
            theme = if (color == null) {
                LatexTheme.auto()
            } else {
                LatexTheme.light(color = Color(color.toArgb()), backgroundColor = Color.Transparent)
            },
        )
    }
}
