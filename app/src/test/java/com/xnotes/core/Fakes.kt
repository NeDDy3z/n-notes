package com.xnotes.core

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/** A Renderer that records the primitive calls it receives (for assertions). */
class FakeRenderer : Renderer {
    val ops = mutableListOf<String>()

    /** Ribbon runs as `from to count`, so a test can see which points a pass actually painted. */
    val ribbonRuns = mutableListOf<Pair<Int, Int>>()

    /** Dashed runs as `from to count` paired with the phase they started at. */
    val dashRuns = mutableListOf<Triple<Int, Int, Double>>()

    /** Page-space rects a raster was blitted into. */
    val rasterDests = mutableListOf<Rect>()

    /** Stroked polyline segments, as start/end pairs (the page ruling draws through these). */
    val segments = mutableListOf<Pair<Pt, Pt>>()

    /** Centres of filled circles (the dotted ruling). */
    val circles = mutableListOf<Pt>()

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        ribbonRuns += from to count
        super.fillDiskRibbon(centers, radii, from, count, color)
    }

    override fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) {
        dashRuns += Triple(from, count, pen.dashPhase)
        super.strokePolyline(pts, from, count, pen)
    }

    override fun save() { ops += "save" }
    override fun restore() { ops += "restore" }
    override fun saveLayerAlpha(bounds: Rect, alpha: Double) { ops += "saveLayerAlpha" }
    override fun translate(dx: Double, dy: Double) { ops += "translate" }
    override fun scale(sx: Double, sy: Double) { ops += "scale" }
    override fun clipRect(rect: Rect) { ops += "clipRect" }
    override fun clear() { ops += "clear" }
    override fun fillBackground(rect: Rect, color: Rgba) { ops += "fillBackground" }
    override fun fillRect(rect: Rect, color: Rgba) { ops += "fillRect" }
    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) { ops += "fillPolygon" }
    override fun fillCircle(center: Pt, radius: Double, color: Rgba) {
        ops += "fillCircle"
        circles += center
    }
    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) { ops += "fillEllipse" }
    override fun strokeRect(rect: Rect, pen: Pen) { ops += "strokeRect" }
    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        ops += "strokePolyline"
        for (i in 0 until points.size - 1) segments += points[i] to points[i + 1]
    }
    override fun strokePolygon(points: List<Pt>, pen: Pen) { ops += "strokePolygon" }
    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) { ops += "strokeEllipse" }
    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        ops += "drawRaster"
        rasterDests += dest
    }
    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double) { ops += "drawImage" }
    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) { ops += "drawText" }
    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {
        ops += "drawTextRun:$text@$x,$baseline"
    }
}

/** A bitmap-less raster surface for tests. */
class FakeRasterSurface(
    override val width: Int,
    override val height: Int,
    override val devicePixelRatio: Double = 1.0,
) : RasterSurface {
    /** One painter for the surface's life, so a test can read everything ever drawn into it. */
    val painter = FakeRenderer()
    var fills = 0
    var recycled = false

    override fun fill(color: Rgba) { fills++ }
    override fun renderer(): Renderer = painter
    override fun recycle() { recycled = true }
}

class FakeSurfaceFactory : SurfaceFactory {
    val created = mutableListOf<FakeRasterSurface>()

    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double) =
        FakeRasterSurface(widthPx, heightPx, devicePixelRatio).also { created += it }
}

/**
 * A measurer where each newline-separated line is one [lineHeight] tall and every
 * character advances a fixed 0.6pt, so layout tests are exact: ascent (1.0pt) +
 * descent (0.3pt) always equals [lineHeight] (1.3pt), matching the Android impl's
 * invariant.
 */
class FakeTextMeasurer(private val perPointLineHeight: Double = 1.3) : TextMeasurer {
    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val lines = maxOf(1, text.split('\n').size)
        return Rect(0.0, 0.0, wrapWidth, lines * lineHeight(font))
    }

    override fun lineHeight(font: FontSpec): Double = font.pointSize * perPointLineHeight

    override fun metrics(font: FontSpec): LineMetrics =
        LineMetrics(ascent = font.pointSize * (perPointLineHeight - 0.3), descent = font.pointSize * 0.3)

    override fun advances(text: String, font: FontSpec): DoubleArray =
        DoubleArray(text.length) { font.pointSize * ADVANCE_PER_POINT }

    companion object {
        const val ADVANCE_PER_POINT = 0.6
    }
}

/** A codec that produces fixed-size surfaces and tiny byte stand-ins. */
class FakeImageCodec : ImageCodec {
    var probeWidth = 64
    var probeHeight = 48
    override fun probeFile(path: String): ImageSize = ImageSize(probeWidth, probeHeight)
    override fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
}
