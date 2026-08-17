package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.GlyphOutliner
import com.xnotes.core.vector.GlyphRun
import com.xnotes.core.vector.GlyphStyle
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorScene
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text layout, against a stand-in outliner: every character is a box one half-em wide and one em
 * tall sitting on the baseline. Fixed metrics are the point, since what is being checked is where
 * the runs land rather than what a font does with them.
 */
class SvgTextTest {

    private object BoxGlyphs : GlyphOutliner {
        override fun outline(text: String, style: GlyphStyle): GlyphRun? {
            if (text.isEmpty()) return null
            val contours = ArrayList<VectorContour>()
            for (i in text.indices) {
                if (text[i] == ' ') continue
                val x = i * advance(style)
                val top = -style.size
                contours.add(
                    VectorContour(
                        Pt(x, top),
                        listOf(
                            VectorSeg.Line(Pt(x + advance(style), top)),
                            VectorSeg.Line(Pt(x + advance(style), 0.0)),
                            VectorSeg.Line(Pt(x, 0.0)),
                        ),
                        closed = true,
                    ),
                )
            }
            return GlyphRun(contours, measure(text, style))
        }

        override fun measure(text: String, style: GlyphStyle): Double = text.length * advance(style)

        private fun advance(style: GlyphStyle) = style.size / 2.0 + style.letterSpacing
    }

    private fun read(body: String): VectorScene =
        SvgReader.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">$body</svg>""".toByteArray(),
            BoxGlyphs,
        )

    private fun span(scene: VectorScene): Pair<Double, Double> {
        var l = Double.MAX_VALUE
        var r = -Double.MAX_VALUE
        for (path in scene.paths) {
            for (c in path.contours) {
                for (p in PathFlattener.flatten(c, 0.01)) {
                    l = minOf(l, p.x)
                    r = maxOf(r, p.x)
                }
            }
        }
        return l to r
    }

    @Test
    fun `text without an outliner is named rather than drawn`() {
        val scene = SvgReader.parse(
            """<svg xmlns="http://www.w3.org/2000/svg"><text x="0" y="0">hi</text></svg>""".toByteArray(),
        )
        assertTrue(scene.paths.isEmpty())
        assertTrue(scene.skipped.contains("text"))
    }

    @Test
    fun `text starts at its own x and sits on its baseline`() {
        val scene = read("""<text x="10" y="40" font-size="20">ab</text>""")
        assertEquals(1, scene.paths.size)
        val (l, r) = span(scene)
        assertEquals(10.0, l, 1e-9)
        assertEquals(30.0, r, 1e-9)
        val ys = scene.paths[0].contours.flatMap { PathFlattener.flatten(it, 0.01) }.map { it.y }
        assertEquals(40.0, ys.max(), 1e-9)
        assertEquals(20.0, ys.min(), 1e-9)
    }

    @Test
    fun `text-anchor middle centres the line on its x`() {
        val scene = read("""<text x="50" y="40" font-size="20" text-anchor="middle">ab</text>""")
        val (l, r) = span(scene)
        assertEquals(50.0, (l + r) / 2.0, 1e-9)
    }

    @Test
    fun `text-anchor end finishes at its x`() {
        val (_, r) = span(read("""<text x="50" y="40" font-size="20" text-anchor="end">ab</text>"""))
        assertEquals(50.0, r, 1e-9)
    }

    @Test
    fun `a tspan continues where the last run stopped`() {
        val scene = read("""<text x="0" y="40" font-size="20">ab<tspan>cd</tspan></text>""")
        assertEquals(2, scene.paths.size)
        assertEquals(40.0, span(scene).second, 1e-9)
    }

    @Test
    fun `a tspan with its own x starts a new chunk there`() {
        val scene = read("""<text x="0" y="40" font-size="20">ab<tspan x="60">cd</tspan></text>""")
        val second = PathFlattener.flatten(scene.paths[1].contours[0], 0.01)
        assertEquals(60.0, second.minOf { it.x }, 1e-9)
    }

    @Test
    fun `a tspan inherits and overrides its parent's style`() {
        val scene = read("""<text x="0" y="40" font-size="20" fill="#ff0000">a<tspan fill="#0000ff">b</tspan></text>""")
        assertEquals(255, (scene.paths[0].fill as com.xnotes.core.vector.VectorPaint.Solid).color.r)
        assertEquals(255, (scene.paths[1].fill as com.xnotes.core.vector.VectorPaint.Solid).color.b)
    }

    @Test
    fun `whitespace collapses the way the default asks for`() {
        val scene = read("""<text x="0" y="40" font-size="20">  a   b  </text>""")
        // "a b" is three characters at half an em each.
        assertEquals(30.0, span(scene).second, 1e-9)
    }

    @Test
    fun `a relative font size resolves against the one it inherited`() {
        val scene = read("""<g font-size="20"><text x="0" y="40" font-size="150%">a</text></g>""")
        assertEquals(15.0, span(scene).second, 1e-9)
    }

    @Test
    fun `letter spacing widens the run`() {
        val tight = span(read("""<text x="0" y="40" font-size="20">abc</text>""")).second
        val loose = span(read("""<text x="0" y="40" font-size="20" letter-spacing="4">abc</text>""")).second
        assertEquals(tight + 12.0, loose, 1e-9)
    }

    @Test
    fun `text on a path is named rather than laid out wrong`() {
        val scene = read("""<text x="0" y="40"><textPath href="#p">hi</textPath></text>""")
        assertTrue(scene.skipped.contains("text on a path"))
    }

    @Test
    fun `a transform on the text element moves its outlines`() {
        val scene = read("""<text x="0" y="40" font-size="20" transform="translate(25,0)">a</text>""")
        assertEquals(25.0, span(scene).first, 1e-9)
    }
}
