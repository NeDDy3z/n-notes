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
 *
 * Samples live in parallel primitive arrays, not a `List<Sample>`: a dense note holds millions of
 * them, and one boxed 4-double object per sample costs ~60 bytes against the 12 the numbers need.
 * Positions are **offsets from [ox]/[oy]**, a per-stroke double origin taken at pen-down, so a
 * float never has to carry a large absolute coordinate — the infinite canvas has no coordinate
 * bound, and float32 spacing reaches a whole pixel around 1e7. An offset spans one stroke, a few
 * hundred px at most, where float resolves to ~1e-5 px. [samples] is a read-only view over the
 * arrays for the cold call sites; the hot loops read [xAt]/[yAt]/[pAt] and allocate nothing.
 */
class Stroke(
    val tool: Tool,
    /** Scaled in place when the stroke is resized (var so [applyTransform] can swap a width-scaled
     *  copy); otherwise the immutable style snapshot captured at pen-down. */
    var config: ToolConfig,
    samples: List<Sample> = emptyList(),
    /** Content-px → dp scale captured at pen-down (zoom ÷ display density), so the speed
     *  pen judges gesture speed in zoom- and device-independent units. 1.0 = unscaled. */
    val speedScale: Double = 1.0,
    /** Straight-line mode: render as a raw segment (no position smoothing) so the line reaches
     *  exactly from the first sample to the last — the EMA low-pass otherwise pulls a 2-point
     *  stroke's far end to the midpoint, lagging it behind the stylus. */
    val straight: Boolean = false,
    /** Scale on every arc-length constant the engine measures the hand against: the low-pass
     *  lengths, the nib's head window and its widen/thin rates. Captured at pen-down
     *  as 1 ÷ zoom and capped at 1, so they are lengths of *gesture* rather than of page, and
     *  writing small at high zoom behaves as writing at 100% does. 1.0 = drawn at 100% zoom, and
     *  the default for anything built without a pen. */
    val smoothScale: Double = 1.0,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false

    /** Double origin the float offsets hang off; the first sample's position. */
    private var ox = 0.0
    private var oy = 0.0
    private var xa = EMPTY_F
    private var ya = EMPTY_F
    private var pa = EMPTY_F
    /** Milliseconds since the first sample, allocated only once a sample carries a non-zero time
     *  (only the speed pen records any), so ordinary ink pays nothing for the channel. */
    private var ta: FloatArray? = null
    private var n = 0

    /** The ribbon cache. Volatile because cache builds read it off the UI thread while
     *  [releaseGeometry] may be nulling it on the UI thread; [StrokeGeometry] is immutable, so a
     *  race costs at worst one redundant rebuild. */
    @Volatile
    private var cachedGeometry: StrokeGeometry? = null
    private var cachedRawBounds: Rect? = null
    private var cachedBounds: Rect? = null

    init {
        if (samples.isNotEmpty()) setSamples(samples)
    }

    /** Copy constructor: duplicates the sample arrays without materializing a single [Sample]. */
    constructor(src: Stroke) : this(src.tool, src.config, emptyList(), src.speedScale, src.straight, src.smoothScale) {
        ox = src.ox
        oy = src.oy
        n = src.n
        xa = src.xa.copyOf(n)
        ya = src.ya.copyOf(n)
        pa = src.pa.copyOf(n)
        ta = src.ta?.copyOf(n)
    }

    // --- sample access ---

    /** Number of raw samples. */
    val sampleCount get() = n

    fun xAt(i: Int): Double = ox + xa[i]
    fun yAt(i: Int): Double = oy + ya[i]
    fun pAt(i: Int): Double = pa[i].toDouble()
    fun tAt(i: Int): Double = ta?.get(i)?.toDouble() ?: 0.0

    fun sampleAt(i: Int): Sample = Sample(xAt(i), yAt(i), pAt(i), tAt(i))

    /** Read-only `List<Sample>` view for the call sites that still speak in [Sample]s. Each `get`
     *  builds a short-lived value; the hot loops use the primitive accessors instead. */
    val samples: List<Sample> get() = SampleView(this)

    private class SampleView(private val s: Stroke) : AbstractList<Sample>() {
        override val size get() = s.n
        override fun get(index: Int): Sample = s.sampleAt(index)
    }

    /** Replace every sample (pen-up reduction, undo restore, legacy compaction). */
    fun setSamples(list: List<Sample>) {
        val m = list.size
        ensure(m)
        n = m
        if (m == 0) {
            ta = null
            invalidate()
            return
        }
        val first = list[0]
        ox = first.x
        oy = first.y
        var timed = false
        for (i in 0 until m) if (list[i].t != 0.0) { timed = true; break }
        ta = if (timed) FloatArray(xa.size) else null
        for (i in 0 until m) {
            val s = list[i]
            xa[i] = (s.x - ox).toFloat()
            ya[i] = (s.y - oy).toFloat()
            pa[i] = s.pressure.toFloat()
            ta?.set(i, s.t.toFloat())
        }
        invalidate()
    }

    private fun ensure(capacity: Int) {
        if (xa.size >= capacity) return
        var c = if (xa.isEmpty()) INITIAL_CAPACITY else xa.size
        while (c < capacity) c *= 2
        xa = xa.copyOf(c)
        ya = ya.copyOf(c)
        pa = pa.copyOf(c)
        ta = ta?.copyOf(c)
    }

    /** False only while the pen is still down on this stroke: lift-time rules (the calligraphy
     *  dot swell, a stroke that never fills [StrokeEngine.HEAD_LEN]) are held off so the live
     *  preview can't open thick at every pen-down. Loaded, cloned and eraser-split strokes are
     *  complete. */
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

    val isEmpty get() = n == 0

    /** Lazily-built ribbon geometry; rebuilt only when samples change. */
    fun geometry(): StrokeGeometry {
        cachedGeometry?.let { return it }
        // Unpack to the doubles the engine works in. It allocates these three arrays anyway, so
        // reading the float storage here costs nothing over passing a list of boxed samples.
        val rx = DoubleArray(n)
        val ry = DoubleArray(n)
        val rp = DoubleArray(n)
        for (i in 0 until n) {
            rx[i] = ox + xa[i]
            ry[i] = oy + ya[i]
            rp[i] = pa[i].toDouble()
        }
        val rt = ta?.let { src -> DoubleArray(n) { src[it].toDouble() } }
        return StrokeEngine.build(
            rx, ry, rp, rt,
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
            smoothScale = smoothScale,
        ).also { cachedGeometry = it }
    }

    fun invalidate() {
        cachedGeometry = null
        cachedRawBounds = null
        cachedBounds = null
    }

    /**
     * Drop the ribbon but keep the bounds. Used by the canvas to reclaim geometry for pages that
     * scrolled out of the cached band. The two [Rect]s stay because [bounds] is what selection
     * reads, and rebuilding the whole ribbon to hand back a rectangle would cost more than the
     * ribbon saved. Not [invalidate]: nothing about the samples changed.
     */
    fun releaseGeometry() {
        cachedGeometry = null
    }

    /** True once [geometry] has been built and not since released; lets the canvas skip pages
     *  that never rendered instead of walking their items. */
    val hasGeometry get() = cachedGeometry != null

    fun addSample(s: Sample) {
        if (n == 0) {
            ox = s.x
            oy = s.y
        }
        ensure(n + 1)
        if (s.t != 0.0 && ta == null) ta = FloatArray(xa.size)
        xa[n] = (s.x - ox).toFloat()
        ya[n] = (s.y - oy).toFloat()
        pa[n] = s.pressure.toFloat()
        ta?.set(n, s.t.toFloat())
        n++
        invalidate()
    }

    /**
     * Straight-line tools: collapse to a single segment from the pen-down sample to [end],
     * replacing any prior moving endpoint. The first sample's origin stays fixed while the far
     * end tracks the pointer, so the live preview and committed stroke are one straight ribbon.
     */
    fun setStraightEnd(end: Sample) {
        if (n > 1) n = 1
        addSample(end)
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
        if (g.pointCount >= 2) {
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
    internal fun neonGlowRadius(): Double {
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
        val core = FloatArray(g.halfWidths.size) { (g.halfWidths[it] * NEON_CORE_FRAC).toFloat() }
        r.fillDiskRibbon(g.centerline, core, Rgba(255, 255, 255, 255))
    }

    /** One bloom pass: the blurred ribbon body plus its two rounded ends, composited once at [alpha]. */
    private fun paintBloom(r: Renderer, g: StrokeGeometry, color: Rgba, radius: Double, alpha: Double) {
        r.saveLayerAlpha(paintBounds(), alpha)
        if (g.outlineCount >= 3) r.fillPolygonGlow(g.outline, color, FillRule.NONZERO, radius)
        val n = g.pointCount
        if (n > 0) {
            if (g.hw(0) > 0.0) r.fillCircleGlow(Pt(g.cx(0), g.cy(0)), g.hw(0), color, radius)
            if (n > 1 && g.hw(n - 1) > 0.0) r.fillCircleGlow(Pt(g.cx(n - 1), g.cy(n - 1)), g.hw(n - 1), color, radius)
        }
        r.restore()
    }

    /** [c] lerped a fraction [t] toward white (alpha preserved). */


    override fun bounds(): Rect {
        cachedBounds?.let { return it }
        val g = geometry()
        if (g.pointCount == 0) {
            val b = if (n == 0) Rect(0.0, 0.0, 0.0, 0.0) else rawBounds()
            return b.also { cachedBounds = it }
        }
        // Bound the swept-disc ribbon by the discs themselves: each centre +/- its half-width holds
        // that sample's disc, and the bridging body never reaches past the discs it joins, so this
        // covers the round caps every pen now grows (the old outline box clipped them flat).
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until g.pointCount) {
            val cx = g.cx(i)
            val cy = g.cy(i)
            val h = g.hw(i)
            if (cx - h < minX) minX = cx - h
            if (cx + h > maxX) maxX = cx + h
            if (cy - h < minY) minY = cy - h
            if (cy + h > maxY) maxY = cy + h
        }
        return Rect(minX, minY, maxX - minX, maxY - minY).also { cachedBounds = it }
    }

    /** O(1): the samples are offsets, so moving the stroke only moves the origin they hang off. */
    override fun translate(dx: Double, dy: Double) {
        ox += dx
        oy += dy
        cachedGeometry = null
        cachedRawBounds = cachedRawBounds?.translate(dx, dy)
        cachedBounds = cachedBounds?.translate(dx, dy)
    }

    override fun snapshotGeometry(): GeometrySnapshot =
        StrokeSnapshot(ox, oy, xa.copyOf(n), ya.copyOf(n), pa.copyOf(n), ta?.copyOf(n), config)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is StrokeSnapshot) return
        ox = snap.ox
        oy = snap.oy
        n = snap.xs.size
        xa = snap.xs.copyOf()
        ya = snap.ys.copyOf()
        pa = snap.ps.copyOf()
        ta = snap.ts?.copyOf()
        config = snap.config
        invalidate()
    }

    /** Transform every sample and scale the width-bearing style fields (base width, taper, dash
     *  runs) by the transform's linear factor, so a resized stroke looks zoomed; a pure rotation
     *  (factor 1) leaves the width untouched. */
    override fun applyTransform(t: Affine) {
        if (n > 0) {
            // Re-origin on the transformed first sample so the offsets stay stroke-local; a rotation
            // about a far-away pivot would otherwise push them out to the pivot's magnitude.
            val first = t.apply(Pt(ox + xa[0], oy + ya[0]))
            for (i in 0 until n) {
                val p = t.apply(Pt(ox + xa[i], oy + ya[i]))
                xa[i] = (p.x - first.x).toFloat()
                ya[i] = (p.y - first.y).toFloat()
            }
            ox = first.x
            oy = first.y
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
        for (i in 0 until g.pointCount) {
            val h = g.hw(i)
            if (h <= 0.0) continue
            val dx = p.x - g.cx(i)
            val dy = p.y - g.cy(i)
            if (dx * dx + dy * dy <= h * h) return true
        }
        return g.outlineCount >= 3 && Geometry.pointInPolygon(g.outline, p)
    }

    /** Mean of the sample positions. */
    override fun centroid(): Pt {
        if (n == 0) return Pt.ZERO
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sx += xa[i]
            sy += ya[i]
        }
        return Pt(ox + sx / n, oy + sy / n)
    }

    /**
     * AABB of the *raw* input samples, cached and invalidated with the geometry.
     * Built in one allocation-free pass (no `samples.map { it.pos }`), so an eraser
     * sweep that bbox-rejects thousands of strokes per move stays cheap.
     */
    private fun rawBounds(): Rect {
        cachedRawBounds?.let { return it }
        var minX = xa[0]
        var minY = ya[0]
        var maxX = minX
        var maxY = minY
        for (i in 1 until n) {
            val x = xa[i]
            val y = ya[i]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        return Rect(ox + minX, oy + minY, (maxX - minX).toDouble(), (maxY - minY).toDouble())
            .also { cachedRawBounds = it }
    }

    /** Cheap sample test after a bounding-box reject (spec 02 §5.1). */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        if (n == 0) return false
        // Reject against the *raw* sample box (the smoothed geometry lags inward).
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return false
        // A straight stroke carries only its two endpoints, so a mid-line tap falls between
        // samples; test the segments they span (point-to-line) instead of the sample points.
        if (straight) {
            val c = Pt(cx, cy)
            if (n == 1) return c.distanceTo(Pt(xAt(0), yAt(0))) <= radius
            for (i in 1 until n) {
                val a = Pt(xAt(i - 1), yAt(i - 1))
                val b = Pt(xAt(i), yAt(i))
                if (Geometry.distancePointToSegment(c, a, b) <= radius) return true
            }
            return false
        }
        // Compare in offset space so the eraser sweep does no per-sample origin arithmetic.
        val lx = cx - ox
        val ly = cy - oy
        val r2 = radius * radius
        for (i in 0 until n) {
            val dx = xa[i] - lx
            val dy = ya[i] - ly
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
        if (n == 0) return null
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return null
        // A straight stroke is just two endpoints — it has no mid-line samples to split on, so any
        // contact erases the whole segment (consistent with how the eraser hit-tests it).
        if (straight) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val lx = cx - ox
        val ly = cy - oy
        val r2 = radius * radius
        var anyErased = false
        var runStart = -1
        val fragments = mutableListOf<Stroke>()
        for (i in 0 until n) {
            val dx = xa[i] - lx
            val dy = ya[i] - ly
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
        if (runStart >= 0) fragments.add(fragment(runStart, n))
        return if (anyErased) fragments else null
    }

    /** A new stroke from samples `[from, to)`, copied so it shares no backing storage. */
    private fun fragment(from: Int, to: Int): Stroke {
        val m = to - from
        val s = Stroke(tool, config, emptyList(), speedScale, straight, smoothScale)
        // Re-origin on the fragment's own first sample; the offsets stay small either way, but this
        // keeps a fragment indistinguishable from a stroke drawn where it sits.
        s.ox = ox + xa[from]
        s.oy = oy + ya[from]
        s.n = m
        s.xa = FloatArray(m) { xa[from + it] - xa[from] }
        s.ya = FloatArray(m) { ya[from + it] - ya[from] }
        s.pa = pa.copyOfRange(from, to)
        s.ta = ta?.copyOfRange(from, to)
        return s
    }

    companion object {
        const val KIND = "stroke"

        private val EMPTY_F = FloatArray(0)

        /** First allocation for a live stroke; grows by doubling from here. */
        private const val INITIAL_CAPACITY = 32

        /** [c] lerped a fraction [t] toward white (alpha preserved). */
        internal fun lighten(c: Rgba, t: Double): Rgba {
            val f = t.coerceIn(0.0, 1.0)
            return Rgba(
                (c.r + (255 - c.r) * f).toInt(),
                (c.g + (255 - c.g) * f).toInt(),
                (c.b + (255 - c.b) * f).toInt(),
                c.a,
            )
        }

        /** Neon bloom: two stacked NORMAL-blur passes, a wide faint halo under a
         *  tighter brighter one. Radius = base_width * (MIN + SPAN * neonStrength),
         *  floored in page px; alpha = MIN + SPAN * neonStrength. */
        internal const val NEON_BLOOM_WIDE_FACTOR_MIN = 1.8
        internal const val NEON_BLOOM_WIDE_FACTOR_SPAN = 5.0
        internal const val NEON_BLOOM_WIDE_MIN = 6.0
        internal const val NEON_BLOOM_WIDE_ALPHA_MIN = 0.0
        internal const val NEON_BLOOM_WIDE_ALPHA_SPAN = 0.42

        internal const val NEON_BLOOM_TIGHT_FACTOR_MIN = 0.7
        internal const val NEON_BLOOM_TIGHT_FACTOR_SPAN = 1.8
        internal const val NEON_BLOOM_TIGHT_MIN = 2.5
        internal const val NEON_BLOOM_TIGHT_ALPHA_MIN = 0.0
        internal const val NEON_BLOOM_TIGHT_ALPHA_SPAN = 0.85

        /** Fraction of the tube width filled by the solid white-hot core. */
        internal const val NEON_CORE_FRAC = 0.3

        /** Body colour lifted this fraction toward white so the tube reads as lit. */
        internal const val NEON_BODY_LIGHTEN = 0.10
    }
}

/** Snapshot of a stroke's transformable geometry: the sample path and the width-bearing style.
 *  Holds the packed arrays rather than a list of [Sample]s, so a resize drag snapshotting a big
 *  selection every frame does not allocate one object per sample. */
private class StrokeSnapshot(
    val ox: Double,
    val oy: Double,
    val xs: FloatArray,
    val ys: FloatArray,
    val ps: FloatArray,
    val ts: FloatArray?,
    val config: ToolConfig,
) : GeometrySnapshot
