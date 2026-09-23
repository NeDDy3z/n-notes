package com.xnotes.core.template

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.paintPagePattern
import com.xnotes.format.TemplateFormatException
import com.xnotes.format.TemplateReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateTest {

    private val ink = Rgba(10, 20, 30, 255)
    private val accent = Rgba(200, 0, 0, 128)

    private fun eval(json: String, w: Double = 100.0, h: Double = 100.0, paper: Rect? = null, values: TemplateValues = TemplateValues.DEFAULTS) =
        TemplateEval().evaluate(TemplateReader.read(json), TemplatePage(w, h, paper ?: Rect(0.0, 0.0, w, h), ink, accent, values))

    private fun tpl(items: String, extra: String = "") = """{ "xtemplate": 1, "name": "t", $extra "items": [ $items ] }"""

    private fun strokes(out: TemplateOutput) = out.layers.flatMap { it.prims }.filterIsInstance<TPrim.Stroke>()

    private fun ys(out: TemplateOutput) = strokes(out).map { it.pts[1] }

    private fun expr(s: String, w: Double = 200.0, h: Double = 100.0, axis: Axis = Axis.X, vars: Map<String, Double> = emptyMap()) =
        Expr.parse(s).eval(object : ExprEnv {
            override val boxW = w
            override val boxH = h
            override fun lookup(name: String) = vars[name]
        }, axis)

    private fun near(expected: List<Double>, actual: List<Double>) {
        assertEquals("count of $actual", expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-6) }
    }

    // --- expressions ---

    @Test
    fun expressionsHonourUnitsPercentAndPrecedence() {
        assertEquals(20.0, expr("2cm"), 1e-9)
        assertEquals(25.4, expr("1in"), 1e-9)
        assertEquals(185.0, expr("100% - 15mm"), 1e-9)
        assertEquals(50.0, expr("50%", axis = Axis.Y), 1e-9)
        assertEquals(50.0, expr("50%", axis = Axis.SHORT) * 2 - 50.0, 1e-9)
        assertEquals(0.5, expr("50%", axis = Axis.PLAIN), 1e-9)
        assertEquals(14.0, expr("2 + 3 * 4"), 1e-9)
        assertEquals(20.0, expr("(2 + 3) * 4"), 1e-9)
        assertEquals(-3.0, expr("-(1 + 2)"), 1e-9)
        assertEquals(20.0, expr("gap * 10", vars = mapOf("gap" to 2.0)), 1e-9)
        assertEquals(50.0, expr("min(W, H) / 2"), 1e-9)
    }

    @Test
    fun expressionFunctions() {
        assertEquals(1.0, expr("sin(90)"), 1e-9)
        assertEquals(-1.0, expr("cos(180deg)"), 1e-9)
        assertEquals(2.0, expr("log10(100)"), 1e-9)
        assertEquals(3.0, expr("round(2.5)"), 1e-9)
        assertEquals(-3.0, expr("round(-2.5)"), 1e-9)
        assertEquals(2.0, expr("mod(-1, 3)"), 1e-9)
        assertEquals(8.0, expr("pow(2, 3)"), 1e-9)
        assertEquals(7.0, expr("max(1, 7, 3)"), 1e-9)
    }

    @Test
    fun badExpressionsAreInvalid() {
        for (bad in listOf("2 mm", "3px", "foo(1)", "(1 + 2", "1 +", "sin(1, 2)", "", "1e5")) {
            assertTrue(bad, Expr.parse(bad) is Expr.Invalid)
        }
        assertTrue(runCatching { expr("1 / 0") }.isFailure)
        assertTrue(runCatching { expr("nope + 1") }.isFailure)
        assertTrue(runCatching { expr("sqrt(-1)") }.isFailure)
    }

    @Test
    fun literalLengths() {
        assertEquals(2.0, Expr.literalLength("2mm")!!, 1e-9)
        assertEquals(25.4, Expr.literalLength("1in")!!, 1e-9)
        assertEquals(3.0, Expr.literalLength(" 3 ")!!, 1e-9)
        assertEquals(null, Expr.literalLength("50%"))
        assertEquals(null, Expr.literalLength("gap * 2"))
    }

    // --- reader ---

    @Test(expected = TemplateFormatException::class)
    fun refusesJsonThatIsNotATemplate() {
        TemplateReader.read("""{ "name": "x", "items": [] }""")
    }

    @Test(expected = TemplateFormatException::class)
    fun refusesMalformedJson() {
        TemplateReader.read("""{ "xtemplate": 1, "items": [ """)
    }

    @Test
    fun skipsUnknownAndUnusableNodes() {
        val t = TemplateReader.read(
            tpl(
                """{ "type": "x-sparkle" }, { "type": "blob" }, { "type": "hline", "y": "2 +" },
                   { "type": "circle" }, { "type": "hline", "y": 5, "futureThing": true }""",
            ),
        )
        assertEquals(1, t.root.children.size)
    }

    @Test
    fun paramsParseClampAndDropBadNames() {
        val t = TemplateReader.read(
            tpl(
                "",
                """"params": {
                  "gap": { "type": "length", "default": "2mm", "min": "1mm", "max": "4mm", "role": "spacing" },
                  "rows": { "type": "integer", "default": 7, "min": 1, "max": 24 },
                  "frame": { "type": "color", "default": "#ff0000" },
                  "i": { "type": "number", "default": 1 },
                  "gap2": { "type": "length", "default": "3mm", "role": "spacing" }
                },""",
            ),
        )
        assertEquals(listOf("gap", "rows", "frame", "gap2"), t.params.map { it.name })
        assertEquals("gap", t.spacingParam?.name)
        assertEquals(4.0, t.param("gap")!!.clamp(9.0), 1e-9)
        assertEquals(3.0, t.param("rows")!!.clamp(2.6), 1e-9)
        assertEquals(Rgba(255, 0, 0), t.param("frame")!!.defaultColor)
    }

    // --- layout ---

    @Test
    fun ruledLinesSkipThePaperEdges() {
        val out = eval(tpl("""{ "type": "repeat", "axis": "y", "period": 10, "children": [ { "type": "hline", "y": 0 } ] }"""))
        near((1..9).map { it * 10.0 }, ys(out))
        val first = strokes(out).first()
        assertEquals(0.0, first.pts[0], 1e-9)
        assertEquals(100.0, first.pts[2], 1e-9)
    }

    @Test
    fun linesAtTheCellBottomAlsoSkipTheEdge() {
        val out = eval(tpl("""{ "type": "repeat", "axis": "y", "period": 10, "children": [ { "type": "hline", "y": "100%" } ] }"""))
        near((1..9).map { it * 10.0 }, ys(out))
    }

    @Test
    fun fitSpaceSpreadsWholeCells() {
        val out = eval(
            tpl("""{ "type": "repeat", "axis": "y", "period": 30, "fit": "space", "children": [ { "type": "hline", "y": 5 } ] }"""),
        )
        // floor(100 / 30) = 3 cells; the 10 mm left over is shared as two 5 mm gaps.
        near(listOf(5.0, 40.0, 75.0), ys(out))
    }

    @Test
    fun fitRoundStretchesAndCenterCentres() {
        val round = eval(tpl("""{ "type": "repeat", "axis": "y", "period": 30, "fit": "round", "children": [ { "type": "hline", "y": "50%" } ] }"""))
        near(listOf(100.0 / 6, 50.0, 100.0 - 100.0 / 6), ys(round))
        val center = eval(tpl("""{ "type": "repeat", "axis": "y", "period": 30, "fit": "center", "children": [ { "type": "hline", "y": 1 } ] }"""))
        near(listOf(6.0, 36.0, 66.0), ys(center))
    }

    @Test
    fun countDividesTheBox() {
        val out = eval(tpl("""{ "type": "repeat", "axis": "x", "count": 4, "children": [ { "type": "vline", "x": "50%" } ] }"""))
        near(listOf(12.5, 37.5, 62.5, 87.5), strokes(out).map { it.pts[0] })
    }

    @Test
    fun musicStaffFitsWholeStaves() {
        val json = """{
          "xtemplate": 1, "name": "staff",
          "params": { "gap": { "type": "length", "default": "2mm", "role": "spacing" } },
          "items": [ { "type": "group", "inset": ["15mm", "12mm"], "children": [
            { "type": "repeat", "axis": "y", "period": "gap * 10", "fit": "space", "children": [
              { "type": "repeat", "axis": "y", "count": 5, "period": "gap", "children": [ { "type": "hline", "y": "gap / 2" } ] }
            ]}
          ]}]
        }"""
        val out = eval(json, 210.0, 297.0)
        // 267 mm tall box, 20 mm staves: 13 whole staves, 65 lines, none outside the box.
        assertEquals(65, strokes(out).size)
        assertTrue(ys(out).all { it > 15.0 && it < 282.0 })
        assertTrue(strokes(out).all { it.pts[0] == 12.0 && it.pts[2] == 198.0 })
    }

    @Test
    fun paramValuesAreClampedIntoRange() {
        val json = tpl(
            """{ "type": "repeat", "axis": "y", "period": "gap", "children": [ { "type": "hline", "y": 0 } ] }""",
            """"params": { "gap": { "type": "length", "default": 10, "min": 20, "max": 50 } },""",
        )
        assertEquals(4, ys(eval(json)).size) // the default 10 clamps up to 20: lines at 20, 40, 60, 80
        assertEquals(1, ys(eval(json, values = TemplateValues(mapOf("gap" to 99.0)))).size)
    }

    @Test
    fun extendedRepeatsRuleTheMarginsAnchoredToThePage() {
        val json = tpl("""{ "type": "repeat", "axis": "y", "period": 10, "extend": true, "children": [ { "type": "hline", "y": 0 } ] }""")
        val out = eval(json, paper = Rect(-15.0, -25.0, 130.0, 150.0))
        near((-2..12).map { it * 10.0 }, ys(out))
        assertTrue(strokes(out).all { it.pts[0] == -15.0 && it.pts[2] == 115.0 })
        // Not extended: the same repeat stays on the page. Its edges are not the paper's, so the
        // lines lying on them are kept.
        val plain = eval(json.replace("\"extend\": true, ", ""), paper = Rect(-15.0, -25.0, 130.0, 150.0))
        near((0..9).map { it * 10.0 }, ys(plain))
    }

    @Test
    fun groupsClipOnlyWhenAsked() {
        val line = """{ "type": "hline", "y": 50, "x1": "-50mm", "x2": "200%" }"""
        val open = eval(tpl("""{ "type": "group", "inset": 20, "children": [ $line ] }"""))
        assertEquals(0.0, strokes(open).single().pts[0], 1e-9)
        assertEquals(100.0, strokes(open).single().pts[2], 1e-9)
        val clipped = eval(tpl("""{ "type": "group", "inset": 20, "clip": true, "children": [ $line ] }"""))
        assertEquals(20.0, strokes(clipped).single().pts[0], 1e-9)
        assertEquals(80.0, strokes(clipped).single().pts[2], 1e-9)
    }

    @Test
    fun repeatsClipToTheirBox() {
        // A Cornell-style ruled region: its last partial cell must not draw below the box.
        val out = eval(tpl("""{ "type": "group", "h": 45, "children": [
            { "type": "repeat", "axis": "y", "period": 10, "children": [ { "type": "hline", "y": "100%" } ] } ] }"""))
        near(listOf(10.0, 20.0, 30.0, 40.0), ys(out))
    }

    @Test
    fun staggeredDotsShiftOddRows() {
        val out = eval(tpl("""{ "type": "repeat", "axis": "xy", "period": [10, 10], "stagger": 5,
            "children": [ { "type": "dot", "cx": 0, "cy": 0 } ] }"""), 40.0, 30.0)
        val dots = out.layers.flatMap { it.prims }.filterIsInstance<TPrim.Ellipse>()
        val row10 = dots.filter { it.cy == 10.0 }.map { it.cx }
        val row20 = dots.filter { it.cy == 20.0 }.map { it.cx }
        near(listOf(5.0, 15.0, 25.0, 35.0), row10)
        near(listOf(10.0, 20.0, 30.0), row20)
    }

    @Test
    fun indexVariablesAndAliases() {
        val out = eval(tpl("""{ "type": "repeat", "axis": "y", "count": 3, "index": "row", "children": [
            { "type": "hline", "y": "row + i" } ] }"""))
        near(listOf(100.0 / 3 + 2.0, 200.0 / 3 + 4.0), ys(out))
    }

    @Test
    fun inPlaceRepeatOnlyCountsTheIndex() {
        val out = eval(tpl("""{ "type": "repeat", "axis": "none", "count": 3, "children": [
            { "type": "circle", "cx": "50%", "cy": "50%", "r": "(i + 1) * 10mm" } ] }"""))
        val rings = out.layers.flatMap { it.prims }.filterIsInstance<TPrim.Ellipse>()
        near(listOf(10.0, 20.0, 30.0), rings.map { it.rx })
        assertTrue(rings.all { it.cx == 50.0 && it.cy == 50.0 })
    }

    @Test
    fun hatchFillsTheBoxAtAnAngle() {
        val flat = eval(tpl("""{ "type": "hatch", "angle": 0, "period": 25 }"""))
        near(listOf(25.0, 50.0, 75.0), ys(flat))
        val steep = eval(tpl("""{ "type": "hatch", "angle": 45, "period": 10 }"""))
        for (s in strokes(steep)) {
            val dx = s.pts[2] - s.pts[0]
            val dy = s.pts[3] - s.pts[1]
            assertEquals(-1.0, dy / dx, 1e-9) // rising to the right on the page
        }
        assertEquals(14, strokes(steep).size) // 141.4 mm of diagonal at 10 mm; the line through a corner is dropped
    }

    @Test
    fun onlyThePapersEdgeDropsLinesLyingOnIt() {
        val out = eval(tpl("""{ "type": "group", "inset": 10, "clip": true, "children": [
            { "type": "vline", "x": 0 }, { "type": "hline", "y": "100%" } ] }, { "type": "vline", "x": "100%" }"""))
        assertEquals(2, strokes(out).size)
    }

    @Test
    fun dotsNeedTheirCentreStrictlyInside() {
        val out = eval(tpl("""{ "type": "dot", "cx": 0, "cy": 50 }, { "type": "dot", "cx": 1, "cy": 50 }"""))
        assertEquals(1, out.primCount)
    }

    @Test
    fun coloursMakeLayersAndOpacityFoldsIntoAlpha() {
        val out = eval(
            tpl(
                """{ "type": "hline", "y": 10 }, { "type": "hline", "y": 20, "stroke": "accent" },
                   { "type": "hline", "y": 30, "stroke": "#00ff00", "opacity": 0.5 }, { "type": "hline", "y": 40 },
                   { "type": "hline", "y": 50, "stroke": "frame" }, { "type": "hline", "y": 60, "stroke": "none" }""",
                """"params": { "frame": { "type": "color", "default": "#0000ff" } },""",
            ),
        )
        assertEquals(listOf(ink, accent, Rgba(0, 255, 0, 128), Rgba(0, 0, 255)), out.layers.map { it.color })
        assertEquals(2, out.layers[0].prims.size)
    }

    @Test
    fun invalidNodesAreSkippedAtEvaluation() {
        val out = eval(tpl("""{ "type": "hline", "y": "nope" }, { "type": "hline", "y": "10 / (i - i)" }, { "type": "hline", "y": 5 }"""))
        near(listOf(5.0), ys(out))
    }

    @Test
    fun rectsFillAndStrokeAndClip() {
        val out = eval(tpl("""{ "type": "rect", "x": 10, "y": 10, "w": 20, "h": 20, "fill": "accent" },
            { "type": "rect", "x": 50, "y": 50, "w": 100, "h": 10 }"""))
        val fill = out.layers.first { it.color == accent }.prims.single() as TPrim.FillRect
        assertEquals(Rect(10.0, 10.0, 20.0, 20.0), fill.rect)
        val outlines = out.layers.first { it.color == ink }.prims.filterIsInstance<TPrim.Stroke>()
        assertTrue(outlines[0].closed)
        // The second rect crosses the page's right edge: its outline is cut open there.
        assertTrue(outlines.drop(1).none { it.closed })
        assertTrue(outlines.drop(1).all { s -> (0 until s.pts.size step 2).all { s.pts[it] <= 100.0 } })
    }

    @Test
    fun textValuesFormatAsSpecified() {
        assertEquals("8", TemplateEval.formatValue(8.0, null))
        assertEquals("08", TemplateEval.formatValue(8.0, 2))
        assertEquals("1.5", TemplateEval.formatValue(1.5, null))
        assertEquals("0.333", TemplateEval.formatValue(1.0 / 3, null))
        assertEquals("-3", TemplateEval.formatValue(-3.0, null))
        assertEquals("0", TemplateEval.formatValue(-0.0000000001, null))
    }

    @Test
    fun shapeCapStopsLayout() {
        val out = TemplateEval().evaluate(
            TemplateReader.read(tpl("""{ "type": "repeat", "axis": "xy", "period": 1, "children": [ { "type": "dot", "cx": "50%", "cy": "50%" } ] }""")),
            TemplatePage(100.0, 100.0, ink = ink, accent = accent),
            maxPrims = 500,
        )
        assertEquals(500, out.primCount)
    }

    // --- the bundled rulings match the painter they replace ---

    private fun bundled(name: String) = TemplateReader.read(File("src/main/assets/templates/$name.xtemplate").readText())

    @Test
    fun bundledRulingsMatchTheOldPainter() {
        val dpi = 150.0
        val scale = dpi / 25.4
        val cover = Rect(-40.0, -30.0, 1320.0, 1800.0)
        val page = Rect(0.0, 0.0, 1240.0, 1754.0)
        val color = Rgba(150, 150, 150, 64)
        for ((name, pattern) in listOf("lines" to PagePattern.LINES, "grid" to PagePattern.GRID, "dots" to PagePattern.DOTS)) {
            for (spacing in listOf(64.0, 37.0)) {
                val old = FakeRenderer()
                paintPagePattern(old, pattern, color, spacing, cover, cover)
                val t = bundled(name)
                val out = TemplateEval().evaluate(
                    t,
                    TemplatePage(
                        page.w / scale, page.h / scale,
                        Rect(cover.x / scale, cover.y / scale, cover.w / scale, cover.h / scale),
                        color, color, TemplateValues(mapOf("spacing" to spacing / scale)),
                    ),
                )
                val neu = FakeRenderer()
                TemplatePainter.paint(neu, out, scale, cover)
                assertEquals("$name@$spacing segments", old.segments.size, neu.segments.size)
                old.segments.zip(neu.segments).forEach { (a, b) ->
                    assertEquals(a.first.x, b.first.x, 1e-6); assertEquals(a.first.y, b.first.y, 1e-6)
                    assertEquals(a.second.x, b.second.x, 1e-6); assertEquals(a.second.y, b.second.y, 1e-6)
                }
                assertEquals("$name@$spacing dots", old.circles.size, neu.circles.size)
                old.circles.zip(neu.circles).forEach { (a, b) ->
                    assertEquals(a.x, b.x, 1e-6); assertEquals(a.y, b.y, 1e-6)
                }
                assertEquals(old.ops.count { it == "saveLayerAlpha" }, neu.ops.count { it == "saveLayerAlpha" })
            }
        }
    }
}
