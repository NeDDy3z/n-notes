package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.stroke.WetRibbon
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * The raster half of the wet cache: the part of the stroke under the pen that has stopped moving,
 * painted into an offscreen surface once and blitted every frame after.
 *
 * [WetRibbon] makes the *geometry* of a long stroke cost what its moving tail costs. This does the
 * same for the *pixels*, which is the larger of the two: filling the ribbon means a path of two
 * shapes per point, rebuilt and scan-converted every frame, so a stroke that has been going for a
 * few seconds is redrawing thousands of discs to show the one under the nib. Here the settled run
 * is drawn into the surface as it settles — each point exactly once, ever — and the frame draws one
 * bitmap plus the handful of points still in play.
 *
 * The two runs deliberately overlap by a point, so they share a whole brush disc and no seam can
 * open between them. That only works because the ink is opaque: the same colour laid down twice is
 * still that colour. Translucent ink would darken along the join and neon's blooms would compound,
 * so [Stroke.wetCacheable] turns both away and they keep the plain redraw.
 *
 * Everything here is in **page space**, like the page and highlighter caches, so the surface holds
 * still while the ink grows over it and only a zoom can invalidate it. A zoom cannot arrive
 * mid-stroke anyway: a second finger aborts the stroke before it becomes a pinch.
 */
class WetInkCache(private val surfaceFactory: SurfaceFactory) {

    private var surface: RasterSurface? = null

    /** A painter into [surface], kept so a frame does not allocate one to add a few points. */
    private var into: Renderer? = null

    /** Page-space rect [surface] covers. */
    private var cover = Rect(0.0, 0.0, 0.0, 0.0)

    /** Device px per page px the surface was rendered at. */
    private var res = 0.0

    /** The stroke the surface holds, by identity; a different one starts over. */
    private var owner: Stroke? = null

    /** Ribbon points already in the surface. */
    private var baked = 0

    /** Centreline arc those points spent, which is where the dashed pen's rhythm has got to. */
    private var bakedArc = 0.0

    /**
     * Draw [stroke]'s live ink into [r], baking whatever has settled since the last frame. Returns
     * false when this stroke is not one the cache can hold, or when it is not yet long enough to be
     * worth a surface, and the caller should paint it whole.
     *
     * [res] is device px per page px and [maxPixels] caps the surface, since a stroke sweeping a
     * zoomed-in page could otherwise ask for a buffer far larger than the screen showing it.
     */
    fun paint(r: Renderer, stroke: Stroke, res: Double, maxPixels: Long): Boolean {
        val ribbon = stroke.wetRibbon ?: return false
        if (!stroke.wetCacheable) return false
        if (owner !== stroke || abs(this.res - res) > 1e-9) {
            owner = stroke
            this.res = res
            baked = 0
            bakedArc = 0.0
        }
        val settled = ribbon.settledCount
        if (settled < MIN_BAKE_POINTS) return false
        if (!ensureRoom(ribbon.bounds(), maxPixels)) return false
        bake(stroke, ribbon, settled)
        val s = surface ?: return false
        r.drawRaster(s, cover)
        // One point back, so the live run and the baked one share a disc and cannot leave a gap,
        // and it starts exactly where the baked run's dash pattern got to.
        val from = max(baked - 1, 0)
        stroke.paintRun(r, ribbon, from, ribbon.pointCount - from, bakedArc)
        return true
    }

    /** Let the surface go: a document closed, a memory trim, or the caches dropped wholesale. */
    fun clear() {
        surface?.recycle()
        surface = null
        into = null
        owner = null
        baked = 0
        bakedArc = 0.0
        res = 0.0
        cover = Rect(0.0, 0.0, 0.0, 0.0)
    }

    /**
     * Make sure the surface covers [needed], growing it if the stroke has run out of it. Growth
     * carries the pixels over rather than repainting the ink: blitting a bitmap costs its area,
     * while redrawing would cost every point the stroke has laid down, which is the thing this
     * whole cache exists to stop paying.
     */
    private fun ensureRoom(needed: Rect, maxPixels: Long): Boolean {
        val old = surface
        val carry = old != null && baked > 0
        if (carry && contains(cover, needed)) return true

        val pad = (max(needed.w, needed.h) * PAD_FRACTION).coerceIn(MIN_PAD, MAX_PAD)
        // Growing must never drop ink already baked, so the new cover swallows the old one whole.
        val want = if (carry) needed.outset(pad).union(cover) else needed.outset(pad)
        // Anchored a whole number of surface pixels out from the old anchor, so carrying the
        // pixels over is a straight copy. Off the grid every growth would resample the ink, and a
        // long stroke would go visibly soft where the short ones behind it stayed crisp.
        val originX = if (carry) cover.left - ceil((cover.left - want.x) * res) / res else want.x
        val originY = if (carry) cover.top - ceil((cover.top - want.y) * res) / res else want.y
        val w = ceil((want.right - originX) * res).toInt().coerceAtLeast(1)
        val h = ceil((want.bottom - originY) * res).toInt().coerceAtLeast(1)
        if (w.toLong() * h.toLong() > maxPixels) return false

        // A fresh stroke inside a surface we already hold: rinse it and re-anchor, no allocation.
        if (old != null && !carry && old.width >= w && old.height >= h) {
            old.fill(TRANSPARENT)
            cover = Rect(originX, originY, old.width / res, old.height / res)
            return true
        }

        val fresh = surfaceFactory.create(w, h, 1.0)
        fresh.fill(TRANSPARENT)
        val painter = fresh.renderer()
        val grown = Rect(originX, originY, w / res, h / res)
        if (carry) {
            painter.withSave {
                painter.scale(res, res)
                painter.translate(-grown.left, -grown.top)
                painter.drawRaster(old!!, cover)
            }
        }
        old?.recycle()
        surface = fresh
        into = painter
        cover = grown
        return true
    }

    /**
     * Paint the run that settled since the last frame into the surface, at page scale, and carry
     * the dash phase over it.
     *
     * [bakedArc] is the arc the baked run has spent, always measured through the last point in it,
     * so it is both the phase this run starts at and — once this run's own length is added — the
     * phase the live tail starts at. Neither is ever measured from the head of the stroke.
     */
    private fun bake(stroke: Stroke, ribbon: WetRibbon, settled: Int) {
        if (settled <= baked) return
        val r = into ?: return
        // From one point back, so the segment bridging the last baked point to the next one is
        // drawn; its disc is simply laid down again, which opaque ink does not notice.
        val from = max(baked - 1, 0)
        r.withSave {
            r.scale(res, res)
            r.translate(-cover.left, -cover.top)
            stroke.paintRun(r, ribbon, from, settled - from, bakedArc)
        }
        for (k in from + 1 until settled) {
            bakedArc += hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
        }
        baked = settled
    }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        inner.left >= outer.left && inner.top >= outer.top &&
            inner.left + inner.w <= outer.left + outer.w &&
            inner.top + inner.h <= outer.top + outer.h

    companion object {
        private val TRANSPARENT = Rgba(0, 0, 0, 0)

        /** Below this the stroke redraws whole: a short one costs nothing, and a surface for it is
         *  memory and a blit spent to save a few dozen discs. */
        const val MIN_BAKE_POINTS = 48

        /** Page-px slack around the ink, so an ordinary stroke outgrows its surface a handful of
         *  times rather than on every sample. Capped at the top end because a stroke that already
         *  spans the screen would otherwise ask for a third as much again. */
        private const val MIN_PAD = 48.0
        private const val MAX_PAD = 320.0
        private const val PAD_FRACTION = 0.35
    }
}
