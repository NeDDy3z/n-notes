package com.xnotes.core.template

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * Paints a [TemplateOutput] through the PAL, in page-local content space (the page's top-left at
 * the origin, [scale] content px per mm). Each colour layer is drawn opaque inside one alpha layer,
 * so crossings never darken and PDF export (which honours a layer's alpha but not a fill's) matches
 * the screen. [region] is the page-local rect being painted; shapes outside it are skipped.
 */
object TemplatePainter {

    fun paint(r: Renderer, out: TemplateOutput, scale: Double, region: Rect) {
        if (scale <= 0.0) return
        val area = Rect(region.x / scale, region.y / scale, region.w / scale, region.h / scale)
        for (layer in out.layers) {
            var bounds: Rect? = null
            val visible = layer.prims.filter { p ->
                p.bounds.intersects(area).also { if (it) bounds = bounds?.union(p.bounds) ?: p.bounds }
            }
            val b = bounds ?: continue
            val translucent = layer.color.a < 255
            val ink = if (translucent) layer.color.copy(a = 255) else layer.color
            if (translucent) {
                val box = TemplateClip.intersect(Rect(b.x * scale, b.y * scale, b.w * scale, b.h * scale).outset(1.0), region) ?: continue
                r.saveLayerAlpha(box, layer.color.a / 255.0)
            }
            for (p in visible) draw(r, p, ink, scale)
            if (translucent) r.restore()
        }
    }

    private fun draw(r: Renderer, p: TPrim, ink: Rgba, s: Double) {
        when (p) {
            is TPrim.Stroke -> {
                val pen = pen(ink, p.width, p.dashOn, p.dashOff, s)
                val pts = points(p.pts, s)
                if (p.closed) r.strokePolygon(pts, pen) else r.strokePolyline(pts, pen)
            }
            is TPrim.Fill -> r.fillPolygon(points(p.pts, s), ink, FillRule.NONZERO)
            is TPrim.FillRect -> r.fillRect(Rect(p.rect.x * s, p.rect.y * s, p.rect.w * s, p.rect.h * s), ink)
            is TPrim.Ellipse -> {
                val c = Pt(p.cx * s, p.cy * s)
                when {
                    !p.fill -> r.strokeEllipse(c, p.rx * s, p.ry * s, pen(ink, p.width, p.dashOn, p.dashOff, s))
                    p.rx == p.ry -> r.fillCircle(c, p.rx * s, ink)
                    else -> r.fillEllipse(c, p.rx * s, p.ry * s, ink)
                }
            }
        }
    }

    private fun pen(ink: Rgba, width: Double, on: Double, off: Double, s: Double) = Pen(
        ink, width = width * s, cosmetic = false, dashed = off > 0.0, dashOn = on * s, dashGap = off * s,
    )

    private fun points(pts: DoubleArray, s: Double): List<Pt> {
        val out = ArrayList<Pt>(pts.size / 2)
        var i = 0
        while (i + 1 < pts.size) {
            out.add(Pt(pts[i] * s, pts[i + 1] * s))
            i += 2
        }
        return out
    }
}
