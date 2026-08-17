package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.vector.Affine
import com.xnotes.core.vector.GlyphOutliner
import com.xnotes.core.vector.GlyphStyle
import com.xnotes.core.vector.GradientStop
import com.xnotes.core.vector.LineCap
import com.xnotes.core.vector.LineJoin
import com.xnotes.core.vector.SpreadMethod
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorPaint
import com.xnotes.core.vector.VectorPath
import com.xnotes.core.vector.VectorScene
import com.xnotes.core.vector.VectorSeg
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads an SVG file into a [VectorScene]: paths in paint order, every transform already applied.
 *
 * Ours rather than a capture of a platform renderer's draw calls, for one decisive reason. Android
 * exposes no way to read a `Shader`'s gradient stops back, so a capture can see that a fill is a
 * gradient but never which gradient. It also gives us feature-level knowledge, which is what lets
 * an unsupported construct be named in a log line rather than quietly drawing wrong.
 *
 * DOM-based and JVM-testable, the same trick [FlowXml] uses. Reading is forgiving throughout:
 * unknown elements are skipped, a malformed attribute takes its default, and a file that will not
 * parse at all loads empty rather than throwing.
 */
object SvgReader {

    /**
     * [bytes] as a scene. [glyphs] turns any text in the file into outlines; without one, text is
     * named as unsupported and left out, which is what keeps this parser testable off-device.
     */
    fun parse(bytes: ByteArray, glyphs: GlyphOutliner? = null): VectorScene {
        val root = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = true
                    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                    runCatching { isExpandEntityReferences = false }
                }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
                .documentElement
        }.getOrNull() ?: return VectorScene.EMPTY
        if (localName(root) != "svg") return VectorScene.EMPTY
        return Reader(root, glyphs).read()
    }

    // --- the walk ---

    private class Reader(private val root: Element, private val glyphs: GlyphOutliner?) {

        private val paths = ArrayList<VectorPath>()
        private val skipped = LinkedHashSet<String>()
        private val byId = HashMap<String, Element>()
        private val css = CssRules()
        private var used = 0
        private var useDepth = 0

        // The document box, which percentage lengths in user space resolve against.
        private var width = DEFAULT_SIZE
        private var height = DEFAULT_SIZE
        private val diagonal: Double
            get() = kotlin.math.sqrt(width * width + height * height) / kotlin.math.sqrt(2.0)

        fun read(): VectorScene {
            index(root)
            css.collect(root)
            val viewBox = numbers(attr(root, "viewBox"))
            val vb = if (viewBox.size == 4 && viewBox[2] > 0.0 && viewBox[3] > 0.0) {
                Rect(viewBox[0], viewBox[1], viewBox[2], viewBox[3])
            } else {
                null
            }
            val w = length(attr(root, "width"), vb?.w ?: DEFAULT_SIZE)
            val h = length(attr(root, "height"), vb?.h ?: DEFAULT_SIZE)
            width = (if (w > 0.0) w else vb?.w ?: DEFAULT_SIZE).coerceAtLeast(1e-6)
            height = (if (h > 0.0) h else vb?.h ?: DEFAULT_SIZE).coerceAtLeast(1e-6)
            val ctm = viewBoxTransform(vb, width, height, attr(root, "preserveAspectRatio"))
            walk(root, ctm, Style.ROOT)
            return VectorScene(width, height, paths, skipped)
        }

        /** Every element carrying an id, so `use` and `clip-path` can find their target. */
        private fun index(el: Element) {
            attr(el, "id")?.let { byId.putIfAbsent(it, el) }
            for (child in children(el)) index(child)
        }

        /**
         * The root viewBox mapped onto the document's own width and height, honouring
         * `preserveAspectRatio`. Only `none` and the `meet` alignments turn up in practice; `slice`
         * would crop the artboard, which no exporter emits.
         */
        private fun viewBoxTransform(vb: Rect?, width: Double, height: Double, par: String?): Affine {
            if (vb == null) return Affine.IDENTITY
            val spec = (par ?: "").trim().lowercase()
            if (spec.startsWith("none")) {
                return Affine.scale(width / vb.w, height / vb.h).times(Affine.translate(-vb.left, -vb.top))
            }
            val s = minOf(width / vb.w, height / vb.h)
            val alignX = if (spec.contains("xmax")) 1.0 else if (spec.contains("xmin")) 0.0 else 0.5
            val alignY = if (spec.contains("ymax")) 1.0 else if (spec.contains("ymin")) 0.0 else 0.5
            val tx = (width - vb.w * s) * alignX
            val ty = (height - vb.h * s) * alignY
            return Affine.translate(tx, ty)
                .times(Affine.scale(s, s))
                .times(Affine.translate(-vb.left, -vb.top))
        }

        private fun walk(el: Element, parentCtm: Affine, parentStyle: Style) {
            for (child in children(el)) {
                if (paths.size >= MAX_PATHS) {
                    skipped.add("more than $MAX_PATHS paths")
                    return
                }
                visit(child, parentCtm, parentStyle)
            }
        }

        private fun visit(el: Element, parentCtm: Affine, parentStyle: Style) {
            val name = localName(el)
            if (name in NEVER_DRAWN) return
            val inherited = parentStyle.inherit(el, css)
            if (!inherited.visible) return
            nameUnbuilt(el, name)
            val ctm = parentCtm.times(transform(attr(el, "transform")))
            val style = clipped(inherited, el, ctm)
            when (name) {
                "g", "a", "switch" -> walk(el, ctm, style)
                "svg" -> walk(el, ctm, style) // a nested viewport, treated as a plain group
                "use" -> expandUse(el, ctm, style)
                "path" -> emit(SvgPathData.parse(attr(el, "d") ?: ""), ctm, style)
                "rect" -> emit(rect(el), ctm, style)
                "circle" -> emit(ellipse(el, "r", "r"), ctm, style)
                "ellipse" -> emit(ellipse(el, "rx", "ry"), ctm, style)
                "line" -> emit(line(el), ctm, style)
                "polyline" -> emit(poly(el, close = false), ctm, style)
                "polygon" -> emit(poly(el, close = true), ctm, style)
                "text" -> emitText(el, ctm, style)
                "tspan", "textPath" -> Unit // laid out by the enclosing text element
                "image" -> skipped.add("image")
                "foreignObject" -> skipped.add("foreignObject")
                else -> Unit
            }
        }

        /**
         * Name whatever this element asks for that the pipeline composites approximately or not at
         * all. All three need the same thing to be exact: the subtree rendered into its own buffer
         * and composited once, which is the one part of the ladder this canvas has not climbed.
         *
         * Named where they are used, not where they are defined: a filter or a mask lives inside
         * `defs`, which is never walked. The element still draws, unfiltered and unmasked, because
         * losing a drop shadow is a far smaller loss than losing the card it sits under.
         */
        private fun nameUnbuilt(el: Element, name: String) {
            if (property(el, "filter") != null) skipped.add("filter")
            if (property(el, "mask") != null) skipped.add("mask")
            // Opacity on a group has to composite the whole subtree once. Applying it per shape
            // instead is exact until two of them overlap, so it is only worth naming for a group
            // that holds more than one thing.
            if (name != "g" && name != "a" && name != "svg") return
            val own = property(el, "opacity")?.let { Style.alpha(it) } ?: return
            if (own < 1.0 && children(el).count { localName(it) !in NEVER_DRAWN } > 1) {
                skipped.add("group opacity")
            }
        }

        /** A property from the element's own `style` attribute, else its presentation attribute. */
        private fun property(el: Element, name: String): String? =
            Style.inlineStyle(attr(el, "style"))[name] ?: attr(el, name)

        /** `use` draws its target again, offset by x/y, under the referring element's own style. */
        private fun expandUse(el: Element, ctm: Affine, style: Style) {
            // A file can point a `use` at its own ancestor; the depth cap is what stops that
            // recursing forever, and the total cap stops a legal but enormous expansion.
            if (useDepth >= MAX_USE_DEPTH || used >= MAX_USE_EXPANSIONS) {
                skipped.add("deeply nested use")
                return
            }
            val href = (attr(el, "href") ?: attrNs(el, XLINK, "href"))?.trim() ?: return
            if (!href.startsWith("#")) return
            val target = byId[href.substring(1)] ?: return
            used++
            useDepth++
            val at = ctm.times(Affine.translate(length(attr(el, "x"), 0.0), length(attr(el, "y"), 0.0)))
            // A referenced symbol or group draws its children; anything else draws itself.
            if (localName(target) == "symbol" || localName(target) == "svg") {
                walk(target, at, style)
            } else {
                visit(target, at, style)
            }
            useDepth--
        }

        // --- shapes ---

        private fun rect(el: Element): List<VectorContour> {
            val x = length(attr(el, "x"), 0.0)
            val y = length(attr(el, "y"), 0.0)
            val w = length(attr(el, "width"), 0.0)
            val h = length(attr(el, "height"), 0.0)
            if (w <= 0.0 || h <= 0.0) return emptyList()
            var rx = length(attr(el, "rx"), -1.0)
            var ry = length(attr(el, "ry"), -1.0)
            if (rx < 0.0 && ry < 0.0) {
                return listOf(polygonOf(listOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))))
            }
            if (rx < 0.0) rx = ry
            if (ry < 0.0) ry = rx
            rx = rx.coerceIn(0.0, w / 2.0)
            ry = ry.coerceIn(0.0, h / 2.0)
            val kx = rx * KAPPA
            val ky = ry * KAPPA
            // Clockwise from the top edge, each corner stated as where it starts and ends and which
            // way the outline is travelling at each. The direction is what places the control
            // points, so writing it down is what stops a corner being built inside out.
            val segs = listOf(
                VectorSeg.Line(Pt(x + w - rx, y)),
                corner(Pt(x + w - rx, y), 1.0, 0.0, Pt(x + w, y + ry), 0.0, 1.0, kx, ky),
                VectorSeg.Line(Pt(x + w, y + h - ry)),
                corner(Pt(x + w, y + h - ry), 0.0, 1.0, Pt(x + w - rx, y + h), -1.0, 0.0, kx, ky),
                VectorSeg.Line(Pt(x + rx, y + h)),
                corner(Pt(x + rx, y + h), -1.0, 0.0, Pt(x, y + h - ry), 0.0, -1.0, kx, ky),
                VectorSeg.Line(Pt(x, y + ry)),
                corner(Pt(x, y + ry), 0.0, -1.0, Pt(x + rx, y), 1.0, 0.0, kx, ky),
            )
            return listOf(VectorContour(Pt(x + rx, y), segs, closed = true))
        }

        /**
         * One rounded corner as the cubic that approximates a quarter ellipse: from [from], leaving
         * along ([fx], [fy]), to [to], arriving along ([tx], [ty]). The first control point runs
         * forward from the start and the second runs *back* from the end, which is why the arriving
         * direction is subtracted.
         */
        private fun corner(
            from: Pt,
            fx: Double,
            fy: Double,
            to: Pt,
            tx: Double,
            ty: Double,
            kx: Double,
            ky: Double,
        ) = VectorSeg.Cubic(
            Pt(from.x + fx * kx, from.y + fy * ky),
            Pt(to.x - tx * kx, to.y - ty * ky),
            to,
        )

        private fun ellipse(el: Element, rxName: String, ryName: String): List<VectorContour> {
            val cx = length(attr(el, "cx"), 0.0)
            val cy = length(attr(el, "cy"), 0.0)
            val rx = length(attr(el, rxName), 0.0)
            val ry = length(attr(el, ryName), 0.0)
            if (rx <= 0.0 || ry <= 0.0) return emptyList()
            val kx = rx * KAPPA
            val ky = ry * KAPPA
            val segs = listOf(
                VectorSeg.Cubic(Pt(cx + rx, cy + ky), Pt(cx + kx, cy + ry), Pt(cx, cy + ry)),
                VectorSeg.Cubic(Pt(cx - kx, cy + ry), Pt(cx - rx, cy + ky), Pt(cx - rx, cy)),
                VectorSeg.Cubic(Pt(cx - rx, cy - ky), Pt(cx - kx, cy - ry), Pt(cx, cy - ry)),
                VectorSeg.Cubic(Pt(cx + kx, cy - ry), Pt(cx + rx, cy - ky), Pt(cx + rx, cy)),
            )
            return listOf(VectorContour(Pt(cx + rx, cy), segs, closed = true))
        }

        private fun line(el: Element): List<VectorContour> {
            val a = Pt(length(attr(el, "x1"), 0.0), length(attr(el, "y1"), 0.0))
            val b = Pt(length(attr(el, "x2"), 0.0), length(attr(el, "y2"), 0.0))
            return listOf(VectorContour(a, listOf(VectorSeg.Line(b)), closed = false))
        }

        private fun poly(el: Element, close: Boolean): List<VectorContour> {
            val n = numbers(attr(el, "points"))
            if (n.size < 4) return emptyList()
            val pts = ArrayList<Pt>(n.size / 2)
            var i = 0
            while (i + 1 < n.size) {
                pts.add(Pt(n[i], n[i + 1]))
                i += 2
            }
            return listOf(VectorContour(pts[0], pts.drop(1).map { VectorSeg.Line(it) }, close))
        }

        private fun polygonOf(pts: List<Pt>) =
            VectorContour(pts[0], pts.drop(1).map { VectorSeg.Line(it) }, closed = true)

        // --- emit ---

        private fun emit(contours: List<VectorContour>, ctm: Affine, style: Style) {
            if (contours.isEmpty()) return
            val box = controlBox(contours)
            val fill = paint(style.fill ?: "black", style.fillOpacity, style, ctm, box)
            val stroke = if (style.strokeWidth > 0.0) {
                paint(style.stroke, style.strokeOpacity, style, ctm, box)
            } else {
                null
            }
            if (fill == null && stroke == null) return
            val scale = ctm.lengthScale()
            paths.add(
                VectorPath(
                    contours = contours.map { transformContour(it, ctm) },
                    fill = fill,
                    fillRule = style.fillRule,
                    stroke = stroke,
                    strokeWidth = style.strokeWidth * scale,
                    cap = style.cap,
                    join = style.join,
                    miterLimit = style.miterLimit,
                    dash = style.dash?.map { it * scale }?.toDoubleArray(),
                    dashOffset = style.dashOffset * scale,
                    clip = style.clip,
                ),
            )
        }

        // --- text ---

        /**
         * A `text` element laid out and outlined. The layout is the practical subset: absolute and
         * relative positions, nested `tspan`s, and `text-anchor` per chunk, which is what a diagram
         * label uses. Anything beyond that is named rather than guessed at.
         */
        private fun emitText(el: Element, ctm: Affine, style: Style) {
            val outliner = glyphs ?: run {
                skipped.add("text")
                return
            }
            val cursor = Cursor(length(attr(el, "x"), 0.0, width), length(attr(el, "y"), 0.0, height))
            val chunks = ArrayList<Chunk>()
            chunks.add(Chunk(cursor.x, cursor.y, style.textAnchor))
            gather(el, style, cursor, chunks)
            for (chunk in chunks) {
                if (chunk.runs.isEmpty()) continue
                val total = chunk.runs.sumOf { outliner.measure(it.text, it.style.glyphStyle()) }
                var pen = chunk.x - when (chunk.anchor) {
                    "middle" -> total / 2.0
                    "end" -> total
                    else -> 0.0
                }
                for (run in chunk.runs) {
                    val glyphs = outliner.outline(run.text, run.style.glyphStyle())
                    if (glyphs == null || glyphs.contours.isEmpty()) {
                        pen += outliner.measure(run.text, run.style.glyphStyle())
                        continue
                    }
                    val at = ctm.times(Affine.translate(pen + run.dx, chunk.y + run.dy))
                    emit(glyphs.contours, at, run.style)
                    pen += glyphs.advance
                }
            }
        }

        /** Where the next run starts, carried across nested `tspan`s. */
        private class Cursor(var x: Double, var y: Double)

        /** One run of characters, with the style and offsets in force where it appeared. */
        private class TextRun(val text: String, val style: Style, val dx: Double, val dy: Double)

        /** Runs sharing one absolute start, which is the unit `text-anchor` applies to. */
        private class Chunk(val x: Double, val y: Double, val anchor: String) {
            val runs = ArrayList<TextRun>()
        }

        private fun gather(el: Element, parentStyle: Style, cursor: Cursor, chunks: MutableList<Chunk>) {
            val kids = el.childNodes
            for (i in 0 until kids.length) {
                val node = kids.item(i)
                if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                    val text = collapse(node.nodeValue ?: "")
                    if (text.isNotEmpty()) chunks.last().runs.add(TextRun(text, parentStyle, 0.0, 0.0))
                    continue
                }
                val child = node as? Element ?: continue
                val name = localName(child)
                if (name == "textPath") {
                    skipped.add("text on a path")
                    continue
                }
                if (name != "tspan") continue
                val style = parentStyle.inherit(child, css)
                if (!style.visible) continue
                // An absolute position starts a new chunk, which is what anchoring is measured over.
                val ax = attr(child, "x")?.let { length(it, cursor.x, width) }
                val ay = attr(child, "y")?.let { length(it, cursor.y, height) }
                if (ax != null || ay != null) {
                    cursor.x = ax ?: cursor.x
                    cursor.y = ay ?: cursor.y
                    chunks.add(Chunk(cursor.x, cursor.y, style.textAnchor))
                }
                val dx = attr(child, "dx")?.let { length(it, 0.0, width) } ?: 0.0
                val dy = attr(child, "dy")?.let { length(it, 0.0, height) } ?: 0.0
                if (dx != 0.0 || dy != 0.0) {
                    // A shift moves the pen for everything after it, so it starts its own chunk too.
                    chunks.add(Chunk(cursor.x + dx, cursor.y + dy, style.textAnchor))
                    cursor.x += dx
                    cursor.y += dy
                }
                gather(child, style, cursor, chunks)
            }
        }

        /** XML whitespace collapsed the way SVG's default `xml:space` asks for. */
        private fun collapse(raw: String): String {
            val out = StringBuilder(raw.length)
            var space = false
            for (c in raw) {
                if (c.isWhitespace()) {
                    space = out.isNotEmpty()
                } else {
                    if (space) out.append(' ')
                    space = false
                    out.append(c)
                }
            }
            return out.toString()
        }

        /**
         * The path's box before it is placed, which is what `objectBoundingBox` gradient units
         * resolve against. Taken from the control points, which contain the true outline: a curve
         * never leaves its own control hull, so this is at worst slightly generous.
         */
        private fun controlBox(contours: List<VectorContour>): Rect {
            var l = Double.MAX_VALUE
            var t = Double.MAX_VALUE
            var r = -Double.MAX_VALUE
            var b = -Double.MAX_VALUE
            fun add(p: Pt) {
                l = minOf(l, p.x)
                t = minOf(t, p.y)
                r = maxOf(r, p.x)
                b = maxOf(b, p.y)
            }
            for (c in contours) {
                add(c.start)
                for (seg in c.segments) {
                    if (seg is VectorSeg.Cubic) {
                        add(seg.c1)
                        add(seg.c2)
                    }
                    add(seg.end)
                }
            }
            if (l > r || t > b) return Rect(0.0, 0.0, 0.0, 0.0)
            return Rect(l, t, r - l, b - t)
        }

        /**
         * One paint source resolved to what the mesher draws with. A reference to something the
         * pipeline cannot build is named in [skipped] rather than silently painting the wrong
         * colour, which is what makes a coverage gap visible.
         */
        private fun paint(
            source: String?,
            channelOpacity: Double,
            style: Style,
            ctm: Affine,
            box: Rect,
        ): VectorPaint? {
            val s = source?.trim() ?: return null
            if (s.isEmpty() || s.equals("none", true)) return null
            val alpha = channelOpacity * style.opacity
            if (s.startsWith("url(")) return referencedPaint(s, alpha, ctm, box)
            val base = if (s.equals("currentColor", true)) style.color else SvgColors.parse(s) ?: return null
            val a = (base.a / 255.0) * alpha
            if (a <= 0.0) return null
            return VectorPaint.Solid(base.withAlpha((a * 255.0).toInt().coerceIn(0, 255)))
        }

        /**
         * A `url(#x)` paint resolved to a gradient, or named in [skipped] when it points at
         * something the pipeline cannot build.
         */
        private fun referencedPaint(source: String, alpha: Double, ctm: Affine, box: Rect): VectorPaint? {
            val id = source.substringAfter('#', "").substringBefore(')').trim()
            val el = byId[id]
            return when (localName(el ?: return skip("paint server"))) {
                "linearGradient" -> gradient(el, alpha, ctm, box, radial = false)
                "radialGradient" -> gradient(el, alpha, ctm, box, radial = true)
                "pattern" -> skip("pattern")
                else -> skip("paint server")
            }
        }

        private fun skip(feature: String): VectorPaint? {
            skipped.add(feature)
            return null
        }

        /**
         * A gradient in document space. Everything a gradient's geometry depends on is resolved
         * here — bounding-box units, `gradientTransform`, the referring element's own transform —
         * so nothing downstream carries a matrix or needs to know what the path's box was.
         */
        private fun gradient(el: Element, alpha: Double, ctm: Affine, box: Rect, radial: Boolean): VectorPaint? {
            val stops = gradientStops(el, alpha)
            if (stops.isEmpty()) return null
            val onBox = (gradientAttr(el, "gradientUnits") ?: "objectBoundingBox").trim() != "userSpaceOnUse"
            if (onBox && (box.w <= 0.0 || box.h <= 0.0)) return null
            val units = if (onBox) {
                Affine.translate(box.left, box.top).times(Affine.scale(box.w, box.h))
            } else {
                Affine.IDENTITY
            }
            val m = ctm.times(units).times(transform(gradientAttr(el, "gradientTransform")))
            val spread = when (gradientAttr(el, "spreadMethod")?.trim()?.lowercase()) {
                "reflect" -> SpreadMethod.REFLECT
                "repeat" -> SpreadMethod.REPEAT
                else -> SpreadMethod.PAD
            }
            fun coord(name: String, fallback: Double, reference: Double) =
                gradientCoord(gradientAttr(el, name), fallback, onBox, reference)
            if (!radial) {
                val a = m.map(Pt(coord("x1", 0.0, width), coord("y1", 0.0, height)))
                val b = m.map(Pt(coord("x2", 1.0, width), coord("y2", 0.0, height)))
                return VectorPaint.Linear(a.x, a.y, b.x, b.y, stops, spread)
            }
            val cx = coord("cx", 0.5, width)
            val cy = coord("cy", 0.5, height)
            val c = m.map(Pt(cx, cy))
            val f = m.map(Pt(coord("fx", cx, width), coord("fy", cy, height)))
            // A non-uniform transform would make the circle an ellipse; the length scale is the
            // circular reading of it, which is what the one file in a hundred that does this loses.
            val r = coord("r", 0.5, diagonal) * m.lengthScale()
            return VectorPaint.Radial(c.x, c.y, r, f.x, f.y, stops, spread)
        }

        /** A gradient coordinate: a fraction under bounding-box units, a length under user space. */
        private fun gradientCoord(text: String?, fallback: Double, onBox: Boolean, reference: Double): Double {
            val t = text?.trim() ?: return fallback
            if (t.isEmpty()) return fallback
            if (t.endsWith("%")) {
                val v = t.dropLast(1).toDoubleOrNull() ?: return fallback
                return if (onBox) v / 100.0 else v / 100.0 * reference
            }
            if (onBox) return t.toDoubleOrNull() ?: fallback
            return length(t, fallback, reference)
        }

        /** An attribute of [el] or of whatever it inherits from through `href`. */
        private fun gradientAttr(el: Element, name: String): String? {
            var cur: Element? = el
            var hops = 0
            while (cur != null && hops++ < MAX_HREF_HOPS) {
                attr(cur, name)?.let { return it }
                cur = hrefTarget(cur)
            }
            return null
        }

        /** The nearest stops up the `href` chain, which is how a file shares one ramp everywhere. */
        private fun gradientStops(el: Element, alpha: Double): List<GradientStop> {
            var cur: Element? = el
            var hops = 0
            while (cur != null && hops++ < MAX_HREF_HOPS) {
                val stops = children(cur).filter { localName(it) == "stop" }
                if (stops.isNotEmpty()) return stops.mapNotNull { stop(it, alpha) }
                cur = hrefTarget(cur)
            }
            return emptyList()
        }

        private fun stop(el: Element, alpha: Double): GradientStop? {
            val decl = css.declarationsFor(el) + Style.inlineStyle(attr(el, "style"))
            fun prop(name: String) = decl[name] ?: attr(el, name)
            val color = SvgColors.parse(prop("stop-color") ?: "black") ?: return null
            val opacity = prop("stop-opacity")?.let { Style.alpha(it) } ?: 1.0
            val a = (color.a / 255.0) * opacity * alpha
            val offsetText = (prop("offset") ?: "0").trim()
            val offset = if (offsetText.endsWith("%")) {
                (offsetText.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0
            } else {
                offsetText.toDoubleOrNull() ?: 0.0
            }
            return GradientStop(offset, color.withAlpha((a * 255.0).toInt().coerceIn(0, 255)))
        }

        private fun hrefTarget(el: Element): Element? {
            val href = (attr(el, "href") ?: attrNs(el, XLINK, "href"))?.trim() ?: return null
            if (!href.startsWith("#")) return null
            return byId[href.substring(1)]
        }

        /**
         * The element's own `clip-path` intersected with whatever it inherited. A rectangular clip
         * is kept and applied to the geometry; anything else is named and ignored, since dropping
         * the clipped content entirely would hide far more than the clip ever would.
         */
        private fun clipped(style: Style, el: Element, ctm: Affine): Style {
            val source = (Style.inlineStyle(attr(el, "style"))["clip-path"] ?: attr(el, "clip-path"))?.trim()
                ?: return style
            if (!source.startsWith("url(")) return style
            @Suppress("NAME_SHADOWING")
            val id = source.substringAfter('#', "").substringBefore(')').trim()
            val el2 = byId[id] ?: return style
            if (localName(el2) != "clipPath") return style
            val rect = clipRect(el2, ctm) ?: return skipClip(style)
            return style.withClip(rect)
        }

        private fun skipClip(style: Style): Style {
            skipped.add("clip path")
            return style
        }

        /**
         * A `clipPath` as one axis-aligned rectangle in document space, or null when it is anything
         * else. The artboard clip an exporter writes is exactly this shape, which is the case worth
         * getting right; a genuinely arbitrary clip would need a polygon boolean library.
         */
        private fun clipRect(el: Element, ctm: Affine): Rect? {
            if ((attr(el, "clipPathUnits") ?: "userSpaceOnUse").trim() != "userSpaceOnUse") return null
            val kids = children(el).filter { localName(it) != "title" && localName(it) != "desc" }
            val only = kids.singleOrNull() ?: return null
            val contours = when (localName(only)) {
                "rect" -> rect(only)
                "path" -> SvgPathData.parse(attr(only, "d") ?: "")
                else -> return null
            }
            val m = ctm.times(transform(attr(only, "transform")))
            // Only a straight rectangle survives: a corner radius or a turn is not a box any more.
            if (attr(only, "rx") != null || attr(only, "ry") != null) return null
            if (kotlin.math.abs(m.b) > 1e-9 || kotlin.math.abs(m.c) > 1e-9) return null
            val contour = contours.singleOrNull() ?: return null
            if (contour.segments.any { it !is VectorSeg.Line } || contour.segments.size !in 3..4) return null
            val pts = listOf(contour.start) + contour.segments.map { it.end }
            val xs = pts.map { it.x }.distinct()
            val ys = pts.map { it.y }.distinct()
            if (xs.size != 2 || ys.size != 2) return null
            val a = m.map(Pt(xs.min(), ys.min()))
            val b = m.map(Pt(xs.max(), ys.max()))
            return Rect.fromPoints(a, b)
        }

        private fun transformContour(c: VectorContour, m: Affine): VectorContour {
            if (m.isIdentity) return c
            return VectorContour(
                m.map(c.start),
                c.segments.map { seg ->
                    when (seg) {
                        is VectorSeg.Line -> VectorSeg.Line(m.map(seg.end))
                        is VectorSeg.Cubic -> VectorSeg.Cubic(m.map(seg.c1), m.map(seg.c2), m.map(seg.end))
                    }
                },
                c.closed,
            )
        }
    }

    // --- style ---

    /**
     * The inherited presentation state. SVG resolves a property from the `style` attribute first,
     * then the stylesheet, then the presentation attribute, then whatever the parent had, so the
     * lookup order here is not incidental.
     *
     * Paints are held as their source text rather than as a resolved colour, because `url(#grad)`
     * cannot be resolved until the gradient is known and `currentColor` cannot be resolved until
     * `color` is.
     */
    private class Style(
        val fill: String?,
        val stroke: String?,
        val color: Rgba,
        val fillOpacity: Double,
        val strokeOpacity: Double,
        val opacity: Double,
        val fillRule: FillRule,
        val strokeWidth: Double,
        val cap: LineCap,
        val join: LineJoin,
        val miterLimit: Double,
        val dash: DoubleArray?,
        val dashOffset: Double,
        val visible: Boolean,
        /** The rectangular clip in force, in document space; nested clips intersect. */
        val clip: Rect?,
        val fontFamily: String?,
        val fontSize: Double,
        val bold: Boolean,
        val italic: Boolean,
        val letterSpacing: Double,
        val textAnchor: String,
    ) {

        fun withClip(rect: Rect): Style = copyWith(clip?.let { intersection(it, rect) } ?: rect)

        private fun copyWith(newClip: Rect?) = Style(
            fill, stroke, color, fillOpacity, strokeOpacity, opacity, fillRule, strokeWidth,
            cap, join, miterLimit, dash, dashOffset, visible, newClip,
            fontFamily, fontSize, bold, italic, letterSpacing, textAnchor,
        )

        fun glyphStyle() = GlyphStyle(fontFamily, fontSize, bold, italic, letterSpacing)

        fun inherit(el: Element, css: CssRules): Style {
            val decl = css.declarationsFor(el) + inlineStyle(attr(el, "style"))
            fun prop(name: String): String? = decl[name] ?: attr(el, name)
            val display = prop("display")?.trim()?.lowercase()
            val visibility = prop("visibility")?.trim()?.lowercase()
            return Style(
                fill = prop("fill") ?: fill,
                stroke = prop("stroke") ?: stroke,
                color = prop("color")?.let { SvgColors.parse(it) } ?: color,
                fillOpacity = prop("fill-opacity")?.let { alpha(it) } ?: fillOpacity,
                strokeOpacity = prop("stroke-opacity")?.let { alpha(it) } ?: strokeOpacity,
                // Element opacity does not inherit; it multiplies down the tree, and a group's own
                // opacity needs an offscreen pass to be exact, so this is the flat approximation.
                opacity = opacity * (prop("opacity")?.let { alpha(it) } ?: 1.0),
                fillRule = when (prop("fill-rule")?.trim()?.lowercase()) {
                    "evenodd" -> FillRule.EVEN_ODD
                    "nonzero" -> FillRule.NONZERO
                    else -> fillRule
                },
                strokeWidth = prop("stroke-width")?.let { length(it, strokeWidth) } ?: strokeWidth,
                cap = when (prop("stroke-linecap")?.trim()?.lowercase()) {
                    "round" -> LineCap.ROUND
                    "square" -> LineCap.SQUARE
                    "butt" -> LineCap.BUTT
                    else -> cap
                },
                join = when (prop("stroke-linejoin")?.trim()?.lowercase()) {
                    "round" -> LineJoin.ROUND
                    "bevel" -> LineJoin.BEVEL
                    "miter", "miter-clip" -> LineJoin.MITER
                    else -> join
                },
                miterLimit = prop("stroke-miterlimit")?.toDoubleOrNull() ?: miterLimit,
                dash = prop("stroke-dasharray")?.let { parseDash(it) } ?: dash,
                dashOffset = prop("stroke-dashoffset")?.let { length(it, 0.0) } ?: dashOffset,
                visible = visible && display != "none" && visibility != "hidden" && visibility != "collapse",
                clip = clip,
                fontFamily = prop("font-family")?.let { firstFamily(it) } ?: fontFamily,
                fontSize = prop("font-size")?.let { fontSize(it, fontSize) } ?: fontSize,
                bold = prop("font-weight")?.let { weightIsBold(it, bold) } ?: bold,
                italic = prop("font-style")?.let { s ->
                    s.trim().lowercase().let { it == "italic" || it == "oblique" }
                } ?: italic,
                letterSpacing = prop("letter-spacing")?.let { spacing(it, fontSize) } ?: letterSpacing,
                textAnchor = prop("text-anchor")?.trim()?.lowercase() ?: textAnchor,
            )
        }

        private fun parseDash(text: String): DoubleArray? {
            val t = text.trim().lowercase()
            if (t.isEmpty() || t == "none") return null
            val vals = numbers(text).filter { it >= 0.0 }
            if (vals.isEmpty() || vals.all { it <= 0.0 }) return null
            // An odd-length pattern repeats to make it even, per the spec.
            return (if (vals.size % 2 == 0) vals else vals + vals).toDoubleArray()
        }

        companion object {
            val ROOT = Style(
                fill = null, stroke = null, color = Rgba(0, 0, 0), fillOpacity = 1.0,
                strokeOpacity = 1.0, opacity = 1.0, fillRule = FillRule.NONZERO, strokeWidth = 1.0,
                cap = LineCap.BUTT, join = LineJoin.MITER, miterLimit = 4.0, dash = null,
                dashOffset = 0.0, visible = true, clip = null, fontFamily = null,
                fontSize = DEFAULT_FONT_SIZE, bold = false, italic = false, letterSpacing = 0.0,
                textAnchor = "start",
            )

            /** The first family a font stack names; the platform matches or substitutes it. */
            fun firstFamily(text: String): String? = text.split(',').firstOrNull()
                ?.trim()
                ?.trim('"', '\'')
                ?.takeIf { it.isNotEmpty() }

            /** A font size, which may be relative to the size it inherited. */
            fun fontSize(text: String, inherited: Double): Double {
                val t = text.trim().lowercase()
                if (t.endsWith("%")) return (t.dropLast(1).toDoubleOrNull() ?: 100.0) / 100.0 * inherited
                if (t.endsWith("em")) return (t.dropLast(2).toDoubleOrNull() ?: 1.0) * inherited
                if (t.endsWith("ex")) return (t.dropLast(2).toDoubleOrNull() ?: 1.0) * inherited / 2.0
                return NAMED_SIZES[t] ?: length(t, inherited)
            }

            fun weightIsBold(text: String, inherited: Boolean): Boolean {
                val t = text.trim().lowercase()
                return when (t) {
                    "bold", "bolder" -> true
                    "normal", "lighter" -> false
                    else -> t.toIntOrNull()?.let { it >= 600 } ?: inherited
                }
            }

            fun spacing(text: String, fontSize: Double): Double {
                val t = text.trim().lowercase()
                if (t == "normal") return 0.0
                if (t.endsWith("em")) return (t.dropLast(2).toDoubleOrNull() ?: 0.0) * fontSize
                return length(t, 0.0)
            }

            private val NAMED_SIZES = mapOf(
                "xx-small" to 9.0, "x-small" to 10.0, "small" to 13.0, "medium" to 16.0,
                "large" to 18.0, "x-large" to 24.0, "xx-large" to 32.0,
            )

            fun alpha(text: String): Double {
                val t = text.trim()
                val v = if (t.endsWith("%")) {
                    (t.dropLast(1).toDoubleOrNull() ?: 100.0) / 100.0
                } else {
                    t.toDoubleOrNull() ?: 1.0
                }
                return v.coerceIn(0.0, 1.0)
            }

            fun inlineStyle(text: String?): Map<String, String> {
                if (text.isNullOrBlank()) return emptyMap()
                val out = HashMap<String, String>()
                for (part in text.split(';')) {
                    val colon = part.indexOf(':')
                    if (colon <= 0) continue
                    out[part.substring(0, colon).trim().lowercase()] = part.substring(colon + 1).trim()
                }
                return out
            }
        }
    }

    /**
     * The `<style>` blocks, reduced to the selectors real files use: a tag name, a class, an id, or
     * a comma-separated list of those. Anything more involved is ignored, which loses styling
     * rather than mangling it.
     */
    private class CssRules {
        private val byClass = HashMap<String, MutableMap<String, String>>()
        private val byTag = HashMap<String, MutableMap<String, String>>()
        private val byId = HashMap<String, MutableMap<String, String>>()

        fun collect(el: Element) {
            if (localName(el) == "style") {
                parse(el.textContent ?: "")
            } else {
                for (child in children(el)) collect(child)
            }
        }

        private fun parse(text: String) {
            val css = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            var i = 0
            while (true) {
                val open = css.indexOf('{', i)
                if (open < 0) break
                val close = css.indexOf('}', open)
                if (close < 0) break
                val selectors = css.substring(i, open)
                val body = Style.inlineStyle(css.substring(open + 1, close))
                if (!selectors.contains('@')) {
                    for (raw in selectors.split(',')) {
                        val sel = raw.trim()
                        // Only a single simple selector; a descendant combinator would need a real
                        // matcher, and getting it half right is worse than not styling at all.
                        if (sel.isEmpty() || sel.any { it.isWhitespace() } || sel.contains('>')) continue
                        val into = when {
                            sel.startsWith(".") -> byClass.getOrPut(sel.substring(1)) { HashMap() }
                            sel.startsWith("#") -> byId.getOrPut(sel.substring(1)) { HashMap() }
                            sel.all { it.isLetterOrDigit() || it == '-' } -> byTag.getOrPut(sel.lowercase()) { HashMap() }
                            else -> continue
                        }
                        into.putAll(body)
                    }
                }
                i = close + 1
            }
        }

        /** Declarations for [el], weakest source first, so the caller's own `style` attribute wins. */
        fun declarationsFor(el: Element): Map<String, String> {
            if (byTag.isEmpty() && byClass.isEmpty() && byId.isEmpty()) return emptyMap()
            val out = HashMap<String, String>()
            byTag[localName(el).lowercase()]?.let { out.putAll(it) }
            attr(el, "class")?.split(' ')?.forEach { c ->
                if (c.isNotBlank()) byClass[c.trim()]?.let { out.putAll(it) }
            }
            attr(el, "id")?.let { id -> byId[id]?.let { out.putAll(it) } }
            return out
        }
    }

    // --- attribute helpers ---

    private fun localName(node: Node): String = node.localName ?: node.nodeName ?: ""

    /** The overlap of two clips; an empty result is a clip that hides everything, which is legal. */
    private fun intersection(a: Rect, b: Rect) = Rect.fromPoints(
        Pt(maxOf(a.left, b.left), maxOf(a.top, b.top)),
        Pt(maxOf(maxOf(a.left, b.left), minOf(a.right, b.right)), maxOf(maxOf(a.top, b.top), minOf(a.bottom, b.bottom))),
    )

    private fun attr(el: Element, name: String): String? =
        el.getAttribute(name).takeIf { it.isNotEmpty() }

    private fun attrNs(el: Element, ns: String, name: String): String? =
        runCatching { el.getAttributeNS(ns, name) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun children(el: Element): List<Element> {
        val kids = el.childNodes
        val out = ArrayList<Element>(kids.length)
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { out.add(it) }
        return out
    }

    /** Every number in [text], whatever separates them. */
    private fun numbers(text: String?): List<Double> {
        if (text.isNullOrBlank()) return emptyList()
        return NUMBER.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
    }

    /**
     * A CSS length in user units. Percentages resolve against [reference], which is the viewport
     * side the attribute belongs to; absolute units use the CSS 96-per-inch scale.
     */
    private fun length(text: String?, fallback: Double, reference: Double = 0.0): Double {
        val t = text?.trim() ?: return fallback
        if (t.isEmpty()) return fallback
        if (t.endsWith("%")) {
            val v = t.dropLast(1).toDoubleOrNull() ?: return fallback
            return v / 100.0 * reference
        }
        for ((suffix, factor) in UNITS) {
            if (t.endsWith(suffix)) {
                val v = t.dropLast(suffix.length).trim().toDoubleOrNull() ?: return fallback
                return v * factor
            }
        }
        return t.toDoubleOrNull() ?: fallback
    }

    /** An SVG `transform` list, composed left to right as the spec reads it. */
    private fun transform(text: String?): Affine {
        val t = text?.trim() ?: return Affine.IDENTITY
        if (t.isEmpty()) return Affine.IDENTITY
        var m = Affine.IDENTITY
        for (match in FUNCTION.findAll(t)) {
            val name = match.groupValues[1].trim().lowercase()
            val n = numbers(match.groupValues[2])
            val step = when (name) {
                "matrix" -> if (n.size >= 6) Affine(n[0], n[1], n[2], n[3], n[4], n[5]) else Affine.IDENTITY
                "translate" -> Affine.translate(n.getOrElse(0) { 0.0 }, n.getOrElse(1) { 0.0 })
                "scale" -> Affine.scale(n.getOrElse(0) { 1.0 }, n.getOrElse(1) { n.getOrElse(0) { 1.0 } })
                "rotate" -> rotate(n)
                "skewx" -> Affine(c = tan(n.getOrElse(0) { 0.0 } * PI / 180.0))
                "skewy" -> Affine(b = tan(n.getOrElse(0) { 0.0 } * PI / 180.0))
                else -> Affine.IDENTITY
            }
            m = m.times(step)
        }
        return m
    }

    private fun rotate(n: List<Double>): Affine {
        val a = n.getOrElse(0) { 0.0 } * PI / 180.0
        val turn = Affine(cos(a), sin(a), -sin(a), cos(a))
        if (n.size < 3) return turn
        return Affine.translate(n[1], n[2]).times(turn).times(Affine.translate(-n[1], -n[2]))
    }

    private const val XLINK = "http://www.w3.org/1999/xlink"

    /** Control-point distance that makes a cubic a quarter ellipse, to within a thousandth. */
    private const val KAPPA = 0.5522847498307933

    private const val DEFAULT_SIZE = 512.0

    /** CSS's own initial font size, which SVG inherits. */
    private const val DEFAULT_FONT_SIZE = 16.0

    /** A ceiling so a machine-generated map cannot mesh the app into the ground. */
    private const val MAX_PATHS = 20000

    private const val MAX_USE_EXPANSIONS = 20000

    private const val MAX_USE_DEPTH = 32

    /** How far a gradient's `href` chain is followed for its stops and attributes. */
    private const val MAX_HREF_HOPS = 8

    private val NEVER_DRAWN = setOf(
        "defs", "symbol", "clipPath", "mask", "marker", "pattern", "filter",
        "style", "title", "desc", "metadata", "script", "linearGradient", "radialGradient",
    )

    private val NUMBER = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

    private val FUNCTION = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")

    private val UNITS = listOf(
        "px" to 1.0, "pt" to 96.0 / 72.0, "pc" to 16.0, "mm" to 96.0 / 25.4,
        "cm" to 96.0 / 2.54, "in" to 96.0, "q" to 96.0 / 101.6,
    )
}
