package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.vector.Affine
import com.xnotes.core.vector.LineCap
import com.xnotes.core.vector.LineJoin
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

    fun parse(bytes: ByteArray): VectorScene {
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
        return Reader(root).read()
    }

    // --- the walk ---

    private class Reader(private val root: Element) {

        private val paths = ArrayList<VectorPath>()
        private val skipped = LinkedHashSet<String>()
        private val byId = HashMap<String, Element>()
        private val css = CssRules()
        private var used = 0
        private var useDepth = 0

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
            val width = (if (w > 0.0) w else vb?.w ?: DEFAULT_SIZE).coerceAtLeast(1e-6)
            val height = (if (h > 0.0) h else vb?.h ?: DEFAULT_SIZE).coerceAtLeast(1e-6)
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
            if (name in NEVER_DRAWN) {
                if (name == "mask" || name == "filter" || name == "pattern") skipped.add(name)
                return
            }
            val style = parentStyle.inherit(el, css)
            if (!style.visible) return
            val ctm = parentCtm.times(transform(attr(el, "transform")))
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
                "text", "tspan", "textPath" -> skipped.add("text")
                "image" -> skipped.add("image")
                "foreignObject" -> skipped.add("foreignObject")
                else -> Unit
            }
        }

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
            val segs = ArrayList<VectorSeg>()
            val k = KAPPA
            segs.add(VectorSeg.Line(Pt(x + w - rx, y)))
            segs.add(corner(Pt(x + w - rx, y), Pt(x + w, y + ry), rx * k, ry * k, 1.0, 0.0, 0.0, 1.0))
            segs.add(VectorSeg.Line(Pt(x + w, y + h - ry)))
            segs.add(corner(Pt(x + w, y + h - ry), Pt(x + w - rx, y + h), rx * k, ry * k, 0.0, 1.0, 1.0, 0.0))
            segs.add(VectorSeg.Line(Pt(x + rx, y + h)))
            segs.add(corner(Pt(x + rx, y + h), Pt(x, y + h - ry), rx * k, ry * k, -1.0, 0.0, 0.0, -1.0))
            segs.add(VectorSeg.Line(Pt(x, y + ry)))
            segs.add(corner(Pt(x, y + ry), Pt(x + rx, y), rx * k, ry * k, 0.0, -1.0, -1.0, 0.0))
            return listOf(VectorContour(Pt(x + rx, y), segs, closed = true))
        }

        /** One rounded corner, as the cubic that approximates a quarter ellipse. */
        private fun corner(
            from: Pt,
            to: Pt,
            kx: Double,
            ky: Double,
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
        ) = VectorSeg.Cubic(
            Pt(from.x + ax * kx, from.y + ay * ky),
            Pt(to.x + bx * kx, to.y + by * ky),
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
            val fill = paint(style.fill ?: "black", style.fillOpacity, style)
            val stroke = if (style.strokeWidth > 0.0) paint(style.stroke, style.strokeOpacity, style) else null
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
                ),
            )
        }

        /**
         * One paint source resolved to what the mesher draws with. A reference to something the
         * pipeline cannot build is named in [skipped] rather than silently painting the wrong
         * colour, which is what makes a coverage gap visible.
         */
        private fun paint(source: String?, channelOpacity: Double, style: Style): VectorPaint? {
            val s = source?.trim() ?: return null
            if (s.isEmpty() || s.equals("none", true)) return null
            if (s.startsWith("url(")) {
                skipped.add(paintReferenceKind(s))
                return null
            }
            val base = if (s.equals("currentColor", true)) style.color else SvgColors.parse(s) ?: return null
            val a = (base.a / 255.0) * channelOpacity * style.opacity
            if (a <= 0.0) return null
            return VectorPaint.Solid(base.withAlpha((a * 255.0).toInt().coerceIn(0, 255)))
        }

        /** What a `url(#x)` paint actually points at, so the log line names the real feature. */
        private fun paintReferenceKind(source: String): String {
            val id = source.substringAfter('#', "").substringBefore(')').trim()
            return when (localName(byId[id] ?: return "paint server")) {
                "linearGradient", "radialGradient" -> "gradient"
                "pattern" -> "pattern"
                else -> "paint server"
            }
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
    ) {

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
                dashOffset = 0.0, visible = true,
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

    /** A ceiling so a machine-generated map cannot mesh the app into the ground. */
    private const val MAX_PATHS = 20000

    private const val MAX_USE_EXPANSIONS = 20000

    private const val MAX_USE_DEPTH = 32

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
