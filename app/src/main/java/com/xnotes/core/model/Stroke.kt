package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

/**
 * The unit of inking (spec 02 §5.1). Holds the raw samples plus a snapshot of
 * the tool style; the ribbon geometry is derived from the samples on demand and
 * cached until the samples change.
 */
class Stroke(
    val tool: Tool,
    /** Scaled in place when the stroke is resized (var so [applyTransform] can swap a width-scaled
     *  copy); otherwise the immutable style snapshot captured at pen-down. */
    var config: ToolConfig,
    val samples: MutableList<Sample> = mutableListOf(),
    /** Content-px → dp scale captured at pen-down (zoom ÷ display density), so the speed
     *  pen judges gesture speed in zoom- and device-independent units. 1.0 = unscaled. */
    val speedScale: Double = 1.0,
    /** Straight-line mode: render as a raw segment (no position smoothing) so the line reaches
     *  exactly from the first sample to the last — the EMA low-pass otherwise pulls a 2-point
     *  stroke's far end to the midpoint, lagging it behind the stylus. */
    val straight: Boolean = false,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false

    private var cachedGeometry: StrokeGeometry? = null
    private var cachedRawBounds: Rect? = null
    private var cachedBounds: Rect? = null

    /** False only while the pen is still down on this stroke: lift-time rules (the calligraphy
     *  dot swell, [StrokeEngine.DOT_MAX_LEN]) are held off so the live preview can't open thick
     *  at every pen-down. Loaded, cloned and eraser-split strokes are complete. */
    var finished = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Ink colour with the tool's alpha scale applied (the highlighter uses its configurable
     *  [ToolConfig.highlighterAlpha]; every other tool is opaque, scale 1.0). */
    val renderColor get() = config.rgba.scaleAlpha(
        if (tool == Tool.HIGHLIGHTER) config.highlighterAlpha else tool.alphaScale,
    )

    val isEmpty get() = samples.isEmpty()

    /** Lazily-built ribbon geometry; rebuilt only when samples change. */
    fun geometry(): StrokeGeometry {
        cachedGeometry?.let { return it }
        return StrokeEngine.build(
            samples,
            config.baseWidth,
            config.pressureEnabled,
            config.pressureMinFactor,
            config.directionStrength,
            config.speedStrength,
            config.taperEnabled,
            config.taperMinFactor,
            speedScale,
            smooth = !straight,
            holdEnds = tool == Tool.PEN || tool == Tool.HIGHLIGHTER,
            finished = finished,
        ).also { cachedGeometry = it }
    }

    fun invalidate() {
        cachedGeometry = null
        cachedRawBounds = null
        cachedBounds = null
    }

    fun addSample(s: Sample) {
        samples.add(s)
        invalidate()
    }

    /**
     * Straight-line tools: collapse to a single segment from the pen-down sample to [end],
     * replacing any prior moving endpoint. The first sample's origin stays fixed while the far
     * end tracks the pointer, so the live preview and committed stroke are one straight ribbon.
     */
    fun setStraightEnd(end: Sample) {
        while (samples.size > 1) samples.removeAt(samples.size - 1)
        samples.add(end)
        invalidate()
    }

    override fun paint(r: Renderer) {
        val g = geometry()
        val color = renderColor
        when {
            // The dashed pen draws its (uniform-width) centreline as a dashed, round-capped
            // line rather than a solid ribbon; the full ribbon geometry is still used for
            // bounds/hit-testing/erasing, so the whole line stays selectable through the gaps.
            tool == Tool.DASHED -> paintDashed(r, g, color)
            // The highlighter never glows (a translucent marker; glow is meaningless there).
            config.neon && tool != Tool.HIGHLIGHTER -> paintNeon(r, g, color)
            color.a >= 255 -> {
                // Opaque ink: draw ribbon + caps directly.
                paintFills(r, g, color)
            }
            else -> {
                // Translucent ink: accumulate the whole stroke opaquely in a layer, then
                // composite once at the ink's alpha, so the cap/ribbon and self-overlaps
                // don't compound into darker patches. The highlighter composites with
                // MULTIPLY so it tints light areas but can't lighten dark ink underneath
                // (text stays legible); other translucent inks blend normally.
                val blend = if (tool == Tool.HIGHLIGHTER) BlendMode.MULTIPLY else BlendMode.SRC_OVER
                r.saveLayerBlended(bounds().outset(2.0), color.a / 255.0, blend)
                paintFills(r, g, color.withAlpha(255))
                r.restore()
            }
        }
    }

    // The ribbon is a circular brush disc swept down the centreline: a disc at every sample plus
    // the body bridging them. Round caps and joins come for free on every pen, and the union of
    // convex pieces can't hole the way one self-overlapping outline does at a sharp turn.
    private fun paintFills(r: Renderer, g: StrokeGeometry, color: Rgba) {
        r.fillDiskRibbon(g.centerline, g.halfWidths, color)
    }

    /**
     * Paint just the opaque highlighter ribbon (full alpha, no blend) into [r]. The canvas
     * pre-renders this once into a bitmap and composites it live at [renderColor]'s alpha with
     * MULTIPLY, so a self-overlapping highlight isn't re-tessellated every frame.
     */
    fun paintHighlighterRibbon(r: Renderer) {
        paintFills(r, geometry(), renderColor.withAlpha(255))
    }

    /**
     * The dashed pen: a constant-width, round-capped dashed line traced down the smoothed
     * centreline (so its rounded dashes match the tool's icon). Dash/gap runs are in content
     * px so they scale with zoom like the ink. A single tap (no line) is drawn as a dot.
     */
    private fun paintDashed(r: Renderer, g: StrokeGeometry, color: Rgba) {
        if (g.centerline.size >= 2) {
            r.strokePolyline(
                g.centerline,
                Pen(
                    color = color,
                    width = config.baseWidth,
                    cosmetic = false,
                    dashed = true,
                    dashOn = config.dashLength,
                    dashGap = config.dashGap,
                ),
            )
        } else {
            r.fillDiskRibbon(g.centerline, g.halfWidths, color)
        }
    }

    /**
     * Neon as a laser trail. Four layers, back to front:
     *   1. a wide, faint outer bloom (the blurred ribbon in the ink colour);
     *   2. a tighter, brighter bloom that keeps the colour saturated at the line;
     *   3. the tube body: the saturated colour, lifted slightly toward white so it
     *      reads as lit;
     *   4. the white-hot core: a thin *solid* white inner ribbon (no blur, so it
     *      reaches full white even on a 1px line instead of smudging out).
     * Each bloom composites once at a glow-intensity alpha so a self-overlapping
     * scribble can't compound into a darker blob. [config.neonStrength] scales the
     * bloom's size and brightness; it overrides the translucent path, so neon works
     * on any stroke tool.
     */
    private fun neonGlowRadius(): Double {
        val s = config.neonStrength.coerceIn(0.0, 1.0)
        return (config.baseWidth * (NEON_BLOOM_WIDE_FACTOR_MIN + NEON_BLOOM_WIDE_FACTOR_SPAN * s))
            .coerceAtLeast(NEON_BLOOM_WIDE_MIN)
    }

    override fun paintBounds(): Rect =
        if (config.neon && tool != Tool.HIGHLIGHTER) bounds().outset(neonGlowRadius() * 2 + 4)
        else bounds()

    private fun paintNeon(r: Renderer, g: StrokeGeometry, color: Rgba) {
        val s = config.neonStrength.coerceIn(0.0, 1.0)
        val body = color.withAlpha(255)

        // 1) Wide, faint outer bloom.
        paintBloom(r, g, body, neonGlowRadius(), NEON_BLOOM_WIDE_ALPHA_MIN + NEON_BLOOM_WIDE_ALPHA_SPAN * s)

        // 2) Tight, brighter bloom: saturated colour hugging the line.
        val tightR = (config.baseWidth * (NEON_BLOOM_TIGHT_FACTOR_MIN + NEON_BLOOM_TIGHT_FACTOR_SPAN * s))
            .coerceAtLeast(NEON_BLOOM_TIGHT_MIN)
        paintBloom(r, g, body, tightR, NEON_BLOOM_TIGHT_ALPHA_MIN + NEON_BLOOM_TIGHT_ALPHA_SPAN * s)

        // 3) Tube body, lifted slightly toward white so it reads as lit.
        paintFills(r, g, lighten(body, NEON_BODY_LIGHTEN))

        // 4) Solid white-hot core (no blur, so thin lines still read pure white): the same swept
        //    disc at a fraction of the width, so it rounds with the body on every pen.
        r.fillDiskRibbon(g.centerline, g.halfWidths.map { it * NEON_CORE_FRAC }, Rgba(255, 255, 255, 255))
    }

    /** One bloom pass: the blurred ribbon body plus its two rounded ends, composited once at [alpha]. */
    private fun paintBloom(r: Renderer, g: StrokeGeometry, color: Rgba, radius: Double, alpha: Double) {
        r.saveLayerAlpha(paintBounds(), alpha)
        if (g.outline.size >= 3) r.fillPolygonGlow(g.outline, color, FillRule.NONZERO, radius)
        val cl = g.centerline
        if (cl.isNotEmpty()) {
            if (g.halfWidths.first() > 0.0) r.fillCircleGlow(cl.first(), g.halfWidths.first(), color, radius)
            if (cl.size > 1 && g.halfWidths.last() > 0.0) r.fillCircleGlow(cl.last(), g.halfWidths.last(), color, radius)
        }
        r.restore()
    }

    /** [c] lerped a fraction [t] toward white (alpha preserved). */
    private fun lighten(c: Rgba, t: Double): Rgba {
        val f = t.coerceIn(0.0, 1.0)
        return Rgba(
            (c.r + (255 - c.r) * f).toInt(),
            (c.g + (255 - c.g) * f).toInt(),
            (c.b + (255 - c.b) * f).toInt(),
            c.a,
        )
    }

    override fun bounds(): Rect {
        cachedBounds?.let { return it }
        val g = geometry()
        if (g.centerline.isEmpty()) {
            val b = if (samples.isEmpty()) Rect(0.0, 0.0, 0.0, 0.0) else rawBounds()
            return b.also { cachedBounds = it }
        }
        // Bound the swept-disc ribbon by the discs themselves: each centre +/- its half-width holds
        // that sample's disc, and the bridging body never reaches past the discs it joins, so this
        // covers the round caps every pen now grows (the old outline box clipped them flat).
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in g.centerline.indices) {
            val c = g.centerline[i]
            val h = g.halfWidths.getOrElse(i) { 0.0 }
            if (c.x - h < minX) minX = c.x - h
            if (c.x + h > maxX) maxX = c.x + h
            if (c.y - h < minY) minY = c.y - h
            if (c.y + h > maxY) maxY = c.y + h
        }
        return Rect(minX, minY, maxX - minX, maxY - minY).also { cachedBounds = it }
    }

    override fun translate(dx: Double, dy: Double) {
        for (i in samples.indices) {
            val s = samples[i]
            samples[i] = s.copy(x = s.x + dx, y = s.y + dy)
        }
        invalidate()
    }

    override fun snapshotGeometry(): GeometrySnapshot = StrokeSnapshot(samples.toList(), config)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is StrokeSnapshot) return
        samples.clear()
        samples.addAll(snap.samples)
        config = snap.config
        invalidate()
    }

    /** Transform every sample and scale the width-bearing style fields (base width, taper, dash
     *  runs) by the transform's linear factor, so a resized stroke looks zoomed; a pure rotation
     *  (factor 1) leaves the width untouched. */
    override fun applyTransform(t: Affine) {
        for (i in samples.indices) {
            val s = samples[i]
            val p = t.apply(Pt(s.x, s.y))
            samples[i] = s.copy(x = p.x, y = p.y)
        }
        val k = t.linearScale
        if (k != 1.0) {
            config = config.copy(
                baseWidth = config.baseWidth * k,
                dashLength = config.dashLength * k,
                dashGap = config.dashGap * k,
            )
        }
        invalidate()
    }

    /** `p` within the swept disc: inside any sample's disc (catching the dot, ends and joins) or
     *  inside the ribbon body. */
    override fun contains(p: Pt): Boolean {
        val g = geometry()
        for (i in g.centerline.indices) {
            val h = g.halfWidths.getOrElse(i) { 0.0 }
            if (h > 0.0 && p.distanceTo(g.centerline[i]) <= h) return true
        }
        return g.outline.size >= 3 && Geometry.pointInPolygon(g.outline, p)
    }

    /** Mean of the sample positions. */
    override fun centroid(): Pt {
        if (samples.isEmpty()) return Pt.ZERO
        var sx = 0.0
        var sy = 0.0
        for (s in samples) {
            sx += s.x
            sy += s.y
        }
        return Pt(sx / samples.size, sy / samples.size)
    }

    /**
     * AABB of the *raw* input samples, cached and invalidated with the geometry.
     * Built in one allocation-free pass (no `samples.map { it.pos }`), so an eraser
     * sweep that bbox-rejects thousands of strokes per move stays cheap.
     */
    private fun rawBounds(): Rect {
        cachedRawBounds?.let { return it }
        var minX = samples[0].x
        var minY = samples[0].y
        var maxX = minX
        var maxY = minY
        for (i in 1 until samples.size) {
            val s = samples[i]
            if (s.x < minX) minX = s.x else if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y else if (s.y > maxY) maxY = s.y
        }
        return Rect(minX, minY, maxX - minX, maxY - minY).also { cachedRawBounds = it }
    }

    /** Cheap sample test after a bounding-box reject (spec 02 §5.1). */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        if (samples.isEmpty()) return false
        // Reject against the *raw* sample box (the smoothed geometry lags inward).
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return false
        // A straight stroke carries only its two endpoints, so a mid-line tap falls between
        // samples; test the segments they span (point-to-line) instead of the sample points.
        if (straight) {
            val c = Pt(cx, cy)
            if (samples.size == 1) return c.distanceTo(Pt(samples[0].x, samples[0].y)) <= radius
            for (i in 1 until samples.size) {
                val a = Pt(samples[i - 1].x, samples[i - 1].y)
                val b = Pt(samples[i].x, samples[i].y)
                if (Geometry.distancePointToSegment(c, a, b) <= radius) return true
            }
            return false
        }
        val r2 = radius * radius
        for (s in samples) {
            val dx = s.x - cx
            val dy = s.y - cy
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    /**
     * AREA-erase: the surviving fragments after an eraser circle (page-local [cx], [cy], [radius])
     * passes over this stroke. A sample is erased when within [radius] of the centre — the same
     * point test as [intersectsCircle], so a stroke this splits is exactly one [intersectsCircle]
     * reports as hit. Surviving samples are partitioned into maximal contiguous runs; each run
     * becomes a new stroke sharing this stroke's tool/config/speedScale.
     *  - `null`      — no sample erased (keep the original untouched)
     *  - empty list  — every sample erased (remove the whole stroke)
     *  - one stroke  — an end was trimmed, or a hole left a single run
     *  - two or more — a mid-stroke hole split it
     */
    fun erasedBy(cx: Double, cy: Double, radius: Double): List<Stroke>? {
        if (samples.isEmpty()) return null
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return null
        // A straight stroke is just two endpoints — it has no mid-line samples to split on, so any
        // contact erases the whole segment (consistent with how the eraser hit-tests it).
        if (straight) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val r2 = radius * radius
        var anyErased = false
        var runStart = -1
        val fragments = mutableListOf<Stroke>()
        for (i in samples.indices) {
            val s = samples[i]
            val dx = s.x - cx
            val dy = s.y - cy
            if (dx * dx + dy * dy <= r2) {
                anyErased = true
                if (runStart >= 0) {
                    fragments.add(fragment(runStart, i))
                    runStart = -1
                }
            } else if (runStart < 0) {
                runStart = i
            }
        }
        if (runStart >= 0) fragments.add(fragment(runStart, samples.size))
        return if (anyErased) fragments else null
    }

    /** A new stroke from samples `[from, to)`, copied so it shares no backing storage. */
    private fun fragment(from: Int, to: Int): Stroke =
        Stroke(tool, config, samples.subList(from, to).toMutableList(), speedScale, straight)

    companion object {
        const val KIND = "stroke"

        /** Neon bloom: two stacked NORMAL-blur passes, a wide faint halo under a
         *  tighter brighter one. Radius = base_width * (MIN + SPAN * neonStrength),
         *  floored in page px; alpha = MIN + SPAN * neonStrength. */
        private const val NEON_BLOOM_WIDE_FACTOR_MIN = 1.8
        private const val NEON_BLOOM_WIDE_FACTOR_SPAN = 5.0
        private const val NEON_BLOOM_WIDE_MIN = 6.0
        private const val NEON_BLOOM_WIDE_ALPHA_MIN = 0.0
        private const val NEON_BLOOM_WIDE_ALPHA_SPAN = 0.42

        private const val NEON_BLOOM_TIGHT_FACTOR_MIN = 0.7
        private const val NEON_BLOOM_TIGHT_FACTOR_SPAN = 1.8
        private const val NEON_BLOOM_TIGHT_MIN = 2.5
        private const val NEON_BLOOM_TIGHT_ALPHA_MIN = 0.0
        private const val NEON_BLOOM_TIGHT_ALPHA_SPAN = 0.85

        /** Fraction of the tube width filled by the solid white-hot core. */
        private const val NEON_CORE_FRAC = 0.3

        /** Body colour lifted this fraction toward white so the tube reads as lit. */
        private const val NEON_BODY_LIGHTEN = 0.10
    }
}

/** Snapshot of a stroke's transformable geometry: the sample path and the width-bearing style. */
private data class StrokeSnapshot(val samples: List<Sample>, val config: ToolConfig) : GeometrySnapshot
