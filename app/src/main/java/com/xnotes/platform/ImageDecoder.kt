package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.util.Svg
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil

/**
 * Decodes an encoded image file downsampled to a target box, so a large photo is never fully decoded
 * into memory and its bytes are never slurped into the heap. Shared by [AndroidImageCodec], the
 * on-screen [AndroidRenderer] and PDF export: each asks only for the pixels its destination needs.
 * SVG files take the same route (probed and rasterized on demand via AndroidSVG), so a vector image
 * also lives on disk only and re-renders sharp at whatever resolution each draw asks for.
 * Stateless; safe to call from any thread.
 */
object ImageDecoder {

    /** Native pixel size of the image at [path] (bounds-only decode, or the SVG's intrinsic
     *  size / viewBox), or null if unreadable. */
    fun probeFile(path: String): ImageSize? =
        if (isVector(path)) parseSvg(path)?.let { svgSize(it) } else probeRaster(path)

    /** Decode the image at [path] no larger than [maxWidth]×[maxHeight] (aspect kept), or null.
     *  Rasters never upscale past their native pixels; an SVG renders to fill the box exactly. */
    fun decodeSampledFile(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val mw = maxWidth.coerceAtLeast(1)
        val mh = maxHeight.coerceAtLeast(1)
        if (isVector(path)) return renderSvg(path, mw, mh)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sw, sh, mw, mh)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        if (bmp.width <= mw && bmp.height <= mh) return bmp
        // inSampleSize only halves, so a result can still exceed the box: scale the rest of the way.
        val scale = minOf(mw.toDouble() / bmp.width, mh.toDouble() / bmp.height)
        val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val th = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** True when [path] holds a vector (SVG) source with no native pixel resolution. */
    fun isVector(path: String): Boolean = Svg.isSvgFile(File(path))

    private fun probeRaster(path: String): ImageSize? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) ImageSize(o.outWidth, o.outHeight) else null
    }

    private fun parseSvg(path: String): SVG? =
        runCatching { FileInputStream(path).use { SVG.getFromInputStream(it) } }.getOrNull()

    /** An SVG's intrinsic pixel size: width/height attributes, else the viewBox, else a square. */
    private fun svgSize(svg: SVG): ImageSize {
        var w = runCatching { svg.documentWidth }.getOrDefault(-1f)
        var h = runCatching { svg.documentHeight }.getOrDefault(-1f)
        if (w <= 0f || h <= 0f) {
            val vb = svg.documentViewBox
            if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                w = vb.width()
                h = vb.height()
            } else {
                w = DEFAULT_SVG_SIZE
                h = DEFAULT_SVG_SIZE
            }
        }
        return ImageSize(ceil(w).toInt().coerceAtLeast(1), ceil(h).toInt().coerceAtLeast(1))
    }

    private fun renderSvg(path: String, maxW: Int, maxH: Int): Bitmap? {
        val svg = parseSvg(path) ?: return null
        val size = svgSize(svg)
        val scale = minOf(maxW.toDouble() / size.width, maxH.toDouble() / size.height)
        val tw = (size.width * scale).toInt().coerceAtLeast(1)
        val th = (size.height * scale).toInt().coerceAtLeast(1)
        // Without a viewBox the content wouldn't scale to the viewport, so synthesize one.
        if (svg.documentViewBox == null) {
            runCatching { svg.setDocumentViewBox(0f, 0f, size.width.toFloat(), size.height.toFloat()) }
        }
        return runCatching {
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bmp), RectF(0f, 0f, tw.toFloat(), th.toFloat()))
            bmp
        }.getOrNull()
    }

    /** Largest power-of-two sample step that keeps the decoded size at or above the requested box. */
    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var ss = 1
        while (w / (ss * 2) >= reqW && h / (ss * 2) >= reqH) ss *= 2
        return ss
    }

    private const val DEFAULT_SVG_SIZE = 512f
}
