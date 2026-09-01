package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Paints a page-background ruling (lines / dots / grid) in **page-local content space** (origin at
 * the page's content top-left, units = content px), behind the ink. Driven from the cached
 * background layer (`CanvasState.paintPageBackground`), so it never re-rasterizes on an ink edit and
 * composites under strokes. [bounds] is the paper the ruling fills — the page's whole footprint, so
 * a page margin is ruled like the rest of the page — and the pattern is anchored to page-space zero,
 * so growing a margin extends the ruling outward instead of shifting it under the ink already
 * written on it. [region] is the page-local rect actually being painted — the whole footprint for
 * the page cache / thumbnails, or just the visible sub-rect for the deep-zoom sharp
 * viewport — used to skip primitives outside it. Thickness is fixed ([PageStyle.LINE_THICKNESS] /
 * [PageStyle.DOT_RADIUS]); [spacing] is the pattern period. The pen is content-space ([Pen.cosmetic]
 * = false) so the ruling scales with zoom like the page it belongs to.
 */
fun paintPagePattern(
    r: Renderer,
    pattern: PagePattern,
    color: Rgba,
    spacing: Double,
    bounds: Rect,
    region: Rect,
) {
    if (pattern == PagePattern.NONE || spacing < 1.0 || color.a == 0) return
    val clip = intersect(bounds, region) ?: return
    // Honour the pattern opacity uniformly: draw the ruling *opaquely inside an alpha layer* rather
    // than with a per-primitive translucent colour. This survives PDF export (whose vector renderer
    // ignores per-fill alpha but does honour a layer's constant alpha) and keeps grid-line
    // intersections from doubling up in darkness.
    val translucent = color.a < 255
    if (translucent) r.saveLayerAlpha(clip, color.a / 255.0)
    val ink = if (translucent) color.copy(a = 255) else color
    val pen = Pen(ink, width = PageStyle.LINE_THICKNESS, cosmetic = false)
    when (pattern) {
        PagePattern.LINES -> hLines(r, pen, spacing, bounds, clip)
        PagePattern.GRID -> {
            hLines(r, pen, spacing, bounds, clip)
            vLines(r, pen, spacing, bounds, clip)
        }
        PagePattern.DOTS -> {
            var y = first(clip.top, spacing)
            while (y <= clip.bottom && y < bounds.bottom) {
                if (y > bounds.top) {
                    var x = first(clip.left, spacing)
                    while (x <= clip.right && x < bounds.right) {
                        if (x > bounds.left) r.fillCircle(Pt(x, y), PageStyle.DOT_RADIUS, ink)
                        x += spacing
                    }
                }
                y += spacing
            }
        }
        PagePattern.NONE -> {}
    }
    if (translucent) r.restore()
}

/**
 * Paints the ruling in [cover]'s margin strips only, leaving [content] untouched — what an imported
 * PDF page gets, so the extra space beside the page is ruled while the page itself is never drawn
 * over. Each strip bounds its own primitives rather than relying on a clip, because PDF export
 * draws unclipped; the pattern is still anchored to page-space zero, so the strips line up with
 * each other and with a neighbouring page's ruling.
 */
fun paintMarginPattern(
    r: Renderer,
    pattern: PagePattern,
    color: Rgba,
    spacing: Double,
    cover: Rect,
    content: Rect,
    region: Rect,
) {
    for (strip in marginStrips(cover, content)) {
        val slice = intersect(strip, region) ?: continue
        paintPagePattern(r, pattern, color, spacing, strip, slice)
    }
}

/** The (up to four, non-overlapping) strips of [cover] left uncovered by [content]. */
fun marginStrips(cover: Rect, content: Rect): List<Rect> {
    val out = ArrayList<Rect>(4)
    fun add(l: Double, t: Double, rr: Double, b: Double) {
        if (rr > l && b > t) out.add(Rect(l, t, rr - l, b - t))
    }
    add(cover.left, cover.top, cover.right, content.top)          // above
    add(cover.left, content.bottom, cover.right, cover.bottom)    // below
    add(cover.left, content.top, content.left, content.bottom)    // left of
    add(content.right, content.top, cover.right, content.bottom)  // right of
    return out
}

private fun intersect(a: Rect, b: Rect): Rect? {
    val l = max(a.left, b.left)
    val t = max(a.top, b.top)
    val r = min(a.right, b.right)
    val bo = min(a.bottom, b.bottom)
    return if (r > l && bo > t) Rect(l, t, r - l, bo - t) else null
}

/** The first ruling coordinate at or after [start]; the paper's own edges are skipped by the callers. */
private fun first(start: Double, spacing: Double): Double = ceil(start / spacing) * spacing

private fun hLines(r: Renderer, pen: Pen, spacing: Double, bounds: Rect, clip: Rect) {
    var y = first(clip.top, spacing)
    while (y <= clip.bottom && y < bounds.bottom) {
        if (y > bounds.top) r.strokePolyline(listOf(Pt(bounds.left, y), Pt(bounds.right, y)), pen)
        y += spacing
    }
}

private fun vLines(r: Renderer, pen: Pen, spacing: Double, bounds: Rect, clip: Rect) {
    var x = first(clip.left, spacing)
    while (x <= clip.right && x < bounds.right) {
        if (x > bounds.left) r.strokePolyline(listOf(Pt(x, bounds.top), Pt(x, bounds.bottom)), pen)
        x += spacing
    }
}
