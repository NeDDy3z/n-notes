package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.ShapeKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An editable geometric shape (spec 02 §5.4): open (line/arrow, two endpoints)
 * or closed (rectangle/ellipse/triangle, drawn inside the normalized AABB of
 * start/end). Polygon/polyline additionally carry a vertex list in [points].
 * Closed shapes are stroked and optionally filled. Shapes erase like ink: whole
 * on contact in STROKE mode, and cut into outline fragments by the AREA eraser.
 */
class ShapeItem(
    var shape: ShapeKind,
    var start: Pt,
    var end: Pt,
    var strokeRgba: Rgba,
    var strokeWidth: Double = 3.0,
    var fillRgba: Rgba? = null,
    /** Render the outline as a glowing neon tube (halo + white-hot core). */
    var neon: Boolean = false,
    /** Glow intensity in [0, 1] (halo size + brightness); used only when [neon]. */
    var neonStrength: Double = 0.6,
    /**
     * Vertices for [ShapeKind.POLYGON]/[ShapeKind.POLYLINE], stored normalized to the
     * unit box so they scale with [start]/[end] for free; null for every other kind.
     */
    var points: List<Pt>? = null,
    /** Stroke the outline dashed (dash/gap run lengths in content px). */
    var dashed: Boolean = false,
    var dashLength: Double = 10.0,
    var dashGap: Double = 8.0,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true

    /** Normalized AABB of the two drag points. */
    val box: Rect get() = Rect.fromPoints(start, end)

    /** Polygon/polyline vertices mapped from normalized storage into content space. */
    internal fun absPoints(): List<Pt> {
        val pts = points ?: return emptyList()
        val b = box
        return pts.map { Pt(b.left + it.x * b.w, b.top + it.y * b.h) }
    }

    /** Absolute (content-space) vertices for polygon/polyline kinds; null for the rest. */
    fun vertices(): List<Pt>? = if (points == null) null else absPoints()

    private fun pen() =
        Pen(color = strokeRgba, width = strokeWidth, cosmetic = false, dashed = dashed, dashOn = dashLength, dashGap = dashGap)

    /** Triangle vertices: apex at top-edge midpoint, base along the bottom edge. */
    internal fun triangleVertices(): List<Pt> {
        val b = box
        return listOf(Pt(b.centerX, b.top), Pt(b.left, b.bottom), Pt(b.right, b.bottom))
    }

    /** Open ">" arrowhead as the chevron polyline barbLeft -> tip -> barbRight (stroked, not filled),
     *  sized from the stroke width. The tip sits just past [end] so the point clears the shaft's
     *  round cap and reads slightly forward instead of buried under the line end. */
    internal fun arrowHead(): List<Pt> {
        val dir = (end - start).normalized()
        if (dir.length() < 1e-9) return emptyList()
        val headLen = max(12.0, strokeWidth * 3.5)
        val tip = end + dir * (strokeWidth * 0.5)
        val back = tip - dir * headLen
        val perp = dir.perp() * (headLen * 0.5)
        return listOf(back + perp, tip, back - perp)
    }

    internal fun ellipsePolygon(segments: Int = 48): List<Pt> {
        val b = box
        val cx = b.centerX
        val cy = b.centerY
        val rx = b.w / 2.0
        val ry = b.h / 2.0
        return (0 until segments).map {
            val a = 2.0 * PI * it / segments
            Pt(cx + rx * cos(a), cy + ry * sin(a))
        }
    }

    override fun paint(r: Renderer) {
        if (neon) return paintNeon(r)
        fillRgba?.let { drawFill(r, it) }
        drawOutline(r, pen())
        if (shape == ShapeKind.ARROW) drawArrowHead(r, pen())
    }

    /** Fill the closed-shape interior (no-op for open line/arrow). */
    private fun drawFill(r: Renderer, fill: Rgba) {
        val b = box
        when (shape) {
            ShapeKind.RECTANGLE -> r.fillRect(b, fill)
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> r.fillEllipse(b.center, b.w / 2.0, b.h / 2.0, fill)
            ShapeKind.TRIANGLE -> r.fillPolygon(triangleVertices(), fill)
            ShapeKind.POLYGON -> r.fillPolygon(absPoints(), fill)
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.POLYLINE, ShapeKind.CURVE -> {}
        }
    }

    /** Stroke the shape's outline (the arrow's shaft for arrows) with [pen]. */
    private fun drawOutline(r: Renderer, pen: Pen) {
        val b = box
        when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> r.strokePolyline(listOf(start, end), pen)
            ShapeKind.RECTANGLE -> r.strokeRect(b, pen)
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> r.strokeEllipse(b.center, b.w / 2.0, b.h / 2.0, pen)
            ShapeKind.TRIANGLE -> r.strokePolygon(triangleVertices(), pen)
            ShapeKind.POLYGON -> r.strokePolygon(absPoints(), pen)
            ShapeKind.POLYLINE, ShapeKind.CURVE -> r.strokePolyline(absPoints(), pen)
        }
    }

    /** Stroke the open ">" arrowhead with [pen], mirroring the shaft so the head and shaft share one tube.
     *  The chevron is always solid: a dash break on the short barbs would maim the point. */
    private fun drawArrowHead(r: Renderer, pen: Pen) {
        val head = arrowHead()
        if (head.size == 3) r.strokePolyline(head, pen.copy(dashed = false))
    }

    /**
     * Neon: the outline as a glowing glass tube — a blurred ink-colour **halo**
     * (composited once at a glow-intensity alpha so corners don't blow out), the
     * opaque colour **body**, then a thinner **white-hot core** down the centre so
     * the colour reads at the tube's edges. [neonStrength] scales the halo only.
     */
    private fun neonGlowRadius(): Double {
        val s = neonStrength.coerceIn(0.0, 1.0)
        return (strokeWidth * (GLOW_FACTOR_MIN + GLOW_FACTOR_SPAN * s)).coerceAtLeast(GLOW_MIN)
    }

    override fun paintBounds(): Rect =
        if (neon) bounds().outset(neonGlowRadius() * 2.0 + 4.0)
        else bounds()

    private fun paintNeon(r: Renderer) {
        val glowR = neonGlowRadius()
        val s = neonStrength.coerceIn(0.0, 1.0)
        val color = strokeRgba.withAlpha(255)
        val white = Rgba(255, 255, 255, 255)
        val glowAlpha = GLOW_ALPHA_MIN + GLOW_ALPHA_SPAN * s
        val coreW = (strokeWidth * CORE_WIDTH_FRAC).coerceAtLeast(CORE_WIDTH_MIN)

        fillRgba?.let { drawFill(r, it) }

        // 1) Outer halo (ink colour), bounded in its own glow-alpha layer.
        r.saveLayerAlpha(paintBounds(), glowAlpha)
        val haloPen = pen().copy(color = color, glowRadius = glowR)
        drawOutline(r, haloPen)
        if (shape == ShapeKind.ARROW) drawArrowHead(r, haloPen)
        r.restore()

        // 2) Tube body — saturated colour shows at the rim.
        drawOutline(r, pen())
        if (shape == ShapeKind.ARROW) drawArrowHead(r, pen())

        // 3) White-hot core — a thinner white line down the centre of the shaft and the chevron.
        val corePen = pen().copy(color = white, width = coreW)
        drawOutline(r, corePen)
        if (shape == ShapeKind.ARROW) drawArrowHead(r, corePen)
    }

    override fun bounds(): Rect {
        val pad = strokeWidth / 2.0 + 1.0
        return when (shape) {
            ShapeKind.LINE -> Rect.fromPoints(start, end).outset(pad)
            ShapeKind.ARROW -> Rect.bounding(listOf(start, end) + arrowHead()).outset(pad)
            else -> box.outset(pad)
        }
    }

    override fun translate(dx: Double, dy: Double) {
        start = Pt(start.x + dx, start.y + dy)
        end = Pt(end.x + dx, end.y + dy)
    }

    override fun contains(p: Pt): Boolean {
        val tol = max(strokeWidth / 2.0, HIT_TOLERANCE)
        return when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> Geometry.distancePointToSegment(p, start, end) <= tol
            ShapeKind.RECTANGLE -> if (fillRgba != null) box.contains(p) else nearRectOutline(p, tol)
            ShapeKind.TRIANGLE -> {
                val v = triangleVertices()
                if (fillRgba != null) Geometry.pointInPolygon(v, p) else nearPolyOutline(v, p, tol)
            }
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> {
                val poly = ellipsePolygon()
                if (fillRgba != null) Geometry.pointInPolygon(poly, p) else nearPolyOutline(poly, p, tol)
            }
            ShapeKind.POLYGON -> {
                val v = absPoints()
                if (fillRgba != null) Geometry.pointInPolygon(v, p) else nearPolyOutline(v, p, tol)
            }
            ShapeKind.POLYLINE, ShapeKind.CURVE -> nearPolyOutline(absPoints(), p, tol, closed = false)
        }
    }

    private fun nearRectOutline(p: Pt, tol: Double): Boolean {
        val b = box
        val corners = listOf(
            Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom),
        )
        return nearPolyOutline(corners, p, tol)
    }

    private fun nearPolyOutline(verts: List<Pt>, p: Pt, tol: Double, closed: Boolean = true): Boolean {
        if (verts.isEmpty()) return false
        val edges = if (closed) verts.size else verts.size - 1
        for (i in 0 until edges) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            if (Geometry.distancePointToSegment(p, a, b) <= tol) return true
        }
        return false
    }

    override fun centroid(): Pt = bounds().center

    /** True if an eraser circle of [radius] at (cx,cy) touches the shape's geometry. */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        val p = Pt(cx, cy)
        if (bounds().distanceTo(p) > radius) return false // cheap AABB reject
        val tol = radius + strokeWidth / 2.0
        return when (shape) {
            ShapeKind.LINE, ShapeKind.ARROW -> Geometry.distancePointToSegment(p, start, end) <= tol
            ShapeKind.RECTANGLE ->
                if (fillRgba != null && box.contains(p)) true else nearRectOutline(p, tol)
            ShapeKind.TRIANGLE -> {
                val v = triangleVertices()
                if (fillRgba != null && Geometry.pointInPolygon(v, p)) true else nearPolyOutline(v, p, tol)
            }
            ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> {
                val poly = ellipsePolygon()
                if (fillRgba != null && Geometry.pointInPolygon(poly, p)) true else nearPolyOutline(poly, p, tol)
            }
            ShapeKind.POLYGON -> {
                val v = absPoints()
                if (fillRgba != null && Geometry.pointInPolygon(v, p)) true else nearPolyOutline(v, p, tol)
            }
            ShapeKind.POLYLINE, ShapeKind.CURVE -> nearPolyOutline(absPoints(), p, tol, closed = false)
        }
    }

    /**
     * AREA-erase: the outline parts that survive an eraser circle (page-local [cx], [cy],
     * [radius]), cut cleanly at the circle boundary — the same contract as [Stroke.erasedBy]:
     *  - `null`      — untouched (keep the original)
     *  - empty list  — remove the whole shape
     *  - fragments   — the surviving outline runs, as polyline shapes keeping this style
     * A filled shape erases whole on any hit: a cut outline cannot hold its fill.
     */
    fun erasedBy(cx: Double, cy: Double, radius: Double): List<ShapeItem>? {
        val c = Pt(cx, cy)
        if (bounds().distanceTo(c) > radius) return null
        if (fillRgba != null) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val verts = when (shape) {
            // The arrow clips by its shaft, like its hit tests; a cut arrow loses its head.
            ShapeKind.LINE, ShapeKind.ARROW -> listOf(start, end)
            else -> currentOutline()
        }
        if (verts.size < 2) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val closed = shape.isClosed
        val pts = if (closed) verts + verts.first() else verts

        var touched = false
        val runs = mutableListOf<MutableList<Pt>>()
        var current = mutableListOf<Pt>()
        fun flush() {
            if (current.size >= 2 && polylineLength(current) > 1e-6) runs.add(current)
            current = mutableListOf()
        }
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val outside = outsideSpans(a, b, c, radius)
            if (outside.size == 1 && outside[0].first == 0.0 && outside[0].second == 1.0) {
                if (current.isEmpty()) current.add(a)
                current.add(b)
                continue
            }
            touched = true
            if (outside.isEmpty()) { // the whole segment sits inside the circle
                flush()
                continue
            }
            for ((ta, tb) in outside) {
                if (ta == 0.0) {
                    if (current.isEmpty()) current.add(a)
                } else {
                    flush()
                    current.add(lerp(a, b, ta))
                }
                current.add(if (tb == 1.0) b else lerp(a, b, tb))
                if (tb < 1.0) flush()
            }
        }
        flush()
        if (!touched) return null

        // A closed outline cut away from the seam vertex leaves the seam intact in two runs;
        // join them so the survivor is one continuous polyline instead of splitting at v0.
        if (closed && runs.size >= 2) {
            val first = runs.first()
            val last = runs.last()
            if (first.first().distanceTo(pts.first()) < 1e-9 && last.last().distanceTo(pts.first()) < 1e-9) {
                runs.removeAt(runs.size - 1)
                runs[0] = (last + first.drop(1)).toMutableList()
            }
        }
        return runs.map {
            poly(
                ShapeKind.POLYLINE, it, strokeRgba, strokeWidth, null, neon, neonStrength,
                dashed, dashLength, dashGap,
            )
        }
    }

    /** Sub-intervals of the segment [a]→[b] (as fractions) lying outside the circle at [c]. */
    private fun outsideSpans(a: Pt, b: Pt, c: Pt, r: Double): List<Pair<Double, Double>> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val fx = a.x - c.x
        val fy = a.y - c.y
        val qa = dx * dx + dy * dy
        if (qa < 1e-12) return if (fx * fx + fy * fy > r * r) listOf(0.0 to 1.0) else emptyList()
        val qb = 2.0 * (fx * dx + fy * dy)
        val qc = fx * fx + fy * fy - r * r
        val disc = qb * qb - 4.0 * qa * qc
        if (disc <= 0.0) return listOf(0.0 to 1.0) // misses (or grazes) the segment's line
        val sq = sqrt(disc)
        val t1 = (-qb - sq) / (2.0 * qa)
        val t2 = (-qb + sq) / (2.0 * qa)
        if (t2 <= 0.0 || t1 >= 1.0) return listOf(0.0 to 1.0) // chord lies beyond the segment span
        val spans = mutableListOf<Pair<Double, Double>>()
        if (t1 > 0.0) spans.add(0.0 to min(t1, 1.0))
        if (t2 < 1.0) spans.add(max(t2, 0.0) to 1.0)
        return spans
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun polylineLength(pts: List<Pt>): Double {
        var len = 0.0
        for (i in 0 until pts.size - 1) len += pts[i].distanceTo(pts[i + 1])
        return len
    }

    override fun geometry(): GeoHandle = ShapeHandle(start, end)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is ShapeHandle) {
            start = handle.start
            end = handle.end
        }
    }

    override fun snapshotGeometry(): GeometrySnapshot = ShapeSnapshot(shape, start, end, points, strokeWidth)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is ShapeSnapshot) return
        shape = snap.shape
        start = snap.start
        end = snap.end
        points = snap.points
        strokeWidth = snap.strokeWidth
    }

    /**
     * Bake a transform into the shape. A line/arrow keeps its kind (only its two endpoints move).
     * A pure scale keeps every kind parametric (the box scales; normalized vertices follow). A
     * rotation can't be held by an axis-aligned box, so a rotated rectangle/ellipse/triangle is
     * converted to a [ShapeKind.POLYGON] (closed) or [ShapeKind.POLYLINE] (open) of baked
     * vertices. The outline width scales by the transform's linear factor (1.0 for a rotation).
     */
    override fun applyTransform(t: Affine) {
        strokeWidth *= t.linearScale
        if (shape.isEndpointShape || t.isAxisAligned) {
            start = t.apply(start)
            end = t.apply(end)
            return
        }
        val verts = currentOutline().map { t.apply(it) }
        val bb = Rect.bounding(verts)
        shape = if (shape.isClosed) ShapeKind.POLYGON else ShapeKind.POLYLINE
        start = bb.topLeft
        end = Pt(bb.right, bb.bottom)
        points = normalize(verts, bb)
    }

    /** Content-space vertices of the current outline; used to bake a rotation into a vertex list. */
    private fun currentOutline(): List<Pt> = when (shape) {
        ShapeKind.POLYGON, ShapeKind.POLYLINE, ShapeKind.CURVE -> absPoints()
        ShapeKind.TRIANGLE -> triangleVertices()
        ShapeKind.ELLIPSE, ShapeKind.CIRCLE -> ellipsePolygon()
        else -> {
            val b = box
            listOf(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom))
        }
    }

    companion object {
        const val KIND = "shape"
        const val HIT_TOLERANCE = 6.0

        /** Build a polygon/polyline from absolute [vertices], stored normalized to their box. */
        fun poly(
            shape: ShapeKind,
            vertices: List<Pt>,
            strokeRgba: Rgba,
            strokeWidth: Double = 3.0,
            fillRgba: Rgba? = null,
            neon: Boolean = false,
            neonStrength: Double = 0.6,
            dashed: Boolean = false,
            dashLength: Double = 10.0,
            dashGap: Double = 8.0,
        ): ShapeItem {
            val box = Rect.bounding(vertices)
            return ShapeItem(
                shape, box.topLeft, Pt(box.right, box.bottom), strokeRgba, strokeWidth,
                fillRgba, neon, neonStrength, normalize(vertices, box), dashed, dashLength, dashGap,
            )
        }

        private fun normalize(vertices: List<Pt>, box: Rect): List<Pt> {
            val w = if (box.w > 1e-9) box.w else 1.0
            val h = if (box.h > 1e-9) box.h else 1.0
            return vertices.map { Pt((it.x - box.left) / w, (it.y - box.top) / h) }
        }

        /** Halo blur = stroke_width × (MIN + SPAN × neonStrength), floored in page px. */
        private const val GLOW_FACTOR_MIN = 1.2
        private const val GLOW_FACTOR_SPAN = 2.6
        private const val GLOW_MIN = 4.0

        /** Halo opacity = MIN + SPAN × neonStrength. */
        private const val GLOW_ALPHA_MIN = 0.25
        private const val GLOW_ALPHA_SPAN = 0.55

        /** White-hot core line width as a fraction of the stroke width, with a page-px floor. */
        private const val CORE_WIDTH_FRAC = 0.4
        private const val CORE_WIDTH_MIN = 1.0
    }
}

/** Snapshot of a shape's transformable geometry (kind can change when a box shape is rotated). */
private data class ShapeSnapshot(
    val shape: ShapeKind,
    val start: Pt,
    val end: Pt,
    val points: List<Pt>?,
    val strokeWidth: Double,
) : GeometrySnapshot
