package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize

/**
 * Where an infinite canvas's content lands on the single PDF page it exports to.
 *
 * A canvas has no page to inherit a size from, so the page is cut to the content: the media box is
 * the content bounds plus a margin, and one point is one content pixel at the document dpi — the
 * same 1:1 mapping the paged exporter uses, so ink exports the same size from either surface.
 *
 * One thing can break that mapping, and it is why this is its own class rather than four lines inside
 * the exporter: a PDF page dimension tops out at 14400 points and a canvas is unbounded. Past the
 * ceiling the page shrinks uniformly instead of being cropped, so a canvas drawn across a mile
 * exports small rather than losing everything right of the 200-inch mark.
 *
 * Pure, so it is the part of the export that can actually be tested: the arithmetic is what would
 * silently produce a cropped or empty page, and checking it needs no PDF writer.
 */
object CanvasPdfLayout {

    /** PDF's ceiling on a page dimension: 200 inches at 72 points to the inch. */
    const val MAX_PAGE_POINTS = 14400.0

    /** Breathing room around the content, as a fraction of its longer side. */
    private const val MARGIN_FRACTION = 0.02

    /** The band that fraction is held to, in inches, so a doodle still gets a visible edge and a
     *  wall-sized canvas does not get a hand's width of blank paper. */
    private const val MIN_MARGIN_INCHES = 0.08
    private const val MAX_MARGIN_INCHES = 0.5

    /**
     * [cover] is the content-space rectangle the page shows; [scale] is points per content pixel,
     * which is `72/dpi` unless the page had to shrink.
     */
    data class Layout(val cover: Rect, val scale: Double) {

        val widthPoints: Double get() = (cover.w * scale).coerceAtLeast(1.0)
        val heightPoints: Double get() = (cover.h * scale).coerceAtLeast(1.0)

        /**
         * How far the page sits from the 1:1 mapping — 1.0 normally, less when it had to shrink.
         * Read as a zoom it is what picks the ruling's level, so a shrunk page gets the coarser grid
         * the canvas itself would show at that zoom rather than an unreadable mesh.
         */
        fun zoomEquivalent(dpi: Int): Double {
            val base = 72.0 / (if (dpi > 0) dpi else PageSize.DEFAULT_DPI)
            return if (base > 0.0) scale / base else 1.0
        }
    }

    /** The page for [content]; null or degenerate bounds mean an empty canvas, which gets a blank A4. */
    fun of(content: Rect?, dpi: Int): Layout {
        val safeDpi = if (dpi > 0) dpi else PageSize.DEFAULT_DPI
        val base = 72.0 / safeDpi
        val body = content?.takeIf { it.isFinite() && it.w > 0.0 && it.h > 0.0 }
            ?: return Layout(blankPage(safeDpi), base)
        val cover = body.outset(marginFor(body, safeDpi))
        val overshoot = maxOf(cover.w * base, cover.h * base) / MAX_PAGE_POINTS
        return Layout(cover, if (overshoot > 1.0) base / overshoot else base)
    }

    private fun marginFor(body: Rect, dpi: Int): Double =
        (maxOf(body.w, body.h) * MARGIN_FRACTION)
            .coerceIn(MIN_MARGIN_INCHES * dpi, MAX_MARGIN_INCHES * dpi)

    /** An empty canvas still has to export something; one blank portrait A4 is the least surprising. */
    private fun blankPage(dpi: Int): Rect {
        val (w, h) = PageSize.A4.pixels(Orientation.PORTRAIT, dpi)
        return Rect(0.0, 0.0, w, h)
    }
}
