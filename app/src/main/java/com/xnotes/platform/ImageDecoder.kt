package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.util.Svg
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Decodes an encoded image file downsampled to a target box, so a large photo is never fully decoded
 * into memory and its bytes are never slurped into the heap. Shared by [AndroidImageCodec], the
 * on-screen [AndroidRenderer] and PDF export: each asks only for the pixels its destination needs.
 * SVG files take the same route (probed and rasterized on demand via AndroidSVG), so a vector image
 * also lives on disk only and re-renders sharp at whatever resolution each draw asks for. Because a
 * complex SVG is expensive to parse and paint — and lifted (selected) items repaint every frame —
 * SVGs are served from two bounded LRUs: the parsed document, and rasterizations at power-of-two
 * size buckets (so a drag, a repeated sharpen, or a small zoom step is a plain bitmap blit).
 * Safe to call from any thread; both caches are thread-safe and memory-capped.
 */
object ImageDecoder {

    /** Native pixel size of the image at [path] (bounds-only decode, or the SVG's intrinsic
     *  size / viewBox), or null if unreadable. */
    fun probeFile(path: String): ImageSize? =
        if (isVector(path)) cachedSvg(path)?.let { svgSize(it) } else probeRaster(path)

    /** Decode the image at [path] for a [maxWidth]×[maxHeight] box (aspect kept), or null.
     *  Rasters stay within the box and never upscale past their native pixels; an SVG returns the
     *  cached power-of-two bucket covering the box (possibly larger — callers scale into place). */
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

    // Parsed SVG documents by path: parsing a big flowchart's XML can dwarf painting it, and image
    // files are immutable temp files, so the path alone is a sound key. Count-bounded (a parsed DOM
    // has no cheap byte size); stale entries for deleted temp files just age out.
    private val svgCache = LruCache<String, SVG>(4)

    // Rasterized SVGs by path + bucket. Byte-bounded against the heap; entries are never recycled
    // (callers may still be drawing them), eviction leaves them to the GC.
    private val svgBitmapCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L shl 20) shr 10).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun cachedSvg(path: String): SVG? {
        svgCache.get(path)?.let { return it }
        val svg = runCatching { FileInputStream(path).use { SVG.getFromInputStream(it) } }.getOrNull() ?: return null
        // Without a viewBox the content wouldn't scale to the render viewport, so synthesize one.
        if (svg.documentViewBox == null) {
            val s = svgSize(svg)
            runCatching { svg.setDocumentViewBox(0f, 0f, s.width.toFloat(), s.height.toFloat()) }
        }
        svgCache.put(path, svg)
        return svg
    }

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

    // Renders at the power-of-two bucket whose long side covers the requested fit (capped), so
    // nearby request sizes share one cached bitmap and get scaled down at draw time. A complex SVG
    // is painted at most once per bucket it ever crosses; every later draw is a cache hit.
    private fun renderSvg(path: String, maxW: Int, maxH: Int): Bitmap? {
        val svg = cachedSvg(path) ?: return null
        val size = svgSize(svg)
        val fit = minOf(maxW.toDouble() / size.width, maxH.toDouble() / size.height)
        val longSide = (max(size.width, size.height) * fit).toInt().coerceAtLeast(1)
        val bucket = pow2AtLeast(longSide).coerceAtMost(VECTOR_RENDER_CAP_PX)
        val key = "$path#$bucket"
        svgBitmapCache.get(key)?.let { return it }
        val s = bucket.toDouble() / max(size.width, size.height)
        val bw = (size.width * s).toInt().coerceAtLeast(1)
        val bh = (size.height * s).toInt().coerceAtLeast(1)
        val bmp = runCatching {
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            // Concurrent renders of one parsed document are serialized (its render state is shared).
            synchronized(svg) { svg.renderToCanvas(Canvas(b), RectF(0f, 0f, bw.toFloat(), bh.toFloat())) }
            b
        }.getOrNull() ?: return null
        svgBitmapCache.put(key, bmp)
        return bmp
    }

    private fun pow2AtLeast(v: Int): Int {
        var p = 16
        while (p < v) p = p shl 1
        return p
    }

    /** Largest power-of-two sample step that keeps the decoded size at or above the requested box. */
    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var ss = 1
        while (w / (ss * 2) >= reqW && h / (ss * 2) >= reqH) ss *= 2
        return ss
    }

    private const val DEFAULT_SVG_SIZE = 512f

    // Matches the raster decode caps used by the screen renderer and PDF export.
    private const val VECTOR_RENDER_CAP_PX = 4096
}
