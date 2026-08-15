package com.xnotes.platform

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags

/**
 * A [Renderer] backed by an [android.graphics.Canvas]. The same implementation
 * draws to the on-screen view, into page-cache/thumbnail bitmaps, to PDF export
 * and to presentation frames.
 *
 * Only translation and uniform scaling are used; the cumulative scale is tracked
 * so a cosmetic pen's width stays constant in device pixels regardless of zoom.
 */
class AndroidRenderer(private val canvas: Canvas) : Renderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
    private val rasterBlendPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val layerPaint = Paint()

    private val scaleStack = ArrayDeque<Float>()
    private var scaleX = 1f
    private var scaleY = 1f
    private val avgScale get() = ((scaleX + scaleY) / 2f).coerceAtLeast(1e-4f)

    override fun save() {
        canvas.save()
        scaleStack.addLast(scaleX)
        scaleStack.addLast(scaleY)
    }

    override fun restore() {
        canvas.restore()
        if (scaleStack.isNotEmpty()) {
            scaleY = scaleStack.removeLast()
            scaleX = scaleStack.removeLast()
        }
    }

    override fun saveLayerAlpha(bounds: Rect, alpha: Double) {
        canvas.saveLayerAlpha(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            (alpha.coerceIn(0.0, 1.0) * 255).toInt(),
        )
        scaleStack.addLast(scaleX)
        scaleStack.addLast(scaleY)
    }

    // MULTIPLY uses the W3C separable blend (BlendMode, API 29+), which over a
    // transparent backdrop *deposits* the source and over ink *multiplies* — so it
    // works the same in the transparent ink cache and on the composed screen. Below
    // API 29 (and for SRC_OVER) we just use plain alpha compositing.
    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) {
        if (blend != BlendMode.MULTIPLY || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveLayerAlpha(bounds, alpha)
        }
        layerPaint.reset()
        layerPaint.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        layerPaint.blendMode = android.graphics.BlendMode.MULTIPLY
        canvas.saveLayer(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            layerPaint,
        )
        scaleStack.addLast(scaleX)
        scaleStack.addLast(scaleY)
    }

    override fun translate(dx: Double, dy: Double) {
        canvas.translate(dx.toFloat(), dy.toFloat())
    }

    override fun rotate(degrees: Double) {
        canvas.rotate(degrees.toFloat())
        // A quarter turn swaps which axis each scale factor acts on (cosmetic pen math).
        val d = ((degrees % 360.0) + 360.0) % 360.0
        if (d == 90.0 || d == 270.0) {
            val t = scaleX
            scaleX = scaleY
            scaleY = t
        }
    }

    override fun scale(sx: Double, sy: Double) {
        canvas.scale(sx.toFloat(), sy.toFloat())
        scaleX *= sx.toFloat()
        scaleY *= sy.toFloat()
    }

    override fun clipRect(rect: Rect) {
        canvas.clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }

    override fun clear() {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
    }

    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)

    override fun fillRect(rect: Rect, color: Rgba) {
        fillPaint.color = color.toArgb()
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), fillPaint)
    }

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.size < 3) return
        fillPaint.color = color.toArgb()
        canvas.drawPath(buildPath(points, close = true, rule), fillPaint)
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) {
        fillPaint.color = color.toArgb()
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), radius.toFloat(), fillPaint)
    }

    // The whole swept-disc ribbon as one path: every disc and bridging quad is wound the same way
    // under WINDING, so overlaps union into solid ink (anti-aliased only along the true outer
    // silhouette, no interior seams, no winding-cancelled gap at a sharp turn). One draw call keeps
    // the repainted-every-frame live stroke cheap. Built straight from the packed float geometry
    // (the [Geometry.ribbonQuad] math inlined) so a dense page allocates nothing per point.
    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, color: Rgba) =
        fillDiskRibbon(centers, radii, 0, minOf(centers.size / 2, radii.size), color)

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count <= 0) return
        val end = from + count
        fillPaint.color = color.toArgb()
        val path = Path().apply { fillType = Path.FillType.WINDING }
        for (i in from until end - 1) {
            val x0 = centers[2 * i]
            val y0 = centers[2 * i + 1]
            val x1 = centers[2 * i + 2]
            val y1 = centers[2 * i + 3]
            val dx = x1 - x0
            val dy = y1 - y0
            val len = kotlin.math.hypot(dx, dy)
            if (len < 1e-9f) continue
            val nx = -dy / len
            val ny = dx / len
            val r0 = radii[i]
            val r1 = radii[i + 1]
            val ax = x0 + nx * r0
            val ay = y0 + ny * r0
            val bx = x1 + nx * r1
            val by = y1 + ny * r1
            val cx = x1 - nx * r1
            val cy = y1 - ny * r1
            val ex = x0 - nx * r0
            val ey = y0 - ny * r0
            // Consistent (positive-area) winding so overlapping quads union rather than cancel.
            if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) >= 0f) {
                path.moveTo(ax, ay)
                path.lineTo(bx, by)
                path.lineTo(cx, cy)
                path.lineTo(ex, ey)
            } else {
                path.moveTo(ex, ey)
                path.lineTo(cx, cy)
                path.lineTo(bx, by)
                path.lineTo(ax, ay)
            }
            path.close()
        }
        for (i in from until end) {
            val r = radii[i]
            if (r > 0f) path.addCircle(centers[2 * i], centers[2 * i + 1], r, Path.Direction.CW)
        }
        canvas.drawPath(path, fillPaint)
    }

    // The blur radius is a page-space length: the canvas scale grows it with zoom,
    // so the halo tracks the ink (exactly like a non-cosmetic pen width). INNER blur
    // keeps the soft fill inside the shape (the white core); NORMAL spreads it both
    // ways (the outer halo).
    override fun fillPolygonGlow(pts: FloatArray, color: Rgba, rule: FillRule, blurRadius: Double, inner: Boolean) {
        if (pts.size < 6) return
        val path = buildPath(pts, close = true, rule)
        if (blurRadius <= 0.0) {
            fillPaint.color = color.toArgb()
            canvas.drawPath(path, fillPaint)
            return
        }
        glowPaint.color = color.toArgb()
        glowPaint.maskFilter = BlurMaskFilter(blurRadius.toFloat().coerceAtLeast(0.1f), blurStyle(inner))
        canvas.drawPath(path, glowPaint)
        glowPaint.maskFilter = null
    }

    override fun fillCircleGlow(center: Pt, radius: Double, color: Rgba, blurRadius: Double, inner: Boolean) {
        if (blurRadius <= 0.0) return fillCircle(center, radius, color)
        glowPaint.color = color.toArgb()
        glowPaint.maskFilter = BlurMaskFilter(blurRadius.toFloat().coerceAtLeast(0.1f), blurStyle(inner))
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), radius.toFloat(), glowPaint)
        glowPaint.maskFilter = null
    }

    private fun blurStyle(inner: Boolean) =
        if (inner) BlurMaskFilter.Blur.INNER else BlurMaskFilter.Blur.NORMAL

    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {
        fillPaint.color = color.toArgb()
        canvas.drawOval(
            (center.x - rx).toFloat(), (center.y - ry).toFloat(),
            (center.x + rx).toFloat(), (center.y + ry).toFloat(),
            fillPaint,
        )
    }

    override fun strokeRect(rect: Rect, pen: Pen) {
        applyPen(pen)
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), strokePaint)
    }

    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        applyPen(pen)
        canvas.drawPath(buildPath(points, close = false, FillRule.NONZERO), strokePaint)
    }

    override fun strokePolyline(pts: FloatArray, pen: Pen) = strokePolyline(pts, 0, pts.size / 2, pen)

    override fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) {
        if (count < 2) return
        applyPen(pen)
        val path = Path()
        path.moveTo(pts[2 * from], pts[2 * from + 1])
        for (i in 1 until count) path.lineTo(pts[2 * (from + i)], pts[2 * (from + i) + 1])
        canvas.drawPath(path, strokePaint)
    }

    override fun strokePolygon(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        applyPen(pen)
        canvas.drawPath(buildPath(points, close = true, FillRule.NONZERO), strokePaint)
    }

    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {
        applyPen(pen)
        canvas.drawOval(
            (center.x - rx).toFloat(), (center.y - ry).toFloat(),
            (center.x + rx).toFloat(), (center.y + ry).toFloat(),
            strokePaint,
        )
    }

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        val srcRect = src?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        val destRect = RectF(dest.left.toFloat(), dest.top.toFloat(), dest.right.toFloat(), dest.bottom.toFloat())
        canvas.drawBitmap(bmp, srcRect, destRect, bitmapPaint)
    }

    // MULTIPLY uses the W3C separable blend (API 29+); below that (and for SRC_OVER) it falls back
    // to plain alpha compositing, matching saveLayerBlended. Composites a pre-rendered highlighter
    // ribbon onto the live page each frame in one blit, no per-frame ribbon tessellation.
    override fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect?) {
        val bmp = (raster as? AndroidRasterSurface)?.bitmap ?: return
        if (bmp.isRecycled) return
        rasterBlendPaint.reset()
        rasterBlendPaint.isFilterBitmap = true
        rasterBlendPaint.isDither = true
        rasterBlendPaint.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        if (blend == BlendMode.MULTIPLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rasterBlendPaint.blendMode = android.graphics.BlendMode.MULTIPLY
        }
        val srcRect = src?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        val destRect = RectF(dest.left.toFloat(), dest.top.toFloat(), dest.right.toFloat(), dest.bottom.toFloat())
        canvas.drawBitmap(bmp, srcRect, destRect, rasterBlendPaint)
    }

    // Decode the source only to the destination's device-pixel size (capped, never upscaled past the
    // source) so a huge photo never fully decodes; the quarter turn is applied as a canvas rotation
    // about the destination centre, the rect already carrying the rotated (w/h-swapped) box.
    override fun drawImage(image: ImageData, dest: Rect, orientation: Int) {
        if (dest.w <= 0.0 || dest.h <= 0.0) return
        val devW = (dest.w * scaleX).toInt()
        val devH = (dest.h * scaleY).toInt()
        val o = ((orientation % 360) + 360) % 360
        val turned = o == 90 || o == 270
        val reqW = (if (turned) devH else devW).coerceIn(1, DECODE_CAP_PX)
        val reqH = (if (turned) devW else devH).coerceIn(1, DECODE_CAP_PX)
        val bmp = ImageDecoder.decodeSampledFile(image.file.path, reqW, reqH) ?: return
        val uw = (if (turned) dest.h else dest.w).toFloat()
        val uh = (if (turned) dest.w else dest.h).toFloat()
        canvas.save()
        canvas.translate(((dest.left + dest.right) / 2.0).toFloat(), ((dest.top + dest.bottom) / 2.0).toFloat())
        if (o != 0) canvas.rotate(o.toFloat())
        canvas.drawBitmap(bmp, null, RectF(-uw / 2f, -uh / 2f, uw / 2f, uh / 2f), bitmapPaint)
        canvas.restore()
    }

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
        if (text.isEmpty()) return
        val paint = AndroidText.textPaint(font, color.toArgb())
        val layout = AndroidText.layout(text, rect.w.toInt(), paint)
        canvas.save()
        canvas.translate(rect.left.toFloat(), rect.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {
        if (text.isEmpty()) return
        val paint = AndroidText.textPaint(font, color.toArgb())
        canvas.drawText(text, x.toFloat(), baseline.toFloat(), paint)
    }

    private fun applyPen(pen: Pen) {
        strokePaint.color = pen.color.toArgb()
        val width = if (pen.cosmetic) (pen.width / avgScale) else pen.width
        strokePaint.strokeWidth = width.toFloat()
        // A page-space outward blur for the neon halo on shape outlines (NORMAL = both sides).
        strokePaint.maskFilter = if (pen.glowRadius > 0.0) {
            BlurMaskFilter(pen.glowRadius.toFloat().coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
        strokePaint.pathEffect = if (pen.dashed) {
            // Cosmetic dashes (chrome) keep a fixed device-pixel rhythm; content dashes (the
            // dashed pen) are specified in content px and so scale with zoom like the ink.
            val s = if (pen.cosmetic) 1.0 / avgScale else 1.0
            val on = (pen.dashOn * s).toFloat().coerceAtLeast(0.1f)
            val off = (pen.dashGap * s).toFloat().coerceAtLeast(0.1f)
            DashPathEffect(floatArrayOf(on, off), (pen.dashPhase * s).toFloat())
        } else {
            null
        }
    }

    private fun buildPath(points: List<Pt>, close: Boolean, rule: FillRule): Path {
        val path = Path()
        path.fillType = if (rule == FillRule.EVEN_ODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) path.lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        if (close) path.close()
        return path
    }

    private fun buildPath(pts: FloatArray, close: Boolean, rule: FillRule): Path {
        val path = Path()
        path.fillType = if (rule == FillRule.EVEN_ODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        path.moveTo(pts[0], pts[1])
        for (i in 1 until pts.size / 2) path.lineTo(pts[2 * i], pts[2 * i + 1])
        if (close) path.close()
        return path
    }

    companion object {
        /** Long-edge cap (device px) for an on-screen image decode, so extreme zoom can't OOM. */
        private const val DECODE_CAP_PX = 4096
    }
}
